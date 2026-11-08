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
    public static final String INIT = init("dagre");

    /**
     * 렌더링 설정 줄. layout "dagre" = Mermaid 기본, "elk" = 직각 간선 레이아웃(뷰어가 ELK 를 지원해야 함).
     */
    public static String init(String layout) {
        return "%%{init: {\"flowchart\": {\"padding\": 20, \"nodeSpacing\": 40, \"rankSpacing\": 50, \"wrappingWidth\": 600}"
                + ("elk".equals(layout) ? ", \"layout\": \"elk\"" : "") + "}}%%";
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

    public Subgraph root() {
        return root;
    }

    public Subgraph subgraph(Subgraph parent, String id, String label) {
        Subgraph s = new Subgraph(id, label);
        claim(id, s);
        parent.items.add(s);
        return s;
    }

    public Node node(Subgraph parent, String id, List<String> lines, Shape shape, String css) {
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
            String label = String.join("<br/>", n.lines().stream().map(MermaidGraph::esc).toList());
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
