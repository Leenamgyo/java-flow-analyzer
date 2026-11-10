package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 바인딩 대상 객체 1개 (변수 단위). 메서드 인자로 넘겨져 다른 메서드에서 이어서 설정되면 같은 객체로 본다
 * (호출 위치가 하나뿐이고 인자가 변수일 때만).
 */
public final class ObjectInfo {
    public String id;
    /** VO | MAP */
    public String kind;
    /** source 클래스면 C* (Map 등 라이브러리 타입이면 null) */
    public String classId;
    public String className;
    public String variable;
    /** 객체 변수가 선언된(소유) 메서드 */
    public String methodId;
    public final List<String> bindingIds = new ArrayList<>();
    /** VO 소스 클래스의 인스턴스 필드 → 선언 타입 (상위 클래스 포함, 표시용 — vo.md 프로퍼티 타입. YAML 에는 넣지 않음) */
    public final java.util.Map<String, String> fieldTypes = new java.util.LinkedHashMap<>();

    // ---- 필드 커버리지 (VO 만; 소스에 선언된 인스턴스 필드 기준) ----
    public final List<String> declaredFields = new ArrayList<>();
    public final List<String> assignedFields = new ArrayList<>();
    /** 모든 할당이 조건부인 필드 */
    public final List<String> conditionalFields = new ArrayList<>();
    /** 할당은 없고 선언 초기값만 있는 필드 */
    public final List<String> initialOnlyFields = new ArrayList<>();
    public final List<String> unassignedFields = new ArrayList<>();
    /** 객체 복사(COPY) 등으로 매핑 여부를 정적으로 확인할 수 없는 필드 */
    public final List<String> unknownFields = new ArrayList<>();
}
