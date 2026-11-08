package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/** 변수 값이 정해지거나 바뀌는 코드 1건 (VC*). */
public final class VariableChange {
    public String id;
    public String variableId;
    public String methodId;
    /** 변수 안에서의 순서 (1부터, 소스 순서) */
    public int order;
    /** INIT(선언 초기값) | PARAM(파라미터로 받음) | ASSIGN(=) | COMPOUND(+= -= …) | INCREMENT(++) | DECREMENT(--) */
    public String op;
    /** "=", "-=", "++" … (INIT/PARAM 은 null) */
    public String operator;
    /** 소스 원문: total -= discount / size = MAX_SIZE / long total = sum(items) */
    public String expression;
    /** 대입되는 값 식 (++/-- 는 null) */
    public String valueExpression;
    public final List<ValueSource> sources = new ArrayList<>();
    /** 바깥 → 안쪽 조건. 비어 있으면 always */
    public final List<String> conditions = new ArrayList<>();
    public String controlBlockId;
    /** 이 식 안에서 일어나는 호출 (흐름상 호출 뒤에 값이 바뀐다) */
    public final List<String> callSiteIds = new ArrayList<>();
    public SourceLocation location;

    /** 선언·파라미터가 아닌 실제 상태 변화 */
    public boolean isChange() {
        return !"INIT".equals(op) && !"PARAM".equals(op);
    }
}
