package analyzer.parser;

/** 사용자 지정 대상이 모호하거나 없을 때 등 분석을 계속할 수 없는 경우. */
public class AnalysisException extends RuntimeException {
    public AnalysisException(String message) {
        super(message);
    }
}
