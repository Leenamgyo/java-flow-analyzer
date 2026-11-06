package analyzer.model;

import java.util.List;

/**
 * 바인딩 값의 최초 출처 1건.
 *
 * @param kind        REQUEST | LITERAL | CONSTANT | PARAMETER | CALL | FIELD | NEW | LOOP | UNRESOLVED
 * @param value       표시값: 파라미터명, 리터럴, 상수식, 호출식 등
 * @param parameterId REQUEST 인 경우 P* ID
 * @param via         메서드 인자로 전달된 경로의 호출 위치 ID (출처 쪽 → 바인딩 쪽). 같은 메서드면 빈 목록
 */
public record ValueSource(String kind, String value, String parameterId, List<String> via) {

    /** request.appId / literal:testShop / constant:Constants.X / param:x / call:m.getX() / field:x / new:X / unresolved:expr */
    public String display() {
        return switch (kind) {
            case "REQUEST" -> "request." + value;
            case "LITERAL" -> "literal:" + value;
            case "CONSTANT" -> "constant:" + value;
            case "PARAMETER" -> "param:" + value;
            case "CALL" -> "call:" + value;
            case "FIELD" -> "field:" + value;
            case "NEW" -> "new:" + value;
            case "LOOP" -> "loop:" + value;
            default -> "unresolved:" + value;
        };
    }
}
