package analyzer.catalog;

import analyzer.classifier.ClassRoleClassifier;
import analyzer.model.ClassRole;
import analyzer.parser.EntryPointLocator;
import analyzer.parser.ProjectSources;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 소스에서 Action/Controller · Util 클래스 찾기 (일괄 카탈로그 --all-actions·--all-utils, query coverage 공용).
 * 빠르게: 파일 이름(접미사)·어노테이션 문자열로 후보만 파싱하고, 역할은 분류기(어노테이션 > 상위 타입 > 접미사)로 확정.
 * 최상위·구체 클래스만 (인터페이스·추상·중첩 제외).
 */
public final class SourceScan {

    private static final ClassRoleClassifier CLASSIFIER = new ClassRoleClassifier();

    private SourceScan() {
    }

    public static List<ClassOrInterfaceDeclaration> actions(ProjectSources sources) throws IOException {
        return find(sources, Set.of(ClassRole.ACTION, ClassRole.CONTROLLER), true);
    }

    public static List<ClassOrInterfaceDeclaration> utils(ProjectSources sources) throws IOException {
        return find(sources, Set.of(ClassRole.UTIL), false);
    }

    private static List<ClassOrInterfaceDeclaration> find(ProjectSources sources, Set<ClassRole> roles, boolean annotated) throws IOException {
        List<ClassOrInterfaceDeclaration> out = new ArrayList<>();
        for (Path f : sources.allFiles()) {
            String simple = f.getFileName().toString().replaceFirst("\\.java$", "");
            boolean bySuffix = roles.contains(CLASSIFIER.classify(simple, List.of(), List.of()).role());
            if (!bySuffix && !(annotated && mentionsController(f))) {
                continue;
            }
            var cu = sources.parse(f);
            if (cu.isEmpty()) {
                continue;
            }
            for (ClassOrInterfaceDeclaration ci : cu.get().findAll(ClassOrInterfaceDeclaration.class)) {
                if (ci.isInterface() || ci.isAbstract() || ci.isNestedType()) {
                    continue;
                }
                ClassRole role = CLASSIFIER.classify(ci.getNameAsString(), ci.getAnnotations().stream().map(a -> a.getNameAsString()).toList(),
                        ci.getExtendedTypes().stream().map(t -> t.asString()).toList()).role();
                if (roles.contains(role)) {
                    out.add(ci);
                }
            }
        }
        out.sort((a, b) -> fqn(a).compareTo(fqn(b)));
        return out;
    }

    /**
     * Action/Controller 의 진입 메서드: 매핑 어노테이션(@RequestMapping·@GetMapping …)이 붙은 메서드 전부(Controller 는 보통 여럿),
     * 없으면 public execute(…) (Struts). 둘 다 없으면 빈 목록.
     */
    public static List<MethodDeclaration> entryMethods(ClassOrInterfaceDeclaration ci) {
        List<MethodDeclaration> mapped = ci.getMethods().stream().filter(m -> m.getBody().isPresent())
                .filter(m -> m.getAnnotations().stream().anyMatch(a -> EntryPointLocator.MAPPING_ANNOTATIONS.contains(a.getNameAsString())))
                .toList();
        if (!mapped.isEmpty()) {
            return mapped;
        }
        return ci.getMethodsByName("execute").stream().filter(m -> m.isPublic() && m.getBody().isPresent()).toList();
    }

    public static String fqn(ClassOrInterfaceDeclaration ci) {
        return ci.getFullyQualifiedName().orElse(ci.getNameAsString());
    }

    private static boolean mentionsController(Path f) {
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException e) {
            try {
                text = new String(Files.readAllBytes(f), java.nio.charset.Charset.forName("MS949"));
            } catch (IOException e2) {
                return false;
            }
        }
        return text.contains("@Controller") || text.contains("@RestController");
    }
}
