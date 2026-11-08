package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/** 객체(VO/DTO/Command/Map) 필드에 값이 설정되는 코드 1건. 같은 필드의 여러 할당은 모두 별도 Binding 으로 기록한다. */
public final class Binding {
    public String id;
    public String objectId;
    /** 객체 안에서의 할당 순서 (1부터, 소스 순서) */
    public int order;
    /** 대상 필드. 복사(COPY)는 "*", 확인 불가면 "?(식)" */
    public String field;
    /** 대상 필드의 선언 타입 (source VO 에서 확인한 경우, 아니면 null) */
    public String fieldType;
    /** SETTER | BUILDER | CONSTRUCTOR | FIELD | MAP_PUT | COPY */
    public String assignment;
    /** setter/builder 메서드명, 생성자면 클래스명, Map 이면 put, 복사면 copyProperties */
    public String method;
    /** 할당 코드가 있는 메서드 */
    public String methodId;
    /** 대응 호출 위치 (setter/builder/put 호출이 모델에 있을 때) */
    public String callSiteId;
    /** 메서드 안에서 할당 코드를 감싸는 가장 안쪽 제어 블록 (최상위면 null) */
    public String controlBlockId;
    public String valueExpression;
    public final List<ValueSource> sources = new ArrayList<>();
    /** 출처 → 필드 사이의 변환/가공 (Integer.parseInt, .trim(), expr: page - 1 …) */
    public final List<String> transforms = new ArrayList<>();
    /** 바깥 → 안쪽 순서의 조건. 비어 있으면 always */
    public final List<String> conditions = new ArrayList<>();
    /** Request Parameter 기본값 (출처가 기본값 있는 요청 파라미터일 때) */
    public String defaultValue;
    /** 출처 경로의 변수에 다른 대입이 있음 → 항상 이 값이라고 확정할 수 없음 */
    public boolean reassigned;
    public ResolutionStatus resolution;
    public String resolutionReason;
    public SourceLocation location;

    public boolean conditional() {
        return !conditions.isEmpty();
    }
}
