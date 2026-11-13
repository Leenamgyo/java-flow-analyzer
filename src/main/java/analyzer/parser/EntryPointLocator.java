package analyzer.parser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.List;
import java.util.Set;

/** 분석 대상 클래스와 진입 메서드를 식별한다. 후보가 여러 개면 임의로 고르지 않고 실패한다. */
public final class EntryPointLocator {
    public static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    public record EntryPoint(TypeDeclaration<?> type, MethodDeclaration method, String selection) {
    }

    private EntryPointLocator() {
    }

    public static TypeDeclaration<?> locateType(ProjectSources sources, String className) {
        List<TypeDeclaration<?>> candidates;
        if (className.contains(".")) {
            candidates = sources.findTypeByQualifiedName(className).<List<TypeDeclaration<?>>>map(List::of)
                    .orElse(List.of());
        } else {
            candidates = sources.findTypesBySimpleName(className);
        }
        if (candidates.isEmpty()) {
            throw new AnalysisException("대상 클래스를 source-root 에서 찾을 수 없습니다: " + className);
        }
        if (candidates.size() > 1) {
            throw new AnalysisException("대상 클래스 후보가 여러 개입니다. 정규화된 이름(FQN)으로 지정하세요: "
                    + candidates.stream().map(t -> t.getFullyQualifiedName().orElse(t.getNameAsString())).toList());
        }
        return candidates.get(0);
    }

    public static EntryPoint locate(ProjectSources sources, String className, String methodName,
                                    List<String> parameterTypes) {
        TypeDeclaration<?> type = locateType(sources, className);
        if (methodName != null && !methodName.isBlank()) {
            List<MethodDeclaration> byName = type.getMethodsByName(methodName);
            if (byName.isEmpty()) {
                throw new AnalysisException("진입 메서드를 찾을 수 없습니다: " + type.getNameAsString() + "." + methodName);
            }
            if (parameterTypes != null) {
                List<MethodDeclaration> matched = byName.stream().filter(m -> matches(m, parameterTypes)).toList();
                if (matched.size() == 1) {
                    return new EntryPoint(type, matched.get(0), "EXPLICIT_WITH_PARAMETER_TYPES");
                }
                throw new AnalysisException("entry-method-parameter-types " + parameterTypes
                        + " 와 일치하는 메서드가 " + matched.size() + "개입니다. 후보: " + signatures(byName));
            }
            if (byName.size() > 1) {
                throw new AnalysisException("진입 메서드 '" + methodName + "' 가 오버로딩되어 있습니다. "
                        + "target.entry-method-parameter-types 로 지정하세요. 후보: " + signatures(byName));
            }
            return new EntryPoint(type, byName.get(0), "EXPLICIT");
        }
        // 진입 메서드 미지정: 프레임워크 매핑 어노테이션 → Struts 스타일 execute 순으로 확인
        List<MethodDeclaration> mapped = type.getMethods().stream()
                .filter(m -> m.getAnnotations().stream().anyMatch(a -> MAPPING_ANNOTATIONS.contains(a.getNameAsString())))
                .toList();
        if (mapped.size() == 1) {
            return new EntryPoint(type, mapped.get(0), "INFERRED_FRAMEWORK_MAPPING");
        }
        if (mapped.size() > 1) {
            throw new AnalysisException("매핑 어노테이션이 있는 메서드가 여러 개입니다. target.entry-method 를 지정하세요: "
                    + signatures(mapped));
        }
        List<MethodDeclaration> execute = type.getMethodsByName("execute").stream()
                .filter(MethodDeclaration::isPublic).toList();
        if (execute.size() == 1) {
            return new EntryPoint(type, execute.get(0), "INFERRED_ACTION_EXECUTE");
        }
        throw new AnalysisException("진입 메서드를 확정할 수 없습니다. target.entry-method 를 지정하세요. 후보: "
                + signatures(type.getMethods()));
    }

    private static boolean matches(MethodDeclaration m, List<String> types) {
        if (m.getParameters().size() != types.size()) {
            return false;
        }
        for (int i = 0; i < types.size(); i++) {
            Parameter p = m.getParameter(i);
            String declared = TypeNames.erasure(p.getType().asString()) + (p.isVarArgs() ? "..." : "");
            String wanted = TypeNames.erasure(types.get(i).trim());
            if (!declared.equals(wanted) && !TypeNames.simple(declared).equals(TypeNames.simple(wanted))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> signatures(List<MethodDeclaration> methods) {
        return methods.stream().map(m -> m.getDeclarationAsString(false, false, false)).toList();
    }
}
