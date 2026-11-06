package analyzer.model;

import com.github.javaparser.ast.Node;

/** 분석 근거가 되는 소스 위치. file 은 source-root 기준 상대 경로. */
public record SourceLocation(String file, int beginLine, int beginColumn, int endLine, int endColumn) {

    public static SourceLocation of(Node node, String file) {
        return node.getRange()
                .map(r -> new SourceLocation(file, r.begin.line, r.begin.column, r.end.line, r.end.column))
                .orElse(new SourceLocation(file, -1, -1, -1, -1));
    }

    public String shortText() {
        return file + ":" + beginLine;
    }
}
