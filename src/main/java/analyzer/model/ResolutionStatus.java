package analyzer.model;

/** 호출 대상 해석 상태. 깊이 제한(Expansion)과는 별개의 축이다. */
public enum ResolutionStatus {
    /** 호출 대상 메서드 선언을 확정함 */
    RESOLVED,
    /** 수신 객체 타입은 확인했으나 메서드 선언(파라미터 타입)은 확정하지 못함 */
    PARTIALLY_RESOLVED,
    /** 이름·인자 수가 같은 후보가 여러 개라 확정하지 못함 */
    AMBIGUOUS,
    /** 대상 타입/메서드를 확인하지 못함 */
    UNRESOLVED
}
