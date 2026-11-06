package analyzer.model;

import com.github.javaparser.ast.AccessSpecifier;

public enum Visibility {
    PUBLIC("public", "+"),
    PROTECTED("protected", "#"),
    PACKAGE_PRIVATE("package-private", "~"),
    PRIVATE("private", "-"),
    UNKNOWN("unknown", "?");

    private final String text;
    private final String umlSymbol;

    Visibility(String text, String umlSymbol) {
        this.text = text;
        this.umlSymbol = umlSymbol;
    }

    public String text() {
        return text;
    }

    public String umlSymbol() {
        return umlSymbol;
    }

    public static Visibility of(AccessSpecifier spec) {
        return switch (spec) {
            case PUBLIC -> PUBLIC;
            case PROTECTED -> PROTECTED;
            case PRIVATE -> PRIVATE;
            case NONE -> PACKAGE_PRIVATE;
        };
    }
}
