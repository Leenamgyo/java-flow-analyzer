package analyzer.model;

/** try-with-resources 리소스 메타데이터. close-order 는 1 이 가장 먼저 close 된다(선언 역순). */
public record TryResource(String variable, String type, String initializer, int declarationOrder, int closeOrder,
                          SourceLocation location) {
}
