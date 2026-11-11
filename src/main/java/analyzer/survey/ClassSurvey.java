package analyzer.survey;

import analyzer.Main;
import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassRole;
import analyzer.model.MethodInfo;
import analyzer.model.ResolutionStatus;
import analyzer.parser.EntryPointLocator;
import analyzer.parser.ProjectSources;
import analyzer.serializer.Labels;
import analyzer.serializer.OutputWriter;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 클래스 조사 (--all-methods, 설정 target.all-methods): Action 의 진입 메서드 하나 대신 지정한 클래스(ContentUtil 등)의
 * 메서드를 전부 나열하고, 메서드마다 기존 분석(Main.analyze — 같은 규칙·같은 출력)을 진입 메서드로 돌려 그 메서드에 얽힌
 * Service·Biz·DAO·SQL·빈 이름 연결을 조사한다 (사용자: 액션 대신 ContentUtil 을 조사, 한 메서드에서 여러 개를 타던 것을
 * 모든 메서드로). 결과: &lt;output&gt;/&lt;클래스&gt;/dependencies.md (메서드별 표 + 의존 대상별 역색인) + 메서드마다 하위 폴더에
 * 기존 출력 전부(flowchart.html·analysis.yaml …). 분석 규칙은 바꾸지 않는다.
 */
public final class ClassSurvey {

    private static final Set<ClassRole> SERVICE = java.util.EnumSet.of(ClassRole.SERVICE, ClassRole.BIZ);
    private static final Set<ClassRole> DB = java.util.EnumSet.of(ClassRole.DAO, ClassRole.MAPPER, ClassRole.REPOSITORY);

    /** 메서드 하나의 조사 결과 */
    public record Entry(String method, String signature, String visibility, String folder, List<String> services,
                        List<String> daos, List<String> sql, List<String> beans, List<String> reflection,
                        List<String> unresolved, String error) {
    }

    private ClassSurvey() {
    }

