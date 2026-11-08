package analyzer.model;

public enum ClassRole {
    ACTION("Action"),
    CONTROLLER("Controller"),
    SERVICE("Service"),
    BIZ("Biz"),
    DAO("DAO"),
    REPOSITORY("Repository"),
    MAPPER("Mapper"),
    /** 값 객체: VO / DTO / Command / (Struts) Form */
    VO("VO"),
    /** 입력 검증 클래스 (Suffix Validator) */
    VALIDATOR("Validator"),
    UTIL("Util"),
    OTHER("Other");

    private final String label;

    ClassRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 표시명(대소문자 무시) 또는 enum 이름으로 찾는다. */
    public static java.util.Optional<ClassRole> fromLabel(String s) {
        for (ClassRole r : values()) {
            if (r.label.equalsIgnoreCase(s) || r.name().equalsIgnoreCase(s)) {
                return java.util.Optional.of(r);
            }
        }
        return java.util.Optional.empty();
    }
}
