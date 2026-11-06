package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 제어 블록. try/if/switch 문은 주 블록이고 catch/finally/else-if/else/case 는
 * 주 블록을 parent 로 갖는 BRANCH 자식 블록이다.
 */
public final class ControlBlock {
    public String id;
    public BlockType type;
    public String methodId;
    public String parentBlockId;
    /** BODY(부모 본문에 중첩) | BRANCH(부모 문장의 대체 분기) | null(메서드 최상위) */
    public String relation;
    /** 제어 블록 중첩 Depth. 메서드 호출 Depth 와 무관하다. BRANCH 는 주 블록과 같은 값. */
    public int nestingDepth;
    public int seq;
    /** 조건식/헤더 원문 (if/else-if/while/for/switch selector 등) */
    public String condition;
    public SourceLocation location;
    public final List<String> callIds = new ArrayList<>();
    public final List<String> childBlockIds = new ArrayList<>();
    public boolean deferred;

    // ---- if 체인 / switch ----
    public Integer branchOrder;
    public final List<String> caseLabels = new ArrayList<>();
    public boolean isDefault;

    // ---- try ----
    public final List<String> catchBlockIds = new ArrayList<>();
    public String finallyBlockId;
    public final List<TryResource> resources = new ArrayList<>();

    // ---- catch / finally ----
    public String tryBlockId;
    public final List<String> exceptionTypes = new ArrayList<>();
    public String exceptionVariable;
    public Integer catchOrder;
    public boolean rethrows;
    public boolean throwsNewException;
    public boolean containsReturn;
    public boolean containsThrow;

    // ---- return / throw ----
    public String expression;
    public boolean rethrow;
    /** 이 return/throw 로 빠져나가며 실행되는 finally 블록 ID (안쪽→바깥쪽) */
    public final List<String> executesFinally = new ArrayList<>();
    /** throw 의 예외 흐름 해석 상태 */
    public ResolutionStatus exceptionResolution;
    public String caughtBy;
    public Boolean propagatesToCaller;
    public String exceptionReason;
}
