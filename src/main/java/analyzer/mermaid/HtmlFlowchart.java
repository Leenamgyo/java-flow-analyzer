package analyzer.mermaid;

import analyzer.mermaid.MermaidGraph.Edge;
import analyzer.mermaid.MermaidGraph.EdgeStyle;
import analyzer.mermaid.MermaidGraph.Node;
import analyzer.mermaid.MermaidGraph.Subgraph;
import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.ClassRole;
import analyzer.model.ObjectInfo;
import analyzer.model.VariableInfo;
import analyzer.serializer.Labels;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.SourceLocation;
import analyzer.model.VariableChange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 흐름도 HTML 뷰어 (output.flowchart-html) — 무한 캔버스(휠 확대·끌어 이동), 메서드·블록 접기/펴기, 검색, 노드 상세 패널,
 * 세부 호출 → 그 메서드로 이동. Mermaid 와 같은 중간 표현(검증을 마친 {@link MermaidGraph})을 JSON 으로 내보내고,
 * 배치는 브라우저에서 ELK(elkjs, jar 리소스에 내장 — 설치·인터넷 불필요)로 계산해 SVG 텍스트(&lt;text&gt;)로 그린다.
 * Mermaid SVG 의 foreignObject 글자 폭·엔티티 문제가 없다. 노드·줄·색 규칙은 Mermaid 흐름도와 같다(모델에서만 생성).
 */
public final class HtmlFlowchart {

    private static final String RES = "/flowchart-html/";

    private HtmlFlowchart() {
    }

    /**
     * @param g         검증을 마친 그래프 ({@link FlowchartGenerator#checked})
     * @param classDefs 노드 class 색 (Mermaid classDef 문법 그대로: fill·stroke·stroke-width·stroke-dasharray·color)
     * @param direction TD | LR …
     */
    public static String render(AnalysisResult r, MermaidGraph g, Map<String, String> classDefs, String direction, String title) {
        return render(r, g, classDefs, direction, title, 0);
    }

    /** hideNodes: 노드 수가 이 개수 이하인 세부 메서드 상자를 뷰어에서 생략 (0 = 생략 없음, output.flowchart-html-hide-nodes) */
    public static String render(AnalysisResult r, MermaidGraph g, Map<String, String> classDefs, String direction, String title,
                                int hideNodes) {
        return render(r, g, classDefs, direction, title, hideNodes, FlowchartGenerator.DEFAULT_LOGIC_ROLES);
    }

    /** logicRoles: VO 입력을 붙일 호출 대상 역할 (Service·Biz·DAO·Mapper·Repository — phases.logic-roles) */
    public static String render(AnalysisResult r, MermaidGraph g, Map<String, String> classDefs, String direction, String title,
                                int hideNodes, Set<ClassRole> logicRoles) {
        Map<String, Object> data = data(r, g, classDefs, direction, title, logicRoles);
        data.put("hideNodes", Math.max(0, hideNodes));
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        // <script> 안에 넣으므로 '<' 를 이스케이프 (코드 줄에 </script>·<!-- 가 있어도 안전)
        json = json.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        String page = resource("viewer.html");
        return page.replace("__TITLE__", htmlEscape(title))
                .replace("/*__ELK__*/", resource("elk.bundled.js"))
                .replace("/*__VIEWER__*/", resource("viewer.js"))
                .replace("\"__DATA__\"", json);
    }

    static Map<String, Object> data(AnalysisResult r, MermaidGraph g, Map<String, String> classDefs, String direction, String title) {
        return data(r, g, classDefs, direction, title, FlowchartGenerator.DEFAULT_LOGIC_ROLES);
    }

