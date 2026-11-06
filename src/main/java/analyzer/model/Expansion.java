package analyzer.model;

/** 호출 위치에서 대상 메서드 본문을 전개했는지 여부와 그 이유. */
public enum Expansion {
    /** 이 호출 위치 아래에서 대상 메서드 본문을 분석함 */
    EXPANDED,
    /** 대상 메서드는 다른 호출 위치(expanded-at)에서 이미 전개됨 */
    SHARED,
    /** 재귀/순환 호출 — 재탐색하지 않음 */
    CYCLE,
    /** 소스가 있으나 max-depth 제한으로 전개하지 않음 */
    DEPTH_LIMIT,
    /** 본문이 없는 abstract/interface 메서드 — 런타임 구현체 미확정 */
    NO_BODY,
    /** source-root 밖(JDK/라이브러리)이라 전개 불가 */
    EXTERNAL,
    /** 대상이 미해결이라 전개 불가 */
    NOT_APPLICABLE
}
