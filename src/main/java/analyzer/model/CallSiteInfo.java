package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/** 실제 소스상의 메서드 호출 위치 1건. */
public final class CallSiteInfo {
    public String id;
    public String callerMethodId;
    /** 미해결이면 null */
    public String targetMethodId;
    public String methodName;
    public int argumentCount;
    /** 소스 표현식 원문 */
    public String expression;
    /** 수신 객체 표현식 (없으면 null) */
    public String scopeExpression;
    /** 호출자 메서드 내 호출 순서 (1부터) */
    public int order;
    /** 호출자 메서드 내 호출+제어블록 공통 순번 */
    public int seq;
    /** 호출 Depth = 호출자 Depth + 1 */
    public int depth;
    public ResolutionStatus resolution;
    /** 해석 근거: SYMBOL_SOLVER | DECLARED_TYPE | IMPLICIT_THIS | STATIC_TYPE ... */
    public String resolutionBasis;
    public String resolutionReason;
    public final List<String> candidates = new ArrayList<>();
    /** 수신 타입(확인된 경우) */
    public String receiverType;
    public Origin targetOrigin;
    /**
     * 본문 없는 인터페이스/추상 메서드 호출에서 source-root 내 유일 구현으로 연결한 구현 메서드 (없으면 null).
     * target-method-id(선언 대상)는 그대로 두고, expansion 은 이 구현 메서드 기준으로 판정한다.
     */
    public String implementationMethodId;
    /** 구현 연결 근거: SINGLE_SOURCE_IMPLEMENTATION | NO_SOURCE_IMPLEMENTATION | MULTIPLE_SOURCE_IMPLEMENTATIONS | ... */
    public String implementationBasis;
    public String implementationReason;
    /** 구현 탐색에서 발견한 구현 클래스 (연결 여부와 무관) */
    public final List<String> implementationCandidates = new ArrayList<>();
    public Expansion expansion;
    /** SHARED 인 경우 전개된 호출 위치 ID */
    public String expandedAt;
    /** 소속 제어 블록 ID (메서드 최상위면 null) */
    public String controlBlockId;
    /** BODY | CONDITION | SELECTOR | ITERABLE | INITIALIZER | UPDATE | RESOURCE | VALUE */
    public String position;
    public boolean conditional;
    public final List<String> conditions = new ArrayList<>();
    /** 람다 본문 등 정의 시점이 아닌 나중에 실행되는 위치 */
    public boolean deferred;
    /** 인자 표현식 원문 */
    public final List<String> arguments = new ArrayList<>();
    /** 반환값 사용: assigned:변수 | returned | argument | condition | discarded | expression */
    public String resultUsage;
    public SourceLocation location;

    /** 전개 판정 대상: 구현 연결이 있으면 구현 메서드, 아니면 선언 대상 */
    public String expansionTargetId() {
        return implementationMethodId != null ? implementationMethodId : targetMethodId;
    }
}
