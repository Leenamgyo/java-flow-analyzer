package analyzer.query;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.parser.ProjectSources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 카탈로그 질의 명령 (사용자: 데이터로 쿼리, 콘솔로 보고 결과도 남김). 분석하지 않고 카탈로그만 읽는다.
 * <pre>
 *   ./gradlew query --args="who-uses ContentUtil.getInfo"          이 메서드를 쓰는 Action (+ 쓰는 Util 메서드)
 *   ./gradlew query --args="uses OrderAction"                       이 Action(.진입)이 쓰는 메서드
 *   ./gradlew query --args="common 2 class=*Util"                   2곳 이상에서 쓰는 메서드 (공통화 후보)
 *   ./gradlew query --args="shared OrderAction ProductAction"       함께 쓰는 메서드
 *   ./gradlew query --args="unused ContentUtil"                     어느 Action 도 안 쓰는 메서드 (Util 기능 목록 기준)
 *   ./gradlew query --args="usage"                                  메서드마다 쓰는 곳 전부
 *   ./gradlew query --args="coverage --config my-project.yml"       빠진 데이터 (run 안 한 Action · 기능 목록 없는 Util · 바뀐 소스)
 * </pre>
 * 필터: class=패턴 (쓰이는 메서드의 클래스), action=패턴 (쓰는 Action). 옵션: --config, --catalog-dir, --md 파일, --csv 파일
 * (파일 이름만 주면 output.query-dir 기본 query/ 아래, 절대 경로는 그대로).
 */
public final class QueryMain {

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[ERROR] " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    static final String USAGE = """
            사용법: ./gradlew query --args="<질의> [필터] [옵션]"
              who-uses <클래스.메서드|클래스|패턴>   이 메서드를 쓰는 Action (+ 쓰는 Util 메서드)
              uses <Action[.진입]>                  이 Action 이 쓰는 메서드
              common [N]                            N곳(기본 2) 이상에서 쓰는 메서드 — 공통화 후보
              shared <Action> <Action> …            모두 함께 쓰는 메서드
              unused [클래스 패턴]                   Util 기능 목록 중 어느 Action 도 안 쓰는 메서드
              usage                                 메서드마다 쓰는 곳 전부
              coverage                              빠진 데이터 (소스 경로가 있는 --config 필요)
            필터: class=패턴 action=패턴      옵션: --config <yml> --catalog-dir <dir> --md <파일> --csv <파일>""";

    public static int run(String[] args) throws Exception {
        Path config = null, md = null, csv = null;
        String catalogDir = null, classF = null, actionF = null;
        List<String> words = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i], v = i + 1 < args.length ? args[i + 1] : null;
            switch (a) {
                case "--config" -> { config = Path.of(need(v, a)); i++; }
                case "--catalog-dir" -> { catalogDir = need(v, a); i++; }
                case "--md" -> { md = Path.of(need(v, a)); i++; }
                case "--csv" -> { csv = Path.of(need(v, a)); i++; }
                default -> {
                    if (a.startsWith("class=")) {
                        classF = a.substring(6);
                    } else if (a.startsWith("action=")) {
                        actionF = a.substring(7);
                    } else {
                        words.add(a);
                    }
                }
            }
        }
        if (words.isEmpty()) {
            System.out.println(USAGE);
            return 0;
        }
        AnalyzerConfig cfg = config != null ? ConfigLoader.load(config)
                : Files.isRegularFile(Path.of("config/analyzer.yml")) ? ConfigLoader.load(Path.of("config/analyzer.yml")) : ConfigLoader.defaults();
        if (catalogDir != null) {
            cfg.output.catalogDir = catalogDir;
        }
        CatalogQuery q = CatalogQuery.load(Path.of(cfg.output.catalogDir).toAbsolutePath().normalize());
        CatalogQuery.Filters f = new CatalogQuery.Filters(classF, actionF);
        String verb = words.get(0);
        List<String> rest = words.subList(1, words.size());
        CatalogQuery.Table t = switch (verb) {
            case "who-uses" -> q.whoUses(one(rest, "who-uses <클래스.메서드>"), f);
            case "uses" -> q.usesOf(one(rest, "uses <Action>"), f);
            case "common" -> q.common(rest.isEmpty() ? 2 : Integer.parseInt(rest.get(0)), f);
            case "shared" -> {
                if (rest.size() < 2) {
                    throw new IllegalArgumentException("shared 는 Action 을 2개 이상: shared OrderAction ProductAction");
                }
                yield q.shared(rest, f);
            }
            case "unused" -> q.unused(rest.isEmpty() ? "*" : rest.get(0));
            case "usage" -> q.usage(f);
            case "coverage" -> Coverage.check(q, new ProjectSources(cfg.target.effectiveSourceRoots().stream().map(Path::of).toList(),
                    cfg.analysis.classpathJars.stream().map(Path::of).toList()));
            default -> throw new IllegalArgumentException("알 수 없는 질의: " + verb + "\n" + USAGE);
        };
        String title = String.join(" ", words) + (classF == null ? "" : " class=" + classF) + (actionF == null ? "" : " action=" + actionF);
        System.out.println("# " + title);
        System.out.println(q.basis());
        System.out.println(TableFormat.text(t));
        System.out.println("(" + t.rows().size() + "행)");
        md = md == null ? null : place(cfg, md);
        csv = csv == null ? null : place(cfg, csv);
        if (md != null) {
            Files.writeString(md, "# " + title + "\n\n" + q.basis() + "\n\n" + TableFormat.markdown(t), StandardCharsets.UTF_8);
            System.out.println("저장: " + md.toAbsolutePath());
        }
        if (csv != null) {
            Files.writeString(csv, "﻿" + TableFormat.csv(t), StandardCharsets.UTF_8); // BOM — 엑셀에서 한글
            System.out.println("저장: " + csv.toAbsolutePath());
        }
        return 0;
    }

    /** 파일 이름·상대 경로는 output.query-dir(기본 query/) 아래로, 절대 경로는 그대로 (사용자: md 는 query 폴더 아래로) */
    public static Path place(AnalyzerConfig cfg, Path file) throws java.io.IOException {
        Path p = file.isAbsolute() ? file : Path.of(cfg.output.queryDir).resolve(file);
        p = p.toAbsolutePath().normalize();
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        return p;
    }

    private static String one(List<String> rest, String usage) {
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("인자가 필요합니다: " + usage);
        }
        return rest.get(0);
    }

    private static String need(String v, String opt) {
        if (v == null) {
            throw new IllegalArgumentException(opt + " 값이 필요합니다.");
        }
        return v;
    }
}
