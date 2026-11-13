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

    /** 진입 메서드에서 닿는 source 메서드의 key (호출 대상·구현 후보, 진입 메서드 자신·VO 제외, 정렬) */
    public static List<String> uses(AnalysisResult r) {
        Set<String> out = new TreeSet<>();
        for (CallSiteInfo s : r.callSites) {
            for (String t : new String[]{s.targetMethodId, s.implementationMethodId}) {
                if (t == null || r.isEntry(t)) {
                    continue;
                }
                MethodInfo m = r.method(t);
                // VO(setter·getter)는 빼다 — 공통화 후보 목록을 어지럽힘 (VO 사용은 vo.md·VO 연결)
                if (m.origin == Origin.SOURCE && r.classOf(m).role != analyzer.model.ClassRole.VO) {
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
