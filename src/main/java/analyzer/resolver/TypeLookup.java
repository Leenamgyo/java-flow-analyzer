package analyzer.resolver;

import analyzer.model.Origin;
import analyzer.parser.ProjectSources;
import analyzer.parser.TypeNames;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 심볼 솔버 실패 시 사용하는 보수적 타입 조회. 선언부(변수/필드/파라미터 타입, import)에
 * 명시된 정보만 사용하며 추측하지 않는다.
 */
public final class TypeLookup {
    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

    private final ProjectSources sources;

    public TypeLookup(ProjectSources sources) {
        this.sources = sources;
    }

    /** 문맥(파일의 import/패키지) 기준으로 타입명을 확인한다. */
    public Optional<TypeRef> resolveTypeName(String typeText, Node context) {
        String t = TypeNames.erasure(typeText);
        if (t.endsWith("[]") || t.endsWith("...") || PRIMITIVES.contains(t) || t.isEmpty()) {
            return Optional.empty();
        }
        Optional<CompilationUnit> cuOpt = context.findCompilationUnit();
        if (cuOpt.isEmpty()) {
            return Optional.empty();
        }
        CompilationUnit cu = cuOpt.get();
        if (t.contains(".")) {
            Optional<TypeDeclaration<?>> src = sources.findTypeByQualifiedName(t);
            if (src.isPresent()) {
                return Optional.of(new TypeRef(t, TypeNames.simple(t), src.get(), Origin.SOURCE, "QUALIFIED_NAME"));
            }
            // Outer.Inner 형태
            String first = t.substring(0, t.indexOf('.'));
            if (Character.isUpperCase(first.charAt(0))) {
                Optional<TypeRef> outer = resolveTypeName(first, context);
                if (outer.isPresent() && outer.get().inSource()) {
                    String nested = outer.get().qualifiedName() + t.substring(t.indexOf('.'));
                    return sources.findTypeByQualifiedName(nested)
                            .map(d -> new TypeRef(nested, TypeNames.simple(nested), d, Origin.SOURCE, "NESTED_TYPE"));
                }
                return Optional.empty();
            }
            return Optional.of(new TypeRef(t, TypeNames.simple(t), null, Origin.LIBRARY, "QUALIFIED_NAME"));
        }
        // 1) 같은 파일 내 선언 타입(중첩 포함)
        for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
            if (td.getNameAsString().equals(t)) {
                return Optional.of(new TypeRef(td.getFullyQualifiedName().orElse(t), t, td, Origin.SOURCE, "SAME_FILE"));
            }
        }
        // 2) 단일 타입 import
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic() && imp.getName().getIdentifier().equals(t)) {
                String fqn = imp.getNameAsString();
                Optional<TypeDeclaration<?>> src = sources.findTypeByQualifiedName(fqn);
                return Optional.of(src.map(d -> new TypeRef(fqn, t, d, Origin.SOURCE, "IMPORT"))
                        .orElseGet(() -> new TypeRef(fqn, t, null, Origin.LIBRARY, "IMPORT")));
            }
        }
        // 3) 같은 패키지
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String samePkg = pkg.isEmpty() ? t : pkg + "." + t;
        Optional<TypeDeclaration<?>> same = sources.findTypeByQualifiedName(samePkg);
        if (same.isPresent()) {
            return Optional.of(new TypeRef(samePkg, t, same.get(), Origin.SOURCE, "SAME_PACKAGE"));
        }
        // 4) 와일드카드 import 중 source-root 에 있는 것
        List<String> wildcard = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() && !imp.isStatic()) {
                String fqn = imp.getNameAsString() + "." + t;
                wildcard.add(fqn);
                Optional<TypeDeclaration<?>> src = sources.findTypeByQualifiedName(fqn);
                if (src.isPresent()) {
                    return Optional.of(new TypeRef(fqn, t, src.get(), Origin.SOURCE, "WILDCARD_IMPORT"));
                }
            }
        }
        // 5) java.lang (부트스트랩 클래스로더 = JRE 만 확인)
        try {
            Class.forName("java.lang." + t, false, null);
            return Optional.of(new TypeRef("java.lang." + t, t, null, Origin.LIBRARY, "JAVA_LANG"));
        } catch (ClassNotFoundException | LinkageError ignored) {
            // not java.lang
        }
        // 6) source-root 에 없는 와일드카드 import 후보 → 출처 미확정
        if (!wildcard.isEmpty()) {
            return Optional.of(new TypeRef(null, t, null, Origin.UNKNOWN, "WILDCARD_IMPORT_NOT_IN_SOURCE"));
        }
        return Optional.empty();
    }

    /**
     * 이름으로 지역 변수/파라미터/필드의 선언 타입을 찾는다. 없으면 empty.
     * 반환된 Type 이 var/암묵적 람다 파라미터면 호출자가 미해결로 처리해야 한다.
     */
    public Optional<Type> findVariableType(String name, Node at) {
        Node child = at;
        Node cur = at.getParentNode().orElse(null);
        while (cur != null) {
            Optional<Type> found = Optional.empty();
            if (cur instanceof LambdaExpr l) {
                found = paramType(l.getParameters(), name);
            } else if (cur instanceof CatchClause c) {
                found = c.getParameter().getNameAsString().equals(name) ? Optional.of(c.getParameter().getType()) : Optional.empty();
            } else if (cur instanceof ForEachStmt f) {
                found = declaratorType(f.getVariable().getVariables(), name);
            } else if (cur instanceof ForStmt f) {
                for (Expression e : f.getInitialization()) {
                    if (e instanceof VariableDeclarationExpr v && found.isEmpty()) {
                        found = declaratorType(v.getVariables(), name);
                    }
                }
            } else if (cur instanceof TryStmt t) {
                for (Expression e : t.getResources()) {
                    if (e instanceof VariableDeclarationExpr v && found.isEmpty()) {
                        found = declaratorType(v.getVariables(), name);
                    }
                }
            } else if (cur instanceof BlockStmt b) {
                found = precedingLocal(b.getStatements(), child, name);
            } else if (cur instanceof SwitchEntry e) {
                found = precedingLocal(e.getStatements(), child, name);
            } else if (cur instanceof CallableDeclaration<?> cd) {
                found = paramType(cd.getParameters(), name);
            } else if (cur instanceof TypeDeclaration<?> td) {
                found = findFieldType(td, name, new HashSet<>());
            }
            if (found.isPresent()) {
                return found;
            }
            child = cur;
            cur = cur.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    public Optional<Type> findFieldType(TypeDeclaration<?> td, String name, Set<String> visited) {
        if (!visited.add(td.getFullyQualifiedName().orElse(td.getNameAsString()))) {
            return Optional.empty();
        }
        var field = td.getFieldByName(name);
        if (field.isPresent()) {
            return declaratorType(field.get().getVariables(), name);
        }
        for (TypeDeclaration<?> sup : sourceSupertypes(td)) {
            Optional<Type> t = findFieldType(sup, name, visited);
            if (t.isPresent()) {
                return t;
            }
        }
        return Optional.empty();
    }

    /** 클래스 계층(소스에 있는 범위)에서 이름·인자 수가 맞는 메서드 후보를 찾는다. */
    public MethodSearch findMethods(TypeDeclaration<?> type, String name, int argc) {
        MethodSearch search = new MethodSearch();
        collect(type, name, argc, new HashSet<>(), search);
        return search;
    }

    private void collect(TypeDeclaration<?> t, String name, int argc, Set<String> visited, MethodSearch out) {
        if (!visited.add(t.getFullyQualifiedName().orElse(t.getNameAsString()))) {
            return;
        }
        List<MethodDeclaration> own = t.getMethodsByName(name).stream().filter(m -> arityMatches(m, argc)).toList();
        if (!own.isEmpty()) {
            out.candidates.addAll(own);
            return;
        }
        for (ClassOrInterfaceType st : supertypeRefs(t)) {
            Optional<TypeRef> ref = resolveTypeName(st.getNameWithScope(), t);
            if (ref.isPresent() && ref.get().inSource()) {
                collect(ref.get().declaration(), name, argc, visited, out);
            } else {
                out.externalSupertypes.add(ref.map(TypeRef::displayName).orElse(st.getNameWithScope()));
            }
        }
    }

    public List<TypeDeclaration<?>> sourceSupertypes(TypeDeclaration<?> t) {
        List<TypeDeclaration<?>> result = new ArrayList<>();
        for (ClassOrInterfaceType st : supertypeRefs(t)) {
            resolveTypeName(st.getNameWithScope(), t).filter(TypeRef::inSource).ifPresent(r -> result.add(r.declaration()));
        }
        return result;
    }

    public static List<ClassOrInterfaceType> supertypeRefs(TypeDeclaration<?> t) {
        List<ClassOrInterfaceType> refs = new ArrayList<>();
        if (t instanceof ClassOrInterfaceDeclaration c) {
            refs.addAll(c.getExtendedTypes());
            refs.addAll(c.getImplementedTypes());
        } else if (t instanceof EnumDeclaration e) {
            refs.addAll(e.getImplementedTypes());
        } else if (t instanceof RecordDeclaration r) {
            refs.addAll(r.getImplementedTypes());
        }
        return refs;
    }

    public static boolean arityMatches(MethodDeclaration m, int argc) {
        int n = m.getParameters().size();
        if (n == argc) {
            return true;
        }
        return n > 0 && m.getParameter(n - 1).isVarArgs() && argc >= n - 1;
    }

    private static Optional<Type> paramType(NodeList<Parameter> params, String name) {
        return params.stream().filter(p -> p.getNameAsString().equals(name)).map(Parameter::getType).findFirst();
    }

    private static Optional<Type> declaratorType(NodeList<VariableDeclarator> vars, String name) {
        return vars.stream().filter(v -> v.getNameAsString().equals(name)).map(VariableDeclarator::getType).findFirst();
    }

    private static Optional<Type> precedingLocal(NodeList<Statement> stmts, Node child, String name) {
        Optional<Type> last = Optional.empty();
        for (Statement s : stmts) {
            if (s == child) {
                break;
            }
            if (s instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr v) {
                Optional<Type> t = declaratorType(v.getVariables(), name);
                if (t.isPresent()) {
                    last = t;
                }
            }
        }
        // 현재 문장 자체가 선언문인 경우(예: String a = x.foo())는 같은 문장의 앞선 선언자만 해당
        if (last.isEmpty() && child instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr v) {
            last = declaratorType(v.getVariables(), name);
        }
        return last;
    }

    public static final class MethodSearch {
        public final List<MethodDeclaration> candidates = new ArrayList<>();
        public final List<String> externalSupertypes = new ArrayList<>();
    }
}
