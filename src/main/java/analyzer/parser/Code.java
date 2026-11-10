package analyzer.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.PrinterConfiguration;

/** 표시용 소스 원문: 주석 제외, 공백 한 칸으로 정규화 (줄이지 않음) */
public final class Code {
    private static final PrinterConfiguration NO_COMMENTS = new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS));

    private Code() {
    }

    /** 메서드·생성자 본문 (추상 메서드면 없음) */
    public static java.util.Optional<com.github.javaparser.ast.stmt.BlockStmt> body(com.github.javaparser.ast.body.CallableDeclaration<?> c) {
        return c instanceof com.github.javaparser.ast.body.MethodDeclaration m ? m.getBody()
                : java.util.Optional.of(((com.github.javaparser.ast.body.ConstructorDeclaration) c).getBody());
    }

    public static String text(Node n) {
        return n.toString(NO_COMMENTS).replaceAll("\\s+", " ").trim();
    }
}
