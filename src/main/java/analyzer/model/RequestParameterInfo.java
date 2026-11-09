package analyzer.model;

public final class RequestParameterInfo {
    public String id;
    /** 파라미터명. 확인 불가면 UNKNOWN, 전체 맵 조회면 null */
    public String name;
    /** LITERAL | CONSTANT | UNKNOWN | ALL_PARAMETERS | ANNOTATION | PARAMETER_NAME */
    public String nameResolution;
    public String nameExpression;
    /** 추출 방식: HttpServletRequest.getParameter, RequestUtil.getString, @RequestParam ... */
    public String extraction;
    public String sourceMethodId;
    public String callSiteId;
    public String variable;
    /** 받는 변수의 선언 타입 (T x = read() 일 때) */
    public String variableType;
    public String type;
    public String defaultValue;
    public Boolean required;
    /**
     * 읽기 메서드(설정 규칙의 source 메서드)에 요청 읽기 말고 로직이 있으면 그 근거 (예: "if 문 · 호출 VasUtil.normalize").
     * null = 단순 읽기·기본값 래퍼(StringUtil.getParam 류) 또는 본문을 모름(라이브러리·JDK). 표시에서 로직이 있으면
     * request.이름 으로 줄이지 않고 호출 코드를 그대로 보인다 (사용자 요구: 로직 있는 읽기 함수는 표현).
     */
    public String readerLogic;
    public SourceLocation location;

    /** 이름이 확정된 개별 요청 값인지 (UNKNOWN·전체 조회·body·@ModelAttribute 객체 바인딩이 아님) */
    public boolean named() {
        return name != null && !"UNKNOWN".equals(nameResolution) && !"@ModelAttribute".equals(extraction);
    }
}
