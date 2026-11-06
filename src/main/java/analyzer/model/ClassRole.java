package analyzer.model;

public enum ClassRole {
    ACTION("Action"),
    CONTROLLER("Controller"),
    SERVICE("Service"),
    BIZ("Biz"),
    DAO("DAO"),
    REPOSITORY("Repository"),
    MAPPER("Mapper"),
    UTIL("Util"),
    OTHER("Other");

    private final String label;

    ClassRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