    static Map<String, Object> data(AnalysisResult r, MermaidGraph g, Map<String, String> classDefs, String direction, String title,
                                    Set<ClassRole> logicRoles) {
        Set<String> detail = g.detailNodes();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", title);
        d.put("theme", g.theme());
        d.put("direction", direction);
        d.put("callEdges", g.callEdges());
        d.put("comments", g.comments());
        Map<String, String> colors = "dark".equals(g.theme()) ? MermaidGraph.TEXT_COLORS_DARK : MermaidGraph.TEXT_COLORS;
        d.put("textColors", colors);
        d.put("loopEdge", style("dark".equals(g.theme()) ? MermaidGraph.LOOP_EDGE_DARK : MermaidGraph.LOOP_EDGE));
        Map<String, Object> classes = new LinkedHashMap<>();
        classDefs.forEach((k, v) -> classes.put(k, style(v)));
        d.put("classes", classes);
        d.put("leftAligned", MermaidGraph.LEFT_ALIGNED);
        Map<String, String> callTarget = new LinkedHashMap<>();
        for (Edge e : g.edges()) {
            if (e.style() == EdgeStyle.CALL) {
                callTarget.putIfAbsent(e.from(), e.to());
            }
        }
        d.put("root", items(r, g, g.root().items, detail, classDefs, callTarget, logicRoles));
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Edge e : g.edges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", e.from());
            m.put("to", e.to());
            String label = e.label();
            boolean loop = label != null && label.startsWith(MermaidGraph.LOOP_MARK);
            if (loop) {
                label = label.substring(MermaidGraph.LOOP_MARK.length());
            }
            m.put("label", label == null || label.isBlank() ? List.of() : MermaidGraph.wrap(label, g.maxLineLength()));
            m.put("style", e.style().name().toLowerCase());
            m.put("loop", loop);
            edges.add(m);
        }
        d.put("edges", edges);
        return d;
    }

    private static List<Object> items(AnalysisResult r, MermaidGraph g, List<Object> items, Set<String> detail,
                                      Map<String, String> classDefs, Map<String, String> callTarget, Set<ClassRole> logicRoles) {
        List<Object> out = new ArrayList<>();
        for (Object o : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (o instanceof Subgraph s) {
                m.put("group", s.id);
                m.put("label", MermaidGraph.wrap(s.label == null ? "" : s.label, g.maxLineLength()));
                m.put("children", items(r, g, s.items, detail, classDefs, callTarget, logicRoles));
            } else {
                Node n = (Node) o;
                m.put("id", n.id());
                m.put("shape", n.shape().name());
                m.put("cls", MermaidGraph.effectiveClass(n, detail, classDefs));
                m.put("lines", lines(n.lines(), g));
                List<String> ids = g.modelIdsOf(n.id());
                m.put("ids", ids);
                List<String> locs = new ArrayList<>();
                for (String id : ids) {
                    SourceLocation l = location(r, id);
                    if (l != null && l.beginLine() > 0 && !locs.contains(l.shortText())) {
                        locs.add(l.shortText());
                    }
                }
                m.put("locs", locs);
                if (callTarget.containsKey(n.id())) {
                    m.put("goto", callTarget.get(n.id()));
                }
                List<Map<String, Object>> vo = new ArrayList<>();
                for (String id : ids) {
                    if (r.element(id).orElse(null) instanceof CallSiteInfo c) {
                        vo.addAll(voInputs(r, g, c, logicRoles));
                    }
                }
                if (!vo.isEmpty()) {
                    m.put("voIn", vo);
                }
            }
            out.add(m);
        }
        return out;
    }

    /** 노드 줄 → 화면 줄(max-line-length 로 끊은 조각)마다 [글자, 색 키] 조각들. 색 규칙은 Mermaid 렌더링과 같다 */
    static List<List<List<String>>> lines(List<String> lines, MermaidGraph g) {
        List<List<List<String>>> out = new ArrayList<>();
        for (String l : lines) {
            String tc = MermaidGraph.textClass(l);
            String plain = MermaidGraph.plain(l);
            List<String> pieces = MermaidGraph.wrap(plain, g.maxLineLength());
            if (!"req".equals(tc)) {
                for (String p : pieces) {
                    out.add(List.of(List.of(p, tc == null ? "" : tc)));
                }
                continue;
            }
            // 요청 값 표기만 칠한다 — 범위는 끊기 전 줄에서 계산 (MermaidGraph.requestPieces 와 같은 규칙)
            List<int[]> ranges = MermaidGraph.requestRanges(plain);
            int cursor = 0;
            for (int k = 0; k < pieces.size(); k++) {
                String piece = pieces.get(k);
                String prefix = k > 0 && piece.startsWith("> ") ? "> " : "";
                String content = piece.substring(prefix.length());
                int start = plain.indexOf(content, cursor);
                List<List<String>> segs = new ArrayList<>();
                if (!prefix.isEmpty()) {
                    segs.add(List.of(prefix, ""));
                }
                if (start < 0) {
                    segs.add(List.of(content, ""));
                    out.add(segs);
                    continue;
                }
                int at = 0;
                for (int[] rg : ranges) {
                    int a = Math.max(rg[0] - start, at);
                    int b = Math.min(rg[1] - start, content.length());
                    if (b <= a) {
                        continue;
                    }
                    if (a > at) {
                        segs.add(List.of(content.substring(at, a), ""));
                    }
                    segs.add(List.of(content.substring(a, b), "req"));
                    at = b;
                }
                if (at < content.length()) {
                    segs.add(List.of(content.substring(at), ""));
                }
                out.add(segs);
                cursor = start + content.length();
            }
        }
        return out;
    }

    /**
     * Service·DAO(로직 역할) 호출에 넘기는 VO 와 그 VO 에 set 하는 코드 (사용자: 어떤 VO 가 들어가고 어떻게 set 하는지만 —
     * 값의 출처를 거슬러 올라가지 않는다). 인자 이름 = 호출한 메서드의 VO 객체 변수면 그 객체의 바인딩(같은 메서드면 호출 전 것만),
     * 그 변수가 다른 함수의 반환값이면(cmd = createCommand(…)) 그 함수 안에서 같은 클래스 VO 에 set 하는 코드 — 반환값을 거친
     * 객체 정체성은 모델이 추적하지 않으므로 "… 안에서" 로 밝혀 둔다.
     */
    static List<Map<String, Object>> voInputs(AnalysisResult r, MermaidGraph g, CallSiteInfo s, Set<ClassRole> logicRoles) {
        List<Map<String, Object>> out = new ArrayList<>();
        String target = s.implementationMethodId != null ? s.implementationMethodId : s.targetMethodId;
        MethodInfo tm = target == null ? null : r.method(target);
        if (tm == null || !logicRoles.contains(r.classOf(tm).role)) {
            return out;
        }
        for (String arg : s.arguments) {
            String a = arg.trim();
            if (!a.matches("[A-Za-z_$][\\w$]*")) {
                continue;
            }
            ObjectInfo o = r.objects.stream().filter(x -> s.callerMethodId.equals(x.methodId) && a.equals(x.variable)).findFirst().orElse(null);
            String via = null;
            String made0 = null; // VO 를 만든 함수 호출 (반환값으로 받은 경우)
            List<Binding> bs = new ArrayList<>();
            if (o != null) {
                for (String bid : o.bindingIds) {
                    Binding b = r.binding(bid);
                    boolean after = b.methodId.equals(s.callerMethodId) && b.location != null && s.location != null
                            && b.location.beginLine() > s.location.beginLine();
                    if (!after) {
                        bs.add(b);
                    }
                }
            } else {
                // 반환값으로 받은 VO: 그 값을 만든 함수 안에서 같은 클래스 VO 에 set 하는 코드
                VariableInfo v = r.variableNamed(s.callerMethodId, a);
                if (v == null || v.type == null) {
                    continue;
                }
                String type = v.type.replaceAll("<.*", "");
                for (String cid : v.changeIds) {
                    VariableChange vc = r.variableChange(cid);
                    for (String csId : vc.callSiteIds) {
                        CallSiteInfo made = r.callSite(csId);
                        if (made == null || !made.expression.equals(vc.valueExpression) || made.expansionTargetId() == null) {
                            continue;
                        }
                        for (ObjectInfo x : r.objects) {
                            if (made.expansionTargetId().equals(x.methodId) && type.equals(x.className) && !x.bindingIds.isEmpty()) {
                                o = x;
                                via = Labels.truncate(made.expression, 80) + " 안에서";
                                made0 = made.id;
                                x.bindingIds.forEach(id -> bs.add(r.binding(id)));
                                break;
                            }
                        }
                        if (o != null) {
                            break;
                        }
                    }
                    if (o != null) {
                        break;
                    }
                }
            }
            if (o == null || bs.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", o.className + " " + a);
            if (via != null) {
                m.put("via", via);
            }
            List<String> lines = new ArrayList<>();
            for (Binding b : bs) {
                lines.add(Labels.bindingLine(r, b, false) + (b.conditional() ? "  (조건부)" : ""));
            }
            m.put("lines", lines);
            // VO 를 set 하는 코드가 그려진 기존 노드 (연결선 출발점) — 흐름도 노드는 그대로, 선만 잇는다
            java.util.Set<String> from = new java.util.LinkedHashSet<>();
            List<String> cands = new ArrayList<>();
            if (made0 != null) {
                cands.add(made0);
            } else {
                for (Binding b : bs) {
                    cands.add(b.id);
                    cands.add("SET_" + b.id);
                    if (b.callSiteId != null) {
                        cands.add(b.callSiteId);
                    }
                }
            }
            for (String x : cands) {
                if (g.has(x) && g.nodeById(g.nodeOf(x)) != null && !g.nodeOf(x).equals(g.nodeOf(s.id))) {
                    from.add(g.nodeOf(x));
                }
            }
            m.put("from", new ArrayList<>(from));
            out.add(m);
        }
        return out;
    }

    /** 모델 요소의 소스 위치 (노드 상세 패널: 파일:라인) */
    private static SourceLocation location(AnalysisResult r, String id) {
        Object o = r.element(id).orElse(null);
        if (o instanceof CallSiteInfo c) {
            return c.location;
        }
        if (o instanceof ControlBlock b) {
            return b.location;
        }
        if (o instanceof MethodInfo m) {
            return m.location;
        }
        if (o instanceof VariableChange v) {
            return v.location;
        }
        if (o instanceof Binding b) {
            return b.location;
        }
        if (o instanceof RequestParameterInfo p) {
            return p.location;
        }
        return null;
    }

    /** Mermaid classDef 문법("fill:#fff,stroke:#000,stroke-dasharray:4 3") → 키·값 */
    static Map<String, String> style(String def) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : def.split(",")) {
            int i = part.indexOf(':');
            if (i > 0) {
                m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
            }
        }
        return m;
    }

    private static String resource(String name) {
        try (InputStream in = HtmlFlowchart.class.getResourceAsStream(RES + name)) {
            if (in == null) {
                throw new IllegalStateException("리소스 없음: " + RES + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
