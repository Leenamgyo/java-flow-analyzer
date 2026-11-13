package analyzer.query;

import analyzer.mermaid.MermaidGraph;

import java.util.ArrayList;
import java.util.List;

/** 질의 결과 표 → 콘솔(열 맞춤, 한글 폭 2) · Markdown · CSV */
final class TableFormat {

    private TableFormat() {
    }

    static String text(CatalogQuery.Table t) {
        if (t.rows().isEmpty()) {
            return "(결과 없음)";
        }
        int n = t.columns().size();
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = MermaidGraph.width(t.columns().get(i));
        }
        for (List<String> r : t.rows()) {
            for (int i = 0; i < n - 1; i++) { // 마지막 열은 길어도 맞추지 않음
                w[i] = Math.min(60, Math.max(w[i], MermaidGraph.width(r.get(i))));
            }
        }
        StringBuilder sb = new StringBuilder();
        line(sb, t.columns(), w);
        List<String> dash = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dash.add("-".repeat(Math.max(3, i == n - 1 ? MermaidGraph.width(t.columns().get(i)) : w[i])));
        }
        line(sb, dash, w);
        t.rows().forEach(r -> line(sb, r, w));
        return sb.toString().stripTrailing();
    }

    private static void line(StringBuilder sb, List<String> cells, int[] w) {
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i);
            sb.append(c);
            if (i < cells.size() - 1) {
                sb.append(" ".repeat(Math.max(1, w[i] - MermaidGraph.width(c) + 2)));
            }
        }
        sb.append('\n');
    }

    static String markdown(CatalogQuery.Table t) {
        StringBuilder sb = new StringBuilder("| " + String.join(" | ", t.columns()) + " |\n|");
        t.columns().forEach(c -> sb.append("---|"));
        sb.append('\n');
        for (List<String> r : t.rows()) {
            sb.append("| ").append(String.join(" | ", r.stream().map(x -> x.replace("|", "\\|")).toList())).append(" |\n");
        }
        if (t.rows().isEmpty()) {
            sb.append("\n(결과 없음)\n");
        }
        return sb.toString();
    }

    static String csv(CatalogQuery.Table t) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", t.columns().stream().map(TableFormat::q).toList())).append('\n');
        for (List<String> r : t.rows()) {
            sb.append(String.join(",", r.stream().map(TableFormat::q).toList())).append('\n');
        }
        return sb.toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