    /** 조사 실행: 메서드마다 분석·출력하고 dependencies.md 를 쓴다. @return 결과 폴더 */
    public static Path run(AnalyzerConfig cfg) throws IOException {
        List<MethodDeclaration> methods = methods(cfg);
        TypeDeclaration<?> type = methods.isEmpty() ? null : (TypeDeclaration<?>) methods.get(0).getParentNode().orElseThrow();
        String simple = type == null ? simpleName(cfg.target.className) : type.getNameAsString();
        Path outDir = Main.outputDir(cfg, simple);
        Files.createDirectories(outDir);
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> used = new java.util.HashMap<>();
        String savedMethod = cfg.target.entryMethod;
        List<String> savedTypes = cfg.target.entryMethodParameterTypes;
        try {
            for (MethodDeclaration md : methods) {
                String name = md.getNameAsString();
                int n = used.merge(name, 1, Integer::sum);
                String folder = n == 1 ? name : name + "_" + n; // 오버로딩
                cfg.target.entryMethod = name;
                cfg.target.entryMethodParameterTypes = md.getParameters().stream()
                        .map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : "")).toList();
                String sig = name + "(" + String.join(", ", md.getParameters().stream().map(ClassSurvey::param).toList()) + ")";
                String vis = md.getAccessSpecifier().asString().isEmpty() ? "package-private" : md.getAccessSpecifier().asString();
                try {
                    AnalysisResult r = Main.analyze(cfg);
                    new OutputWriter().write(r, cfg, outDir.resolve(folder));
                    entries.add(entry(r, name, sig, vis, folder));
                    System.out.println("  [조사] " + simple + "." + sig + " → " + folder + "/");
                } catch (RuntimeException e) {
                    entries.add(new Entry(name, sig, vis, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                            e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString())));
                    System.err.println("[WARN] " + simple + "." + sig + " 분석 실패 — " + e.getMessage());
                }
            }
        } finally {
            cfg.target.entryMethod = savedMethod;
            cfg.target.entryMethodParameterTypes = savedTypes;
        }
        Files.writeString(outDir.resolve("dependencies.md"), render(type == null ? cfg.target.className
                : type.getFullyQualifiedName().orElse(simple), entries));
        return outDir;
    }

    /** 조사할 메서드: 클래스에 선언된 본문 있는 메서드 전부 (접근 제어자 무관, 소스 순서) */
    static List<MethodDeclaration> methods(AnalyzerConfig cfg) {
        ProjectSources sources = new ProjectSources(cfg.target.effectiveSourceRoots().stream().map(Path::of).toList(),
                cfg.analysis.classpathJars.stream().map(Path::of).toList());
        TypeDeclaration<?> type = EntryPointLocator.locateType(sources, cfg.target.className);
        return type.getMethods().stream().filter(m -> m.getBody().isPresent()).toList();
    }

    private static String param(Parameter p) {
        return p.getType().asString() + (p.isVarArgs() ? "..." : "") + " " + p.getNameAsString();
    }

    private static String simpleName(String n) {
        return n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n;
    }

    /** 분석 결과에서 그 메서드에 얽힌 의존 (진입에서 도달한 호출 전부) */
    static Entry entry(AnalysisResult r, String name, String sig, String vis, String folder) {
        Set<String> services = new LinkedHashSet<>();
        Set<String> daos = new LinkedHashSet<>();
        Set<String> sql = new LinkedHashSet<>();
        Set<String> beans = new LinkedHashSet<>();
        Set<String> reflection = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (CallSiteInfo s : r.callSites) {
            for (String t : new String[]{s.targetMethodId, s.implementationMethodId}) {
                if (t == null) {
                    continue;
                }
                MethodInfo m = r.method(t);
                ClassRole role = r.classOf(m).role;
                if (SERVICE.contains(role)) {
                    services.add(Labels.methodName(r, m));
                } else if (DB.contains(role)) {
                    daos.add(Labels.methodName(r, m));
                }
            }
            if (s.sql != null) {
                String id = s.sql.statementId != null ? s.sql.statementId : s.sql.id;
                sql.add((id == null ? "?" : id) + (s.sql.tables.isEmpty() ? "" : " · " + String.join(", ", s.sql.tables)));
            }
            if ("BEAN_NAME".equals(s.implementationBasis)) {
                beans.add(s.implementationReason == null ? s.expression : s.implementationReason);
                if (s.implementationMethodId == null) {
                    unresolved.add("`" + s.expression + "` — " + s.implementationReason);
                }
            }
            if ("REFLECTION".equals(s.resolutionBasis)) {
                MethodInfo m = r.method(s.targetMethodId);
                reflection.add("`" + Labels.truncate(s.expression, 80) + "` → " + Labels.methodName(r, m));
            }
            if (s.resolution == ResolutionStatus.UNRESOLVED || s.resolution == ResolutionStatus.AMBIGUOUS) {
                unresolved.add("`" + Labels.truncate(s.expression, 80) + "` — " + s.resolution
                        + (s.resolutionReason == null ? "" : " " + s.resolutionReason));
            }
        }
        return new Entry(name, sig, vis, folder, List.copyOf(services), List.copyOf(daos), List.copyOf(sql), List.copyOf(beans),
                List.copyOf(reflection), List.copyOf(unresolved), null);
    }

    static String render(String fqn, List<Entry> entries) {
        StringBuilder sb = new StringBuilder("# 클래스 조사: ").append(fqn).append("\n\n");
        sb.append("메서드마다 진입 메서드로 분석한 결과 (같은 분석 규칙). 메서드별 흐름도·analysis.yaml 은 하위 폴더.\n\n");
        sb.append("## 1. 메서드별 의존\n\n");
        sb.append("| 메서드 | 접근 | Service·Biz | DAO·Mapper | SQL (id · 테이블) | 결과 |\n|---|---|---|---|---|---|\n");
        for (Entry e : entries) {
            sb.append("| ").append(md(e.signature())).append(" | ").append(e.visibility()).append(" | ")
                    .append(cell(e.services())).append(" | ").append(cell(e.daos())).append(" | ").append(cell(e.sql())).append(" | ")
                    .append(e.error() != null ? "분석 실패: " + md(e.error()) : "[흐름도](" + e.folder() + "/flowchart.html)").append(" |\n");
        }
        // 의존 대상별 역색인: 이 Service·DAO 를 쓰는 메서드
        Map<String, List<String>> reverse = new TreeMap<>();
        for (Entry e : entries) {
            for (String d : e.services()) {
                reverse.computeIfAbsent(d, k -> new ArrayList<>()).add(e.method());
            }
            for (String d : e.daos()) {
                reverse.computeIfAbsent(d, k -> new ArrayList<>()).add(e.method());
            }
        }
        sb.append("\n## 2. 의존 대상별 (이 Service·DAO 를 쓰는 메서드)\n\n");
        if (reverse.isEmpty()) {
            sb.append("없음.\n");
        } else {
            sb.append("| 대상 | 쓰는 메서드 |\n|---|---|\n");
            reverse.forEach((k, v) -> sb.append("| ").append(md(k)).append(" | ")
                    .append(md(String.join(", ", new LinkedHashSet<>(v)))).append(" |\n"));
        }
        sb.append("\n## 3. 빈 이름·리플렉션 연결\n\n");
        boolean any = false;
        for (Entry e : entries) {
            if (e.beans().isEmpty() && e.reflection().isEmpty()) {
                continue;
            }
            any = true;
            sb.append("- **").append(md(e.signature())).append("**\n");
            e.beans().forEach(b -> sb.append("  - ").append(md(b)).append('\n'));
            e.reflection().forEach(b -> sb.append("  - 리플렉션 ").append(b).append('\n'));
        }
        if (!any) {
            sb.append("없음.\n");
        }
        sb.append("\n## 4. 확정하지 못한 호출\n\n");
        any = false;
        for (Entry e : entries) {
            if (e.unresolved().isEmpty()) {
                continue;
            }
            any = true;
            sb.append("- **").append(md(e.signature())).append("**\n");
            e.unresolved().forEach(u -> sb.append("  - ").append(u.replace("|", "\\|")).append('\n'));
        }
        if (!any) {
            sb.append("없음.\n");
        }
        return sb.toString();
    }

    private static String cell(List<String> items) {
        return items.isEmpty() ? "-" : md(String.join("<br>", items));
    }

    private static String md(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /** 표 정리용: 이름 → 메서드들 (테스트에서 사용) */
    static Map<String, Entry> byMethod(List<Entry> entries) {
        Map<String, Entry> m = new LinkedHashMap<>();
        entries.forEach(e -> m.put(e.signature(), e));
        return m;
    }
}
