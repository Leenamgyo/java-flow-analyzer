package analyzer.resolver;

import analyzer.model.Origin;
import analyzer.model.ResolutionStatus;
import analyzer.parser.ProjectSources;
import analyzer.parser.TypeNames;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.List;
import java.util.Optional;

/**
 * 메서드 호출 대상 해석.
 * 1) JavaSymbolSolver 로 선언 대상 확정 시도
 * 2) 실패하면 선언부에 명시된 타입 + 이름/인자 수로 보수적으로 확인 (후보가 2개 이상이면 AMBIGUOUS)
 * 런타임 구현체(인터페이스 구현 클래스)는 추측하지 않는다.
 */
public final class CallResolver {
    private final ProjectSources sources;
    private final TypeLookup types;

    public CallResolver(ProjectSources sources, TypeLookup types) {
        this.sources = sources;
        this.types = types;
    }

    public CallTarget resolve(MethodCallExpr call, TypeDeclaration<?> callerType) {
        String solverError;
        try {
            ResolvedMethodDeclaration rmd = call.resolve();
            CallTarget t = fromSolver(call, rmd);
            if (t != null) {
                return t;
            }
            solverError = "symbol solver result could not be mapped";
        } catch (RuntimeException | StackOverflowError e) {
            solverError = e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
        }
        return fallback(call, callerType, solverError);
    }

    private CallTarget fromSolver(MethodCallExpr call, ResolvedMethodDeclaration rmd) {
        String declaring = rmd.declaringType().getQualifiedName();
        String receiver = receiverTypeViaSolver(call).orElse(declaring);
        Optional<Node> ast = rmd.toAst();
        if (ast.isPresent() && sources.isInSource(ast.get())) {
            Optional<CallableDeclaration<?>> own = sources.mapToOwnAst(ast.get());
            if (own.isPresent() && own.get() instanceof MethodDeclaration md) {
                return new CallTarget(ResolutionStatus.RESOLVED, "SYMBOL_SOLVER", null, Origin.SOURCE,
                        declaring, receiver, md, null, List.of());
            }
            return null;
        }
        return new CallTarget(ResolutionStatus.RESOLVED, "SYMBOL_SOLVER", null, Origin.LIBRARY,
                declaring, receiver, null, rmd, List.of());
    }

