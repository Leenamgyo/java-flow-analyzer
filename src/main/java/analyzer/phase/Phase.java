package analyzer.phase;

/** 진입 메서드 실행 단계. 공통 구조: 바인딩 › 체크 › 로직 › 후처리. */
public enum Phase {
    BINDING("바인딩", "①"),
    CHECK("체크", "②"),
    LOGIC("로직", "③"),
    POST("후처리", "④"),
    /** 진입 메서드 최상위 return/throw */
    END("종료", ""),
    /** 판정 근거 없음 (중간 계산용 — 최종 Step 에는 남지 않는다) */
    NONE("-", "");

    private final String label;
    private final String mark;

    Phase(String label, String mark) {
        this.label = label;
        this.mark = mark;
    }

    public String label() {
        return label;
    }

    public String title() {
        return mark.isEmpty() ? label : mark + " " + label;
    }

    /** 집계 우선순위: 로직 > 바인딩 > 체크 > 없음 */
    static Phase max(Phase a, Phase b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(Phase p) {
        return switch (p) {
            case LOGIC -> 3;
            case BINDING -> 2;
            case CHECK -> 1;
            default -> 0;
        };
    }
}
