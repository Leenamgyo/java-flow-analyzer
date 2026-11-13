package analyzer.query;

import analyzer.catalog.CatalogKeys;
import analyzer.classifier.ClassRoleClassifier;
import analyzer.model.ClassRole;
import analyzer.parser.ProjectSources;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * coverage: 쿼리 결과가 완전한지 (사용자: 이러려면 데이터가 완벽해야). 소스와 카탈로그를 비교해 빠진 것을 보인다.
 * <ol>
 *   <li>run 카탈로그가 없는 Action/Controller — 그 Action 은 쿼리 결과에서 빠짐</li>
 *   <li>Action 이 쓰는데 Util 기능 목록이 없는 Util — unused 등에서 빠짐</li>
 *   <li>카탈로그를 만든 뒤 소스가 바뀐 클래스 (파일 수정 시각) — 다시 run / --all-methods</li>
 * </ol>
 */
final class Coverage {

    private Coverage() {
    }

    static CatalogQuery.Table check(CatalogQuery q, ProjectSources sources) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        ClassRoleClassifier classifier = new ClassRoleClassifier();
        // 1. Action/Controller (일괄 카탈로그 --all-actions 와 같은 찾기)
        for (ClassOrInterfaceDeclaration ci : analyzer.catalog.SourceScan.actions(sources)) {
            String fqn = analyzer.catalog.SourceScan.fqn(ci);
            if (!q.runFiles.containsKey(fqn)) {
                rows.add(List.of("run 카탈로그 없음", fqn, "./gradlew run --args=\"… --class " + ci.getNameAsString() + "\"  (전부: --all-actions)"));
            }
        }
        // 2. 쓰이는 Util 중 기능 목록 없는 것 (역할은 이름 규칙으로 — 카탈로그에는 역할이 없음)
        Set<String> utils = new TreeSet<>();
        q.uses().forEach(u -> {
            String cls = CatalogKeys.classOf(u.usedKey());
            if (classifier.classify(CatalogKeys.simple(cls), List.of(), List.of()).role() == ClassRole.UTIL && !q.utilMethods.containsKey(cls)) {
                utils.add(cls);
            }
        });
        utils.forEach(c -> rows.add(List.of("Util 기능 목록 없음", c, "./gradlew run --args=\"… --class " + CatalogKeys.simple(c) + " --all-methods\"")));
        // 3. 카탈로그 뒤에 바뀐 소스
        stale(q.runFiles, sources, rows, "run 다시");
        stale(q.utilFiles, sources, rows, "--all-methods 다시");
        return new CatalogQuery.Table(List.of("빠진 것", "클래스", "채우는 법"), rows);
    }

    private static void stale(java.util.Map<String, Path> files, ProjectSources sources, List<List<String>> rows, String how) throws IOException {
        for (var e : files.entrySet()) {
            Optional<Path> src = sources.findTypeByQualifiedName(e.getKey()).flatMap(TypeDeclaration::findCompilationUnit)
                    .flatMap(cu -> cu.getStorage()).map(s -> s.getPath());
            if (src.isPresent() && Files.getLastModifiedTime(src.get()).compareTo(Files.getLastModifiedTime(e.getValue())) > 0) {
                rows.add(List.of("카탈로그 뒤 소스 변경", e.getKey(), how));
            }
        }
    }
}
