package analyzer.catalog;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.MethodInfo;
import analyzer.model.Origin;
import analyzer.parser.TypeNames;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 카탈로그 공통 인터페이스 (사용자: Util 카탈로그와 run 카탈로그 간 인터페이스가 맞아야 쿼리 가능). 두 카탈로그 모두
 * 메서드를 {@code key}(분석 모델 analysis.yaml 의 메서드 키 그대로: {@code 클래스FQN#이름(erasure 타입,…)} — 오버로딩·같은
 * 단순명 클래스·중첩 클래스까지 구분)로 가리키고, 그 메서드에서 (전이적으로) 닿는 프로젝트 메서드를 같은 계산의 {@code uses}
 * (key 목록, 정렬)로 적는다. 쿼리는 이 두 칸만으로 잇는다.
 */
public final class CatalogKeys {

    private CatalogKeys() {
    }

    /**
     * 그 분석의 흐름도에 그려진 메서드의 key — 사용자: 흐름도의 노드를 그대로 옮겨서, 흐름도에 있는 내용이 모두 매칭되어야.
     * Action 분석과 같은 설정(로직 역할·flowchart-exclude)으로 흐름도 그래프를 만들어, ① 상자로 그린 메서드 ② 흐름도에 그린 메서드
     * (진입·상자·펼쳐 보이는 바인딩 함수) 안 호출(flowchart-exclude 제외)의 대상·구현 후보 중 프로젝트 소스 메서드를 모은다(진입 메서드
     * 자신 제외, 정렬). VO setter·getter 도 흐름도에 있으면(값 설정 줄) 포함.
     */
    public static List<String> uses(AnalysisResult r, analyzer.config.AnalyzerConfig cfg) {
        analyzer.mermaid.MermaidGraph g = new analyzer.mermaid.FlowchartGenerator(
                analyzer.mermaid.FlowchartGenerator.roles(cfg.phases.logicRoles)).exclude(cfg.output.flowchartExclude).checked(r);
        Set<String> out = new TreeSet<>();
        for (MethodInfo m : r.methods()) {
            if (!r.isEntry(m.id) && g.isSubgraph("sg_" + m.id)) {
                out.add(m.key); // 상자로 그린 메서드
            }
        }
        // 흐름도에 그려진 메서드(진입·상자) 와 상자 없이 부른 줄 아래 ▸·│ 로 본문을 펼쳐 보이는 바인딩 함수(createCommand 등) —
        // 흐름도는 그린 메서드 안의 코드를 빠짐없이 보이므로(FlowchartSweepTest) 그 안의 호출 전부가 흐름도에 있는 내용이다.
        // 노드 단위로 고르면 값 설정 줄 안에 펼친 호출(StringUtil.getParameter 등)이 빠졌다. 뺄 것은 flowchart-exclude 뿐.
        Set<String> drawn = new java.util.HashSet<>(analyzer.mermaid.FlowchartGenerator.bindingFunctions(r));
        for (MethodInfo m : r.methods()) {
            if (r.isEntry(m.id) || g.isSubgraph("sg_" + m.id)) {
                drawn.add(m.id);
            }
        }
        for (CallSiteInfo s : r.callSites) {
            if (!drawn.contains(s.callerMethodId) || analyzer.serializer.Excludes.call(r, cfg.output.flowchartExclude, s)) {
                continue;
            }
            for (String t : new String[]{s.targetMethodId, s.implementationMethodId}) {
                if (t == null || r.isEntry(t)) {
                    continue;
                }
                MethodInfo m = r.method(t);
                if (m.origin == Origin.SOURCE) {
                    out.add(m.key);
                }
            }
        }
        return List.copyOf(out);
    }

    /** 선언에서 key (분석 전·분석 실패 시) — FlowAnalyzer.sourceMethod 와 같은 규칙 */
    public static String keyOf(String classFqn, MethodDeclaration md) {
        List<String> erased = md.getParameters().stream()
                .map(p -> TypeNames.erasure(p.getType().asString() + (p.isVarArgs() ? "..." : ""))).toList();
        return classFqn + "#" + md.getNameAsString() + "(" + String.join(",", erased) + ")";
    }

    /** key → 사람이 읽는 이름: com.x.ProductDAO#select(String) → ProductDAO.select(String) */
    public static String display(String key) {
        int h = key.indexOf('#');
        if (h < 0) {
            return key;
        }
        String cls = key.substring(0, h);
        return simple(cls) + "." + key.substring(h + 1);
    }

    public static String classOf(String key) {
        int h = key.indexOf('#');
        return h < 0 ? key : key.substring(0, h);
    }

    public static String simple(String fqn) {
        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    }
}
