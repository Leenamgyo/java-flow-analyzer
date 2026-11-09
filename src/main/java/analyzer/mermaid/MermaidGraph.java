package analyzer.mermaid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mermaid flowchart 중간 표현. 렌더링 전에 ID 충돌·엣지 끝점을 검증한다. */
public final class MermaidGraph {

    /** 노드 여백·간격·자동 줄바꿈 폭 (라벨은 &lt;br/&gt; 로 직접 줄바꿈) */

    /**
     * 렌더링 설정 줄. layout "dagre" = Mermaid 기본, "elk" = 직각 간선 레이아웃(뷰어가 ELK 를 지원해야 함).
     */
    /** 코드 줄(#n …)이 들어가는 노드 클래스 — 글자를 가운데가 아니라 왼쪽에 맞춘다 */
    public static final List<String> LEFT_ALIGNED = List.of("codeline", "req", "bind", "var", "state", "log", "depth", "external", "unresolved", "cycle", "fin", "exc", "check", "post");

    private static final String LEFT_ALIGN_CSS = String.join(", ", LEFT_ALIGNED.stream()
            .map(c -> "." + c + " .nodeLabel, ." + c + " .nodeLabel p").toList()) + " { text-align: left !important; }";

    /** 기본(dagre) 설정 줄 — LEFT_ALIGN_CSS 뒤에 선언해야 초기화 순서가 맞다 */
    public static final String INIT = init("dagre");

    public static String init(String layout) {
        return init(layout, "light");
    }

    /** theme: light(Mermaid 기본 테마) | dark(Mermaid dark 테마 — 메서드 상자 배경·간선·라벨도 어둡게) */
    public static String init(String layout, String theme) {
        return "%%{init: {" + ("dark".equals(theme) ? "\"theme\": \"dark\", " : "")
                + "\"flowchart\": {\"padding\": 20, \"nodeSpacing\": 40, \"rankSpacing\": 50, \"wrappingWidth\": 2400}"
                + ("elk".equals(layout) ? ", \"layout\": \"elk\"" : "")
                + ", \"themeCSS\": \"" + LEFT_ALIGN_CSS + "\"}}%%";
    }

    /** 색 테마 이름 확인 (설정 output.color-theme) */
    public static String checkTheme(String theme) {
        if (!"dark".equals(theme) && !"light".equals(theme)) {
            throw new IllegalArgumentException("output.color-theme 은 dark 또는 light: " + theme);
        }
        return theme;
    }

    public enum Shape { RECT, ROUND, STADIUM, DIAMOND, HEX, SUBROUTINE, ASYM }

    public enum EdgeStyle {
        FLOW("-->"), CALL("==>"), EXCEPTION("-.->");

        final String arrow;

        EdgeStyle(String arrow) {
            this.arrow = arrow;
        }
    }

    public record Node(String id, List<String> lines, Shape shape, String cssClass) {
    }

    /**
     * 글자 색으로 구분하는 줄 (한 노드 안에서 종류 구분): "req" = 요청 파라미터, "fn" = 바인딩 함수.
     * 태그는 {@code <font color='#rrggbb'>} — {@code <span style='color:…'>} 를 쓰면 Mermaid(11.x)가 한 노드에 그런 span 이
     * 둘 이상 나온 뒤 줄의 엔티티 세미콜론을 잃어 "#7" 이 "#357", "#quot;" 가 "#quot" 로 보인다 (SVG 렌더링으로 확인).
     * 색 뒤에 ';' 가 없으므로 #rrggbb 가 엔티티로 해석되지 않는다.
     */
    public static final Map<String, String> TEXT_COLORS = Map.of(
            "req", "#0f766e",
            "fn", "#7c3aed");
    /** 다크 테마 글자 색 (어두운 노드 바탕 위에서 읽히게 밝은 색) */
    public static final Map<String, String> TEXT_COLORS_DARK = Map.of(
            "req", "#5eead4",
            "fn", "#c4b5fd");

    /** 색 테마: dark | light (설정 output.color-theme) */
    private String theme = "light";

    public MermaidGraph theme(String t) {
        this.theme = checkTheme(t);
        return this;
    }

    private static final char STYLE_START = '\u0001';
    private static final char STYLE_END = '\u0002';

