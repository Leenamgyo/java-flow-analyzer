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
        return "%%{init: {\"flowchart\": {\"padding\": 20, \"nodeSpacing\": 40, \"rankSpacing\": 50, \"wrappingWidth\": 2400}"
                + ("elk".equals(layout) ? ", \"layout\": \"elk\"" : "")
                + ", \"themeCSS\": \"" + LEFT_ALIGN_CSS + "\"}}%%";
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
     * 색은 rgb() 로 쓴다 — "#rrggbb;" 는 Mermaid 엔티티(#…;)로 해석된다.
     */
    public static final Map<String, String> TEXT_COLORS = Map.of(
            "req", "rgb(15,118,110)",
            "fn", "rgb(124,58,237)");

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
        StringBuilder sb = new StringBuilder(init(layout)).append('\n').append("flowchart ").append(direction).append('\n');
        comments.forEach(c -> sb.append("    %% ").append(c).append('\n'));
        for (Object o : root.items) {
            renderItem(sb, o, "    ");
        }
        for (Edge e : edges) {
            sb.append("    ").append(e.from()).append(' ').append(e.style().arrow);
            if (e.label() != null && !e.label().isBlank()) {
                sb.append("|\"").append(esc(e.label())).append("\"|");
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
        return sb.toString();
    }

    private void renderItem(StringBuilder sb, Object o, String indent) {
        if (o instanceof Subgraph s) {
            sb.append(indent).append("subgraph ").append(s.id).append("[\"").append(esc(s.label)).append("\"]\n");
            for (Object c : s.items) {
                renderItem(sb, c, indent + "    ");
            }
            sb.append(indent).append("end\n");
        } else {
            Node n = (Node) o;
            List<String> parts = new ArrayList<>();
            for (String l : n.lines()) {
                String tc = textClass(l);
                for (String piece : wrap(plain(l), maxLineLength)) {
                    parts.add(tc == null ? esc(piece)
                            : "<span style='color:" + TEXT_COLORS.get(tc) + "'>" + esc(piece) + "</span>");
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

    /** 노드 한 줄 최대 길이 (넘으면 끊고 다음 줄 앞에 "> "). 0 이하면 끊지 않음 */
    private int maxLineLength = 100;

    public MermaidGraph maxLineLength(int n) {
        this.maxLineLength = n;
        return this;
    }

    /**
     * 긴 코드 줄을 끊기 좋은 자리에서 나눈다: 쉼표 뒤 > + / - 앞 > '.' 앞(메서드 체인) > '(' 뒤.
     * 이어지는 줄은 "> " 로 시작. 끊을 자리가 없으면 그대로 둔다 (단어 중간을 자르지 않음).
     */
    public static List<String> wrap(String line, int max) {
        List<String> out = new ArrayList<>();
        if (max <= 0 || line == null || line.length() <= max) {
            out.add(line);
            return out;
        }
        String rest = line;
        boolean first = true;
        while (true) {
            String prefix = first ? "" : "> ";
            int room = max - prefix.length();
            if (rest.length() <= room) {
                out.add(prefix + rest);
                break;
            }
            int cut = breakAt(rest, room);
            if (cut <= 0 || cut >= rest.length()) {
                out.add(prefix + rest);
                break;
            }
            out.add(prefix + rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
            first = false;
        }
        return out;
    }

    /**
     * room 안의 끊을 자리. 강한 자리(쉼표 뒤·+/- 앞·).체인 앞)를 우선, 없으면 약한 자리('(' 뒤),
     * 한도 안에 마땅한 자리가 없으면 한도를 넘는 첫 자리, 그것도 없으면 -1. 문자열 리터럴 안은 제외.
     */
    private static int breakAt(String s, int room) {
        int strong = -1;
        int weak = -1;
        int after = -1;
        char quote = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == '\\') {
                    i++;
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
            if (cut <= room) {
                if (isStrong) {
                    strong = cut;
                } else {
                    weak = cut;
                }
            } else if (after < 0) {
                after = cut;
            }
        }
        if (strong > room / 3) {
            return strong;
        }
        if (weak > room / 3) {
            return weak;
        }
        if (after > 0) {
            return after;
        }
        return Math.max(strong, weak);
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
