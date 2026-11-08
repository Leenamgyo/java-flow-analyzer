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

    public static String text(Node n) {
        return n.toString(NO_COMMENTS).replaceAll("\\s+", " ").trim();
    }
}
