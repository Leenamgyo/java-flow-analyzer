package analyzer.model;

public enum BlockType {
    IF("IF", "if"),
    ELSE_IF("ELSEIF", "else-if"),
    ELSE("ELSE", "else"),
    SWITCH("SW", "switch"),
    CASE("CASE", "case"),
    FOR("FOR", "for"),
    FOREACH("FOREACH", "foreach"),
    WHILE("WHILE", "while"),
    DO_WHILE("DO", "do-while"),
    TRY("T", "try"),
    CATCH("CATCH", "catch"),
    FINALLY("FINALLY", "finally"),
    RETURN("RET", "return"),
    THROW("THROW", "throw");

    private final String idPrefix;
    private final String yamlName;

    BlockType(String idPrefix, String yamlName) {
        this.idPrefix = idPrefix;
        this.yamlName = yamlName;
    }

    public String idPrefix() {
        return idPrefix;
    }

    public String yamlName() {
        return yamlName;
    }

    /** 같은 문장의 대체 분기(부모 블록의 BRANCH 자식)인지 */
    public boolean isBranch() {
        return this == ELSE_IF || this == ELSE || this == CASE || this == CATCH || this == FINALLY;
    }

    public boolean isLeaf() {
        return this == RETURN || this == THROW;
    }
}
