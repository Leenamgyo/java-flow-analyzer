package analyzer.model;

/** 클래스/메서드의 출처. */
public enum Origin {
    /** source-root 안에 소스가 있음 */
    SOURCE,
    /** JDK 또는 외부 라이브러리 (source-root 밖) */
    LIBRARY,
    /** 출처를 확정하지 못함 */
    UNKNOWN
}