    private Optional<String> receiverTypeViaSolver(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TypeNames.erasure(call.getScope().get().calculateResolvedType().describe()));
        } catch (RuntimeException | StackOverflowError e) {
            // 정적 호출(타입명 스코프)은 타입 계산이 실패할 수 있다
            Expression scope = call.getScope().get();
            if (scope instanceof NameExpr n) {
                return types.resolveTypeName(n.getNameAsString(), call).map(TypeRef::displayName);
            }
            return Optional.empty();
        }
    }

    private CallTarget fallback(MethodCallExpr call, TypeDeclaration<?> callerType, String solverError) {
        String name = call.getNameAsString();
        int argc = call.getArguments().size();
        Optional<Expression> scope = call.getScope();

        if (scope.isEmpty() || scope.get() instanceof ThisExpr) {
            return inType(callerType, name, argc, "IMPLICIT_THIS", solverError);
        }
        Expression s = scope.get();
        if (s instanceof SuperExpr) {
            for (ClassOrInterfaceType st : TypeLookup.supertypeRefs(callerType)) {
                Optional<TypeRef> ref = types.resolveTypeName(st.getNameWithScope(), callerType);
                if (ref.isPresent() && ref.get().inSource()) {
                    CallTarget t = inType(ref.get().declaration(), name, argc, "SUPER", solverError);
                    if (t.status() != ResolutionStatus.UNRESOLVED) {
                        return t;
                    }
                }
            }
            return CallTarget.unresolved("SUPER", "super 메서드를 소스에서 찾지 못함; solver: " + solverError);
        }
        if (s instanceof com.github.javaparser.ast.expr.StringLiteralExpr
                || s instanceof com.github.javaparser.ast.expr.TextBlockLiteralExpr) {
            // 문자열 리터럴의 타입은 언어 규칙상 항상 java.lang.String
            return onType(new TypeRef("java.lang.String", "String", null, Origin.LIBRARY, "STRING_LITERAL"),
                    name, argc, "LITERAL_TYPE", solverError);
        }
        String varName = null;
        if (s instanceof NameExpr n) {
            varName = n.getNameAsString();
        } else if (s instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
            varName = f.getNameAsString();
        }
        if (varName != null) {
            Optional<Type> declared = s instanceof NameExpr
                    ? types.findVariableType(varName, call)
                    : types.findFieldType(callerType, varName, new java.util.HashSet<>());
            if (declared.isPresent()) {
                Type t = declared.get();
                if (t.isVarType() || t.isUnknownType()) {
                    return CallTarget.unresolved("DECLARED_TYPE",
                            "변수 '" + varName + "' 의 타입이 추론형(var/람다)이라 확인 불가; solver: " + solverError);
                }
                Optional<TypeRef> ref = types.resolveTypeName(t.asString(), call);
                if (ref.isEmpty()) {
                    return CallTarget.unresolved("DECLARED_TYPE",
                            "선언 타입 '" + t.asString() + "' 을 확인할 수 없음(제네릭 타입 변수/배열 등); solver: " + solverError);
                }
                return onType(ref.get(), name, argc, "DECLARED_TYPE", solverError);
            }
            if (s instanceof NameExpr && Character.isUpperCase(varName.charAt(0))) {
                Optional<TypeRef> ref = types.resolveTypeName(varName, call);
                if (ref.isPresent()) {
                    return onType(ref.get(), name, argc, "STATIC_TYPE", solverError);
                }
            }
            return CallTarget.unresolved("DECLARED_TYPE", "'" + varName + "' 의 선언을 찾지 못함; solver: " + solverError);
        }
        if (s instanceof FieldAccessExpr f) {
            Optional<TypeRef> ref = types.resolveTypeName(f.toString(), call);
            if (ref.isPresent()) {
                return onType(ref.get(), name, argc, "STATIC_TYPE", solverError);
            }
        }
        return CallTarget.unresolved("NONE",
                "수신 표현식 타입을 정적으로 확정할 수 없음(" + s.getClass().getSimpleName() + "); solver: " + solverError);
    }

    private CallTarget onType(TypeRef ref, String name, int argc, String basis, String solverError) {
        if (ref.inSource()) {
            CallTarget t = inType(ref.declaration(), name, argc, basis, solverError);
            return new CallTarget(t.status(), t.basis(), t.reason(), t.origin(), t.declaringType(), ref.displayName(),
                    t.sourceDeclaration(), t.libraryDeclaration(), t.candidates());
        }
        String reason = "수신 타입 " + ref.displayName() + " 은 source-root 밖(" + ref.basis()
                + ") — 메서드 시그니처 미확정";
        return new CallTarget(ResolutionStatus.PARTIALLY_RESOLVED, basis, reason, ref.origin(),
                ref.displayName(), ref.displayName(), null, null, List.of());
    }

    private CallTarget inType(TypeDeclaration<?> type, String name, int argc, String basis, String solverError) {
        String typeName = type.getFullyQualifiedName().orElse(type.getNameAsString());
        TypeLookup.MethodSearch search = types.findMethods(type, name, argc);
        if (search.candidates.size() == 1) {
            MethodDeclaration md = search.candidates.get(0);
            String declaring = md.findAncestor(TypeDeclaration.class)
                    .<String>flatMap(td -> ((TypeDeclaration<?>) td).getFullyQualifiedName()).orElse(typeName);
            return new CallTarget(ResolutionStatus.RESOLVED, basis + "+NAME_ARITY",
                    "symbol solver 실패 → 선언 타입의 이름·인자 수 일치 후보 1개; solver: " + solverError,
                    Origin.SOURCE, declaring, typeName, md, null, List.of());
        }
        if (search.candidates.size() > 1) {
            List<String> cands = search.candidates.stream().map(m -> m.getDeclarationAsString(false, false, false)).toList();
            return new CallTarget(ResolutionStatus.AMBIGUOUS, basis + "+NAME_ARITY",
                    "이름·인자 수가 같은 오버로딩 후보가 여러 개; solver: " + solverError,
                    Origin.SOURCE, typeName, typeName, null, null, cands);
        }
        if (!search.externalSupertypes.isEmpty()) {
            return new CallTarget(ResolutionStatus.PARTIALLY_RESOLVED, basis,
                    "소스 계층에 없음 — 외부 상위 타입 " + search.externalSupertypes + " 에서 상속됐을 수 있음",
                    Origin.UNKNOWN, null, typeName, null, null, List.of());
        }
        return CallTarget.unresolved(basis, typeName + " 계층에서 '" + name + "'(" + argc + " args) 선언을 찾지 못함; solver: "
                + solverError);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String line = s.lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }
}
