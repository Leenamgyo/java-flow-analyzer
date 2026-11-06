package analyzer.resolver;

import analyzer.parser.ProjectSources;
import analyzer.parser.TypeNames;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 본문 없는 인터페이스/추상 메서드에 대해 source-root 안의 구현 클래스를 찾는다.
 * 구체(non-abstract) 구현 클래스가 정확히 1개이고 그 클래스(또는 source 상위 클래스)에 같은 시그니처의
 * 본문 있는 메서드가 있을 때만 연결한다. 그 외(0개, 2개 이상, 계층 판정 불가, 익명 클래스 구현 존재)는 연결하지 않는다.
 * 이는 "source-root 내 유일 구현"이라는 정적 사실이며 런타임 DI 구현체를 확정한 것이 아니다.
 */
public final class ImplementationFinder {

    public static final String SINGLE = "SINGLE_SOURCE_IMPLEMENTATION";
    public static final String NONE = "NO_SOURCE_IMPLEMENTATION";
    public static final String MULTIPLE = "MULTIPLE_SOURCE_IMPLEMENTATIONS";
    public static final String HIERARCHY_UNRESOLVED = "HIERARCHY_UNRESOLVED";
    public static final String METHOD_NOT_MATCHED = "IMPLEMENTATION_METHOD_NOT_MATCHED";

    /**
     * @param basis       위 상수 중 하나
     * @param candidates  발견한 구체 구현 클래스(FQN) 및 판정 불가 클래스 표시
     * @param method      연결할 구현 메서드 (basis=SINGLE 일 때만 존재)
     */
    public record Result(String basis, String reason, List<String> candidates, Optional<MethodDeclaration> method) {
    }

    private final ProjectSources sources;
    private final Map<String, Result> cache = new HashMap<>();

    public ImplementationFinder(ProjectSources sources) {
        this.sources = sources;
    }

    public Result find(MethodDeclaration abstractMethod) {
        TypeDeclaration<?> owner = abstractMethod.findAncestor(TypeDeclaration.class)
                .map(t -> (TypeDeclaration<?>) t).orElseThrow();
        String ownerFqn = owner.getFullyQualifiedName().orElse(owner.getNameAsString());
        List<String> erased = erasedParams(abstractMethod);
        String key = ownerFqn + "#" + abstractMethod.getNameAsString() + erased;
        return cache.computeIfAbsent(key, k -> compute(owner, ownerFqn, abstractMethod.getNameAsString(), erased));
    }

    private Result compute(TypeDeclaration<?> owner, String ownerFqn, String name, List<String> erased) {
        String simple = owner.getNameAsString();
        List<ClassOrInterfaceDeclaration> concrete = new ArrayList<>();
        List<String> undetermined = new ArrayList<>();
        List<String> anonymous = new ArrayList<>();

        for (Path file : sources.allFiles()) {
            if (!mentions(file, simple)) {
                continue;
            }
            Optional<CompilationUnit> cu = sources.parse(file);
            if (cu.isEmpty()) {
                continue;
            }
            for (ClassOrInterfaceDeclaration ci : cu.get().findAll(ClassOrInterfaceDeclaration.class)) {
                if (ci.isInterface() || ci == owner) {
                    continue;
                }
                Boolean sub = isSubtype(ci, ownerFqn, simple);
                String fqn = ci.getFullyQualifiedName().orElse(ci.getNameAsString());
                if (sub == null) {
                    undetermined.add(fqn);
                } else if (sub && !ci.isAbstract()) {
                    concrete.add(ci);
                }
            }
            for (ObjectCreationExpr oc : cu.get().findAll(ObjectCreationExpr.class)) {
                if (oc.getAnonymousClassBody().isPresent() && oc.getType().getNameAsString().equals(simple)) {
                    anonymous.add("<anonymous>@" + sources.relativeFile(oc) + ":"
                            + oc.getBegin().map(p -> p.line).orElse(0));
                }
            }
        }

        List<String> candidates = new ArrayList<>(concrete.stream()
                .map(c -> c.getFullyQualifiedName().orElse(c.getNameAsString())).toList());
        candidates.addAll(anonymous);
        undetermined.forEach(u -> candidates.add(u + " (계층 판정 불가)"));
        String note = " — 람다/메서드 참조 구현과 source-root 밖 구현은 탐색하지 않으며, 런타임 DI 구현체를 확정한 것이 아님";

        if (!undetermined.isEmpty()) {
            return new Result(HIERARCHY_UNRESOLVED, "상위 타입을 판정할 수 없는 클래스가 있어 연결하지 않음: "
                    + undetermined, candidates, Optional.empty());
        }
        if (concrete.size() + anonymous.size() == 0) {
            return new Result(NONE, "source-root 에 " + simple + " 의 구체 구현 클래스가 없음", candidates, Optional.empty());
        }
        if (concrete.size() + anonymous.size() > 1) {
            return new Result(MULTIPLE, "source-root 에 " + simple + " 구현이 " + candidates.size()
                    + "개라 연결하지 않음", candidates, Optional.empty());
        }
        if (concrete.isEmpty()) {
            return new Result(MULTIPLE, "익명 클래스 구현만 있어 연결하지 않음", candidates, Optional.empty());
        }
        ClassOrInterfaceDeclaration impl = concrete.get(0);
        Optional<MethodDeclaration> m = findMethod(impl, name, erased, new HashSet<>());
        if (m.isEmpty()) {
            return new Result(METHOD_NOT_MATCHED, impl.getNameAsString() + " 에서 " + name + erased
                    + " 와 파라미터 타입이 같은 본문 있는 메서드를 찾지 못함", candidates, Optional.empty());
        }
        String where = m.get().findAncestor(TypeDeclaration.class).map(t -> ((TypeDeclaration<?>) t).getNameAsString()).orElse("?");
        return new Result(SINGLE, "source-root 내 " + simple + " 의 구체 구현 클래스가 " + impl.getNameAsString()
                + " 1개뿐" + (where.equals(impl.getNameAsString()) ? "" : " (메서드는 상위 클래스 " + where + " 에 선언)")
                + note, candidates, m);
    }