    /** 글자 색을 입힌 라벨 줄 (TEXT_COLORS 의 키) */
    public static String styled(String textClass, String text) {
        if (!TEXT_COLORS.containsKey(textClass)) {
            throw new IllegalArgumentException("알 수 없는 글자 색: " + textClass);
        }
        return STYLE_START + textClass + STYLE_END + text;
    }

    /** 색 표시를 뗀 줄 */
    public static String plain(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == STYLE_START) {
            return line.substring(line.indexOf(STYLE_END) + 1);
        }
        return line;
    }

    private static String textClass(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == STYLE_START) {
            return line.substring(1, line.indexOf(STYLE_END));
        }
        return null;
    }

    public record Edge(String from, String to, String label, EdgeStyle style) {
    }

    public static final class Subgraph {
        final String id;
        final String label;
        final List<Object> items = new ArrayList<>();

        Subgraph(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }
    }

    private final Subgraph root = new Subgraph(null, null);
    private final Map<String, Object> ids = new LinkedHashMap<>();
    /** 다른 노드에 합쳐진 모델 ID → 대표 노드 ID (렌더링하지 않음, has() 만 참) */
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    /** 렌더링 시 flowchart 줄 바로 뒤에 넣는 %% 주석 */
    private final List<String> comments = new ArrayList<>();

    public MermaidGraph comment(String text) {
        comments.add(text.replace('\n', ' '));
        return this;
    }

    /** 노드에 합쳐진 모델 ID 들 (노드 ID 자신 포함) */
    public List<String> modelIdsOf(String nodeId) {
        List<String> out = new ArrayList<>(List.of(nodeId));
        aliases.forEach((k, v) -> {
            if (v.equals(nodeId)) {
                out.add(k);
            }
        });
        return out;
    }

    public Subgraph root() {
        return root;
    }

    public Subgraph subgraph(Subgraph parent, String id, String label) {
        Subgraph s = new Subgraph(id, label);
        claim(id, s);
        parent.items.add(s);
        return s;
    }

    /** class 이름으로 쓰면 파서가 깨지는 Mermaid 예약어 */
    private static final Set<String> RESERVED_CLASS = Set.of("call", "click", "class", "classDef", "style", "end",
            "subgraph", "graph", "flowchart", "default", "href", "callback", "linkStyle", "interpolate", "direction");

    public Node node(Subgraph parent, String id, List<String> lines, Shape shape, String css) {
        if (css != null && RESERVED_CLASS.contains(css)) {
            errors.add("Mermaid 예약어 class: " + css);
        }
        if (lines.isEmpty() || lines.stream().allMatch(l -> l == null || l.isBlank())) {
            errors.add("빈 라벨 노드: " + id); // Mermaid 는 [""] 를 파싱하지 못한다
        }
        Node n = new Node(id, lines, shape, css);
        claim(id, n);
        parent.items.add(n);
        return n;
    }

    /** 노드 클릭 링크 (SVG 를 브라우저로 열면 그 파일로 이동): id → [href, 툴팁] */
    private final Map<String, String[]> links = new LinkedHashMap<>();

    public void link(String id, String href, String tooltip) {
        if (!(ids.get(id) instanceof Node)) {
            errors.add("링크 대상 노드 없음: " + id);
            return;
        }
        if (href.contains("\"") || tooltip.contains("\"")) {
            errors.add("링크에 따옴표: " + href);
            return;
        }
        links.put(id, new String[]{href, tooltip});
    }

    public boolean has(String id) {
        return ids.containsKey(id) || aliases.containsKey(id);
    }

    /** 모델 요소 id 를 이미 있는 노드 nodeId 에 합친다 (요약 노드) */
    public void alias(String id, String nodeId) {
        if (ids.containsKey(id) || aliases.putIfAbsent(id, nodeId) != null) {
            errors.add("Mermaid ID 충돌(alias): " + id);
        }
    }

    /** 합쳐진 경우 대표 노드 ID, 아니면 그대로 */
    public String nodeOf(String id) {
        return aliases.getOrDefault(id, id);
    }

    public void edge(String from, String to, String label, EdgeStyle style) {
        if (from == null || to == null) {
            return;
        }
        edges.add(new Edge(from, to, label, style));
    }

    public List<Edge> edges() {
        return edges;
    }

    public Set<String> nodeIds() {
        Set<String> s = new LinkedHashSet<>();
        ids.forEach((k, v) -> {
            if (v instanceof Node) {
                s.add(k);
            }
        });
        return s;
    }

    private void claim(String id, Object o) {
        if (!id.matches("[A-Za-z][A-Za-z0-9_]*")) {
            errors.add("Mermaid ID 형식 오류: " + id);
        }
        if (id.equalsIgnoreCase("end") || id.equalsIgnoreCase("subgraph") || id.equalsIgnoreCase("graph")) {
            errors.add("Mermaid 예약어 ID: " + id);
        }
        if (ids.putIfAbsent(id, o) != null) {
            errors.add("Mermaid ID 충돌: " + id);
        }
    }

    public List<String> validate() {
        List<String> e = new ArrayList<>(errors);
        for (Edge ed : edges) {
            if (!ids.containsKey(ed.from())) {
                e.add("엣지 시작점 없음: " + ed.from() + " -> " + ed.to());
            }
            if (!ids.containsKey(ed.to())) {
                e.add("엣지 끝점 없음: " + ed.from() + " -> " + ed.to());
            }
        }
        return e;
    }

    public String render(String direction, Map<String, String> classDefs) {
        return render(direction, "dagre", classDefs);
    }

    public String render(String direction, String layout, Map<String, String> classDefs) {
        // 여러 줄 라벨(요청 파라미터·VO 값 설정)이 잘리지 않도록 노드 여백·줄바꿈 폭을 넉넉히
        StringBuilder sb = new StringBuilder(init(layout, theme)).append('\n').append("flowchart ").append(direction).append('\n');
        comments.forEach(c -> sb.append("    %% ").append(c).append('\n'));
        for (Object o : root.items) {
            renderItem(sb, o, "    ");
        }
        for (Edge e : edges) {
            sb.append("    ").append(e.from()).append(' ').append(e.style().arrow);
            if (e.label() != null && !e.label().isBlank()) {
                sb.append("|\"").append(wrapped(e.label())).append("\"|");
            }
            sb.append(' ').append(e.to()).append('\n');
        }
        classDefs.forEach((k, v) -> sb.append("    classDef ").append(k).append(' ').append(v).append('\n'));
        Map<String, List<String>> byClass = new LinkedHashMap<>();
        ids.forEach((k, v) -> {
            if (v instanceof Node n && n.cssClass() != null) {
                byClass.computeIfAbsent(n.cssClass(), x -> new ArrayList<>()).add(k);
            }
        });
        byClass.forEach((css, list) -> sb.append("    class ").append(String.join(",", list)).append(' ').append(css).append('\n'));
        links.forEach((id, l) -> sb.append("    click ").append(id).append(" href \"").append(l[0]).append("\" \"").append(l[1]).append("\"\n"));
        return sb.toString();
    }

    /**
     * 요청 값 표기(request.이름 ?: 기본값, request[식], request.(전체))만 글자색 — 줄 전체를 칠하면
     * 같은 문장의 다른 코드(Integer.parseInt(…), + 연산 …)도 요청 항목처럼 보인다 (사용자 지적).
     * 기본값은 문자열 리터럴 또는 식(이름·호출 체인, 괄호 짝 맞춤) — 정규식으로 이름까지만 잡으면
     * {@code request.scVersion ?: VasUtil.getExceptionScVersion(appId)} 가 "(appId)" 앞에서 색이 끊겼다 (사용자 지적).
     */
    public static List<int[]> requestRanges(String s) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = skipLiteral(s, i);
                continue;
            }
            if (s.startsWith("request", i) && (i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1)))) {
                int end = requestValueEnd(s, i + "request".length());
                if (end > 0) {
                    out.add(new int[]{i, end});
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    /** request 뒤 표기의 끝 (아니면 -1): [식] / .(…) / .이름 (호출이 아니어야) + 선택 " ?: 기본값" */
    private static int requestValueEnd(String s, int j) {
        int end;
        if (j < s.length() && s.charAt(j) == '[') {
            end = closing(s, j);
        } else if (j + 1 < s.length() && s.charAt(j) == '.' && s.charAt(j + 1) == '(') {
            end = closing(s, j + 1);
        } else if (j + 1 < s.length() && s.charAt(j) == '.' && nameChar(s.charAt(j + 1))) {
            end = j + 1;
            while (end < s.length() && nameChar(s.charAt(end))) {
                end++;
            }
            if (end < s.length() && s.charAt(end) == '(') {
                return -1; // request.getRequestURI() — 요청 객체의 다른 메서드 호출
            }
        } else {
            return -1;
        }
        if (end < 0) {
            return -1;
        }
        String sep = " ?: ";
        if (s.startsWith(sep, end) && end + sep.length() < s.length()) {
            int d = end + sep.length();
            char c = s.charAt(d);
            if (c == '"' || c == '\'') {
                return skipLiteral(s, d);
            }
            int e = d;
            while (e < s.length()) {   // 이름·호출 체인: VasUtil.getX(appId).trim(), Consts.DEF, -1, arr[0]
                if (nameChar(s.charAt(e))) {
                    e++;
                } else if (s.charAt(e) == '(' || s.charAt(e) == '[') {
                    int close = closing(s, e);
                    if (close < 0) {
                        break;
                    }
                    e = close;
                } else {
                    break;
                }
            }
            if (e > d) {
                return e;
            }
        }
        return end;
    }

    private static boolean nameChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.' || c == '-';
    }

    /** open 의 짝 괄호 다음 위치 (리터럴·중첩 고려, 없으면 -1) */
    private static int closing(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i);
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }

    /** 문자열·문자 리터럴 다음 위치 (닫히지 않으면 끝) */
    private static int skipLiteral(String s, int at) {
        char q = s.charAt(at);
        for (int i = at + 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
            } else if (s.charAt(i) == q) {
                return i + 1;
            }
        }
        return s.length();
    }

    /** 글자색 시작 태그 (TEXT_COLORS 주석 참고) */
    public static String fontOpen(String color) {
        return "<font color='" + color + "'>";
    }

    public static String requestSpans(String piece, String color) {
        return colored(piece, 0, requestRanges(piece), color);
    }

    /** text(원래 줄의 offset 위치부터)에서 ranges(원래 줄 기준)에 든 부분만 글자색, 나머지는 이스케이프만 */
    private static String colored(String text, int offset, List<int[]> ranges, String color) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int[] rg : ranges) {
            int a = Math.max(rg[0] - offset, at);
            int b = Math.min(rg[1] - offset, text.length());
            if (b <= a) {
                continue;
            }
            out.append(esc(text.substring(at, a))).append(fontOpen(color)).append(esc(text.substring(a, b))).append("</font>");
            at = b;
        }
        return out.append(esc(text.substring(at))).toString();
    }

    /**
     * 요청 값 줄을 끊은 조각들 — 색 범위는 끊기 전 줄 전체에서 계산해, 요청 값 표기가 두 줄에 걸쳐도
     * 이어지는 줄까지 칠한다.
     */
    private static List<String> requestPieces(String line, List<String> pieces, String color) {
        List<int[]> ranges = requestRanges(line);
        List<String> out = new ArrayList<>();
        int cursor = 0;
        for (int k = 0; k < pieces.size(); k++) {
            String piece = pieces.get(k);
            String prefix = k > 0 && piece.startsWith("> ") ? "> " : "";
            String content = piece.substring(prefix.length());
            int start = line.indexOf(content, cursor);
            if (start < 0) {
                out.add(requestSpans(piece, color));
                continue;
            }
            out.add(esc(prefix) + colored(content, start, ranges, color));
            cursor = start + content.length();
        }
        return out;
    }

    private void renderItem(StringBuilder sb, Object o, String indent) {
        if (o instanceof Subgraph s) {
            sb.append(indent).append("subgraph ").append(s.id).append("[\"").append(wrapped(s.label)).append("\"]\n");
            for (Object c : s.items) {
                renderItem(sb, c, indent + "    ");
            }
            sb.append(indent).append("end\n");
        } else {
            Node n = (Node) o;
            List<String> parts = new ArrayList<>();
            for (String l : n.lines()) {
                String tc = textClass(l);
                String color = tc == null ? null : ("dark".equals(theme) ? TEXT_COLORS_DARK : TEXT_COLORS).get(tc);
                List<String> pieces = wrap(plain(l), maxLineLength);
                if ("req".equals(tc)) {
                    parts.addAll(requestPieces(plain(l), pieces, color));
                    continue;
                }
                for (String piece : pieces) {
                    parts.add(tc == null ? esc(piece) : fontOpen(color) + esc(piece) + "</font>");
                }
            }
            String label = String.join("<br/>", parts);
            String[] br = switch (n.shape()) {
                case RECT -> new String[]{"[\"", "\"]"};
                case ROUND -> new String[]{"(\"", "\")"};
                case STADIUM -> new String[]{"([\"", "\"])"};
                case DIAMOND -> new String[]{"{\"", "\"}"};
                case HEX -> new String[]{"{{\"", "\"}}"};
                case SUBROUTINE -> new String[]{"[[\"", "\"]]"};
                case ASYM -> new String[]{">\"", "\"]"};
            };
            sb.append(indent).append(n.id()).append(br[0]).append(label).append(br[1]).append('\n');
        }
    }

    /** 한 줄 라벨(subgraph 제목·간선 라벨)도 노드 줄과 같은 한도로 끊는다 */
    private String wrapped(String label) {
        return String.join("<br/>", wrap(label == null ? "" : label, maxLineLength).stream().map(MermaidGraph::esc).toList());
    }

    /** subgraph 안의 모든 ID (자신·중첩 subgraph·노드) */
    public Set<String> idsUnder(String subgraphId) {
        Set<String> out = new LinkedHashSet<>();
        if (ids.get(subgraphId) instanceof Subgraph sg) {
            collectIds(sg, out);
        }
        return out;
    }

    private static void collectIds(Subgraph sg, Set<String> out) {
        out.add(sg.id);
        for (Object o : sg.items) {
            if (o instanceof Subgraph c) {
                collectIds(c, out);
            } else {
                out.add(((Node) o).id());
            }
        }
    }

    /** 노드의 첫 줄(라벨) 또는 subgraph 라벨 */
    public String labelOf(String id) {
        Object o = ids.get(id);
        if (o instanceof Node n) {
            return n.lines().isEmpty() ? id : plain(n.lines().get(0));
        }
        return o instanceof Subgraph s ? s.label : id;
    }

    /**
     * subgraph 하나만 떼어 낸 그래프 (파일 분할용). 안쪽끼리의 간선은 그대로, 바깥으로 나가는 간선은
     * 끝에 안내 노드(go_대상ID, 라벨 = stubLabel(대상ID))를 만들어 잇는다. 바깥에서 들어오는 간선은 뺀다.
     */
    public MermaidGraph extract(String subgraphId, java.util.function.Function<String, List<String>> stubLabel) {
        MermaidGraph out = new MermaidGraph();
        out.maxLineLength = this.maxLineLength;
        out.comments.addAll(comments);
        Subgraph sg = (Subgraph) ids.get(subgraphId);
        out.root.items.add(sg);
        Set<String> inside = idsUnder(subgraphId);
        for (String id : inside) {
            out.ids.put(id, ids.get(id));
        }
        for (Edge e : edges) {
            if (!inside.contains(e.from())) {
                continue;
            }
            if (inside.contains(e.to())) {
                out.edges.add(e);
            } else {
                String stub = "go_" + e.to();
                if (!out.has(stub)) {
                    out.node(out.root, stub, stubLabel.apply(e.to()), Shape.SUBROUTINE, "golink");
                }
                out.edges.add(new Edge(e.from(), stub, e.label(), e.style()));
            }
        }
        return out;
    }

    /** id 의 노드 (subgraph·없는 ID 면 null) */
    public Node nodeById(String id) {
        return ids.get(id) instanceof Node n ? n : null;
    }

    /** subgraph 바로 아래 항목 ID (노드·subgraph, 그린 순서) */
    public List<String> childrenOf(String subgraphId) {
        List<String> out = new ArrayList<>();
        if (ids.get(subgraphId) instanceof Subgraph sg) {
            for (Object o : sg.items) {
                out.add(o instanceof Subgraph c ? c.id : ((Node) o).id());
            }
        }
        return out;
    }

    public boolean isSubgraph(String id) {
        return ids.get(id) instanceof Subgraph;
    }

    /**
     * 노드 일부만 남긴 그래프 (긴 메서드 구간 분할). 남긴 노드를 품은 subgraph 는 그 노드들만 담아 유지한다.
     * 안쪽끼리 간선은 그대로, 나가는 간선은 go_대상 안내 노드(outLabel), 들어오는 간선은 inLabel 이 null 이 아니면
     * from_출발 안내 노드에서 잇는다(inLabel 이 null 을 돌려주면 그 간선은 뺀다).
     */
    public MermaidGraph extractNodes(Set<String> keep, java.util.function.Function<String, List<String>> outLabel,
                                     java.util.function.Function<String, List<String>> inLabel) {
        MermaidGraph out = new MermaidGraph();
        out.maxLineLength = this.maxLineLength;
        out.comments.addAll(comments);
        copyInto(root, out.root, keep, out);
        for (Edge e : edges) {
            boolean from = out.ids.containsKey(e.from());
            boolean to = out.ids.containsKey(e.to());
            if (from && to) {
                out.edges.add(e);
            } else if (from) {
                String stub = "go_" + e.to();
                if (!out.has(stub)) {
                    out.node(out.root, stub, outLabel.apply(e.to()), Shape.SUBROUTINE, "golink");
                }
                out.edges.add(new Edge(e.from(), stub, e.label(), e.style()));
            } else if (to) {
                List<String> label = inLabel.apply(e.from());
                if (label == null) {
                    continue;
                }
                String stub = "from_" + e.from();
                if (!out.has(stub)) {
                    out.node(out.root, stub, label, Shape.SUBROUTINE, "golink");
                }
                out.edges.add(new Edge(stub, e.to(), e.label(), e.style()));
            }
        }
        return out;
    }

    private static boolean copyInto(Subgraph src, Subgraph dst, Set<String> keep, MermaidGraph out) {
        for (Object o : src.items) {
            if (o instanceof Subgraph s) {
                Subgraph c = new Subgraph(s.id, s.label);
                if (copyInto(s, c, keep, out)) {
                    dst.items.add(c);
                    out.ids.put(s.id, c);
                }
            } else if (keep.contains(((Node) o).id())) {
                dst.items.add(o);
                out.ids.put(((Node) o).id(), o);
            }
        }
        return !dst.items.isEmpty();
    }

    /**
     * 골격 보기: keep 이 아닌 노드가 정상 흐름(FLOW) 한 줄로 이어진 사슬(같은 subgraph, 앞 노드의 나가는 FLOW 1개·
     * 뒤 노드의 들어오는 FLOW 1개)을 노드 하나(사슬 첫 노드 ID, 라벨 = label(사슬))로 접는다. 접은 노드의 간선은
     * 대표 노드로 옮기고 사슬 안 간선은 뺀다. 줄 수가 minLines 이하인 노드 하나짜리 사슬은 그대로 둔다.
     */
    public MermaidGraph collapse(java.util.function.Predicate<Node> keep,
                                 java.util.function.Function<List<Node>, List<String>> label, String css, int minLines) {
        Map<String, Subgraph> parent = new LinkedHashMap<>();
        parents(root, parent);
        Map<String, Integer> flowOut = new java.util.HashMap<>();
        Map<String, Integer> flowIn = new java.util.HashMap<>();
        Map<String, String> flowNext = new java.util.HashMap<>();
        for (Edge e : edges) {
            if (e.style() == EdgeStyle.FLOW) {
                flowOut.merge(e.from(), 1, Integer::sum);
                flowIn.merge(e.to(), 1, Integer::sum);
                flowNext.put(e.from(), e.to());
            }
        }
        java.util.function.Predicate<String> foldable = id -> ids.get(id) instanceof Node n && !keep.test(n);
        Map<String, String> next = new java.util.HashMap<>();
        Set<String> hasPrev = new java.util.HashSet<>();
        for (String id : ids.keySet()) {
            if (!foldable.test(id) || flowOut.getOrDefault(id, 0) != 1) {
                continue;
            }
            String to = flowNext.get(id);
            if (foldable.test(to) && flowIn.getOrDefault(to, 0) == 1 && parent.get(id) == parent.get(to)) {
                next.put(id, to);
                hasPrev.add(to);
            }
        }
        Map<String, String> repOf = new java.util.HashMap<>();
        Map<String, List<Node>> chains = new LinkedHashMap<>();
        for (String id : ids.keySet()) {
            if (!foldable.test(id) || hasPrev.contains(id)) {
                continue;
            }
            List<Node> chain = new ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            for (String c = id; c != null && seen.add(c); c = next.get(c)) {
                chain.add((Node) ids.get(c));
                repOf.put(c, id);
            }
            int lines = chain.stream().mapToInt(n -> n.lines().size()).sum();
            if (chain.size() > 1 || lines > minLines) {
                chains.put(id, chain);
            }
        }
        MermaidGraph out = new MermaidGraph();
        out.maxLineLength = this.maxLineLength;
        out.comments.addAll(comments);
        collapseInto(root, out.root, chains, repOf, label, css, out);
        Set<String> seenEdges = new java.util.HashSet<>();
        for (Edge e : edges) {
            String from = chains.containsKey(repOf.get(e.from())) ? repOf.get(e.from()) : e.from();
            String to = chains.containsKey(repOf.get(e.to())) ? repOf.get(e.to()) : e.to();
            if (from.equals(to) && chains.containsKey(from)) {
                continue; // 사슬 안 간선
            }
            if (seenEdges.add(from + "|" + to + "|" + e.label() + "|" + e.style())) {
                out.edges.add(new Edge(from, to, e.label(), e.style()));
            }
        }
        return out;
    }

    private static void parents(Subgraph sg, Map<String, Subgraph> out) {
        for (Object o : sg.items) {
            if (o instanceof Subgraph c) {
                out.put(c.id, sg);
                parents(c, out);
            } else {
                out.put(((Node) o).id(), sg);
            }
        }
    }

    private static void collapseInto(Subgraph src, Subgraph dst, Map<String, List<Node>> chains, Map<String, String> repOf,
                                     java.util.function.Function<List<Node>, List<String>> label, String css, MermaidGraph out) {
        for (Object o : src.items) {
            if (o instanceof Subgraph s) {
                Subgraph c = new Subgraph(s.id, s.label);
                collapseInto(s, c, chains, repOf, label, css, out);
                if (!c.items.isEmpty()) {
                    dst.items.add(c);
                    out.ids.put(s.id, c);
                }
                continue;
            }
            Node n = (Node) o;
            String rep = repOf.get(n.id());
            if (rep != null && chains.containsKey(rep)) {
                if (rep.equals(n.id())) {
                    Node folded = new Node(n.id(), label.apply(chains.get(rep)), Shape.RECT, css);
                    dst.items.add(folded);
                    out.ids.put(n.id(), folded);
                }
                continue; // 사슬의 나머지 노드는 대표 노드에 접힘
            }
            dst.items.add(n);
            out.ids.put(n.id(), n);
        }
    }

    /** 노드 한 줄 최대 길이 (넘으면 끊고 다음 줄 앞에 "> "). 0 이하면 끊지 않음 */
    private int maxLineLength = 150;

    public MermaidGraph maxLineLength(int n) {
        this.maxLineLength = n;
        return this;
    }

    /**
     * 긴 코드 줄을 끊기 좋은 자리에서 나눈다: 쉼표 뒤 > + / - 앞 > '.' 앞(메서드 체인) > '(' 뒤.
     * 이어지는 줄은 "> " 로 시작. 길이는 화면 폭 기준({@link #width} — 한글·한자 등 전각 문자 = 2).
     * 방어: 마땅한 자리가 없어도 한 줄이 한도를 크게 넘지 않게 — 공백(문자열 리터럴 밖 → 안) → 강제 자르기 순으로
     * 끊는다 (긴 문자열 리터럴·식별자 한 덩어리가 노드 폭을 넘어 라벨이 잘리거나 겹쳐 보이던 문제).
     */
    public static List<String> wrap(String line, int max) {
        List<String> out = new ArrayList<>();
        if (max <= 0 || line == null || width(line) <= max) {
            out.add(line);
            return out;
        }
        int limit = Math.max(max, MIN_WRAP);
        String rest = line;
        boolean first = true;
        while (true) {
            String prefix = first ? "" : "> ";
            int room = limit - prefix.length();
            if (width(rest) <= room) {
                out.add(prefix + rest);
                break;
            }
            int cut = breakAt(rest, room);
            String head = rest.substring(0, cut).stripTrailing();
            if (head.isEmpty()) {           // 공백만 앞에 있던 경우 — 진행 보장
                head = rest.substring(0, cut);
            }
            out.add(prefix + head);
            rest = rest.substring(cut).stripLeading();
            if (rest.isEmpty()) {
                break;
            }
            first = false;
        }
        return out;
    }

    /** 이 폭보다 좁게는 끊지 않는다 ("> " 와 몇 글자만 남는 줄 방지) */
    private static final int MIN_WRAP = 20;

    /** 화면 폭: 전각(한글·CJK·전각 기호·이모지) 2, 나머지 1 */
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += charWidth(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    private static int charWidth(int cp) {
        if (cp < 0x1100) {
            return 1;
        }
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        if (sc == Character.UnicodeScript.HANGUL || sc == Character.UnicodeScript.HAN
                || sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA) {
            return 2;
        }
        return (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF01 && cp <= 0xFF60) || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF) ? 2 : 1;
    }

    /**
     * room(화면 폭) 안의 끊을 자리(문자 인덱스, 항상 0 보다 크고 s.length() 보다 작음).
     * 1) 강한 자리(쉼표 뒤·+/- 앞·&amp;&amp;/|| 앞·).체인 앞) 2) 약한 자리('(' 뒤) — 둘 다 room/3 넘는 위치일 때,
     * 3) 한도를 조금(1/3) 넘는 첫 강한·약한 자리, 4) 리터럴 밖 공백, 5) 앞쪽이라도 리터럴 밖 자리, 6) 리터럴 안 공백,
     * 7) 강제 자르기(한도 위치, 서로게이트 쌍은 나누지 않음).
     */
    private static int breakAt(String s, int room) {
        int strong = -1;
        int weak = -1;
        int after = -1;
        int spaceOut = -1;
        int spaceIn = -1;
        int hard = -1;
        int w = 0;
        int afterLimit = room + room / 3;
        char quote = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char ch = s.charAt(i);
            w += Character.isLowSurrogate(ch) ? 0 : charWidth(s.codePointAt(i));
            if (w > room && hard < 0) {
                hard = Character.isLowSurrogate(ch) ? i - 1 : i; // 이 글자부터 다음 줄
            }
            int widthBefore = w - (Character.isLowSurrogate(ch) ? 0 : charWidth(s.codePointAt(i)));
            if (quote != 0) {
                if (ch == ' ' && widthBefore + 1 <= room && i > 0) {
                    spaceIn = i + 1;
                }
                if (ch == '\\') {
                    i++;
                    w += i < s.length() ? charWidth(s.charAt(i)) : 0;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (i == 0) {
                continue;
            }
            if (ch == ' ' && w <= room) {
                spaceOut = i + 1;
            }
            int cut = -1;
            boolean isStrong = true;
            if (ch == ',' && s.charAt(i + 1) == ' ') {
                cut = i + 1;                                   // 쉼표 뒤
            } else if ((ch == '+' || ch == '-') && s.charAt(i - 1) == ' ' && s.charAt(i + 1) == ' ') {
                cut = i;                                       // 연산자 앞
            } else if ((ch == '&' && s.charAt(i + 1) == '&' || ch == '|' && s.charAt(i + 1) == '|') && s.charAt(i - 1) == ' ') {
                cut = i;                                       // && / || 앞
            } else if (ch == '.' && s.charAt(i - 1) == ')') {
                cut = i;                                       // ).method() 체인 앞
            } else if (ch == '(' && s.charAt(i + 1) != ')') {
                cut = i + 1;                                   // 여는 괄호 뒤 (약한 자리)
                isStrong = false;
            }
            if (cut <= 0) {
                continue;
            }
            int cutWidth = cut == i ? widthBefore : w;
            if (cutWidth <= room) {
                if (isStrong) {
                    strong = cut;
                } else {
                    weak = cut;
                }
            } else if (after < 0 && cutWidth <= afterLimit) {
                after = cut;
            }
        }
        int third = room / 3;
        for (int c : new int[]{strong, weak}) {
            if (c > 0 && width(s.substring(0, c)) > third) {
                return c;
            }
        }
        if (after > 0) {
            return after;
        }
        if (spaceOut > 0 && width(s.substring(0, spaceOut)) > third) {
            return spaceOut;
        }
        int outside = Math.max(Math.max(strong, weak), spaceOut);   // 앞쪽이라도 리터럴 밖 자리가 리터럴 안보다 낫다
        if (outside > 0) {
            return outside;
        }
        if (spaceIn > 0) {
            return spaceIn;
        }
        return hard > 0 ? hard : Math.max(1, s.length() - 1);
    }

    /** 라벨 특수문자를 Mermaid 엔티티로 치환 (# 먼저) */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("#", "#35;")
                .replace("\"", "#quot;")
                .replace("<", "#lt;")
                .replace(">", "#gt;")
                .replace("|", "#124;")
                .replace("`", "#96;")
                .replace("\n", " ");
    }
}
