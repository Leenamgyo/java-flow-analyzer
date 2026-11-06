package analyzer.model;

/** 역할 분류 근거. kind: ANNOTATION | SUPERTYPE | SUFFIX */
public record RoleEvidence(String kind, String value, ClassRole role) {
}