    /** @return true/false, 판정 불가면 null */
    private Boolean isSubtype(ClassOrInterfaceDeclaration ci, String ownerFqn, String ownerSimple) {
        boolean namesOwner = ci.getExtendedTypes().stream().anyMatch(t -> mentionsType(t, ownerSimple))
                || ci.getImplementedTypes().stream().anyMatch(t -> mentionsType(t, ownerSimple));
        try {
            ResolvedReferenceTypeDeclaration rd = ci.resolve();
            for (ResolvedReferenceType a : rd.getAllAncestors()) {
                if (a.getQualifiedName().equals(ownerFqn)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            // 계층 해석 실패: 직접 상위 타입에 같은 단순명이 보이면 판정 불가, 아니면 무관한 클래스로 본다.
            return namesOwner ? null : false;
        }
    }

    private static boolean mentionsType(ClassOrInterfaceType t, String simple) {
        return t.getNameAsString().equals(simple);
    }

    private Optional<MethodDeclaration> findMethod(ClassOrInterfaceDeclaration cls, String name, List<String> erased,
                                                   Set<String> visited) {
        if (!visited.add(cls.getFullyQualifiedName().orElse(cls.getNameAsString()))) {
            return Optional.empty();
        }
        for (MethodDeclaration md : cls.getMethodsByName(name)) {
            if (md.getBody().isPresent() && !md.isStatic() && erasedParams(md).equals(erased)) {
                return Optional.of(md);
            }
        }
        for (ClassOrInterfaceType ext : cls.getExtendedTypes()) {
            String fqn;
            try {
                fqn = ext.resolve().asReferenceType().getQualifiedName();
            } catch (RuntimeException e) {
                return Optional.empty();
            }
            Optional<TypeDeclaration<?>> sup = sources.findTypeByQualifiedName(fqn);
            if (sup.isPresent() && sup.get() instanceof ClassOrInterfaceDeclaration sc && !sc.isInterface()) {
                return findMethod(sc, name, erased, visited);
            }
        }
        return Optional.empty();
    }

    private static List<String> erasedParams(MethodDeclaration md) {
        return md.getParameters().stream().map(ImplementationFinder::paramType).map(TypeNames::erasure).toList();
    }

    private static String paramType(Parameter p) {
        return p.getType().asString() + (p.isVarArgs() ? "..." : "");
    }

    private static boolean mentions(Path file, String simpleName) {
        try {
            return Files.readString(file).contains(simpleName);
        } catch (IOException e) {
            return false;
        }
    }
}
