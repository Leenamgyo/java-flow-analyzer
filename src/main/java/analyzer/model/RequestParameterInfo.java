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
    public String type;
    public String defaultValue;
    public Boolean required;
    public SourceLocation location;

    /** 이름이 확정된 개별 요청 값인지 (UNKNOWN·전체 조회·body·@ModelAttribute 객체 바인딩이 아님) */
    public boolean named() {
        return name != null && !"UNKNOWN".equals(nameResolution) && !"@ModelAttribute".equals(extraction);
    }
}
