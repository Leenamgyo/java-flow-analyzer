package analyzer.model;

import java.util.ArrayList;
import java.util.List;

public final class MethodInfo {
    public String id;
    /** 안정 키: 클래스키#이름(파라미터 타입 erasure) */
    public String key;
    public String classId;
    public String name;
    /** 표시용 시그니처: name(Type1, Type2) */
    public String signature;
    /** 파라미터 타입까지 확정된 시그니처인지 */
    public boolean signatureResolved;
    public Visibility visibility;
    /** 인터페이스 멤버처럼 선언부에 없는 암묵적 제어자인지 */
    public boolean modifiersImplicit;
    public boolean isStatic;
    public boolean isAbstract;
    public boolean hasBody;
    public String returnType;
    public final List<MethodParameter> parameters = new ArrayList<>();
    public final List<String> thrownTypes = new ArrayList<>();
    public final List<String> annotations = new ArrayList<>();
    public Origin origin;
    public SourceLocation location;
    /** 가장 짧은 호출 경로 기준 Depth (진입 메서드 = 0) */
    public int depth;
    /** 본문을 분석(전개)했는지 */
    public boolean analyzed;
    /** 이 메서드 본문이 전개된 호출 위치 ID (진입 메서드는 null) */
    public String expandedAt;
    /** 전개하지 않은 경우 그 사유 */
    public Expansion notAnalyzedReason;
}
