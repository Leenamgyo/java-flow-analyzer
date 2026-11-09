package analyzer.parameter;

import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.MethodInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.SourceLocation;
import analyzer.parser.TypeNames;
import analyzer.resolver.CallTarget;
import analyzer.resolver.TypeLookup;
import analyzer.resolver.TypeRef;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Legacy(HttpServletRequest, 사용자 정의 RequestUtil) 및 Spring MVC 어노테이션 기반 요청 파라미터 추출.
 * 파라미터명을 정적으로 확인할 수 없으면 UNKNOWN, 전체 맵 조회는 개별 파라미터를 만들지 않는다.
 */
public final class RequestParameterExtractor {

    /**
     * className null = 클래스 무관(메서드명만). nameArgIndex: 0 이상 = 그 인자가 이름, -1 = 전체 조회, FIXED = fixedName 사용
     */
    private record Rule(String className, String method, int nameArgIndex, Integer defaultArgIndex, String type,
                        String fixedName) {
        Rule(String className, String method, int nameArgIndex, Integer defaultArgIndex, String type) {
            this(className, method, nameArgIndex, defaultArgIndex, type, null);
        }
    }

    private static final int FIXED = -2;
    /** 기본값 래퍼 규칙: nameArgIndex 자리에 WRAP_BASE - (감싸는 인자 위치) */
    private static final int WRAP_BASE = -100;

    /** 호출식 → 그 호출이 만든 요청 파라미터 (래퍼가 안쪽 읽기를 찾을 때) */
    private final java.util.Map<MethodCallExpr, List<RequestParameterInfo>> produced = new java.util.IdentityHashMap<>();

    private static final Set<String> SPRING = Set.of("RequestParam", "PathVariable", "RequestHeader", "RequestBody",
            "ModelAttribute");

    private final List<Rule> rules = new ArrayList<>();
    private final AnalysisResult result;
    private final TypeLookup types;

    public RequestParameterExtractor(AnalysisResult result, TypeLookup types,
                                     List<AnalyzerConfig.RequestParameterRule> custom) {
        this.result = result;
        this.types = types;
        for (String req : List.of("HttpServletRequest", "ServletRequest")) {
            rules.add(new Rule(req, "getParameter", 0, null, "String"));
            rules.add(new Rule(req, "getParameterValues", 0, null, "String[]"));
            rules.add(new Rule(req, "getParameterMap", -1, null, "Map<String, String[]>"));
        }
        // 흔한 기본값 래퍼 (commons-lang): StringUtils.defaultIfEmpty(x, d) 등
        for (String w : List.of("defaultIfEmpty", "defaultIfBlank", "defaultString")) {
            rules.add(new Rule("StringUtils", w, WRAP_BASE, 1, null));
        }
        for (var r : custom) {
            for (var m : r.methods) {
                if (m.wraps != null) {
                    rules.add(new Rule(r.className, m.name, WRAP_BASE - m.wraps, m.defaultArgIndex, m.type));
                } else if (m.nameArgIndex != null) {
                    rules.add(new Rule(r.className, m.name, m.nameArgIndex, m.defaultArgIndex, m.type));
                } else {
                    String fixed = m.parameter != null ? m.parameter : nameFromMethod(m.name);
                    rules.add(new Rule(r.className, m.name, FIXED, m.defaultArgIndex, m.type, fixed));
                }
            }
        }
    }

    public List<RequestParameterInfo> fromCall(MethodCallExpr call, CallTarget target, MethodInfo source,
                                               TypeDeclaration<?> callerType, String file) {
        Rule rule = match(call, target);
        if (rule == null) {
            return List.of();
        }
        if (rule.nameArgIndex() <= WRAP_BASE) {
            return wrap(call, rule);
        }
        RequestParameterInfo p = new RequestParameterInfo();
        p.extraction = rule.className() == null ? rule.method() : TypeNames.simple(rule.className()) + "." + rule.method();
        p.sourceMethodId = source.id;
        p.location = SourceLocation.of(call, file);
        p.type = rule.type();
        if (rule.nameArgIndex() == FIXED) {
            p.name = rule.fixedName();
            p.nameResolution = "CONFIG"; // 설정 규칙의 고정 이름 (parameter 또는 메서드명)
            p.nameExpression = rule.method() + "()";
        } else if (rule.nameArgIndex() < 0) {
            p.name = null;
            p.nameResolution = "ALL_PARAMETERS";
        } else {
            Expression arg = call.getArgument(rule.nameArgIndex());
            p.nameExpression = arg.toString();
            resolveName(arg, callerType, call, p);
        }
        if (rule.defaultArgIndex() != null && call.getArguments().size() > rule.defaultArgIndex()) {
            p.defaultValue = call.getArgument(rule.defaultArgIndex()).toString();
        }
        p.readerLogic = target == null ? null : readerLogic(target.sourceDeclaration());
        assignedVariable(call, p);
        List<RequestParameterInfo> out = List.of(register(p));
        produced.put(call, out);
        return out;
    }

    /** 요청 읽기로 보는 호출 (단순 읽기 메서드 본문에 있어도 로직이 아님) */
    private static final Set<String> READ_CALLS = Set.of("getParameter", "getParameterValues", "getParameterMap",
            "getHeader", "getHeaders", "getAttribute");
    /** 값 그대로 기본값만 고르는 데 쓰는 호출 (null·빈 값 확인, 공백 제거) — 로직으로 보지 않는다 */
    private static final Set<String> TRIVIAL_CALLS = Set.of("isEmpty", "isBlank", "isNotEmpty", "isNotBlank", "hasText",
            "hasLength", "trim", "strip", "length", "equals", "equalsIgnoreCase", "toString", "valueOf",
            "defaultString", "defaultIfEmpty", "defaultIfBlank");

    /**
     * 읽기 메서드 본문에 요청 읽기·기본값 고르기 말고 로직이 있으면 근거 문자열, 없으면(단순 래퍼) null.
     * 본문을 모르면(라이브러리·추상) null — 추측하지 않는다. 로직: 반복문·try·throw·switch·객체 생성·필드 대입,
     * READ_CALLS·TRIVIAL_CALLS 가 아닌 호출. 지역 변수 선언·대입·if·삼항·return 은 값 고르기로 본다.
     */
    static String readerLogic(MethodDeclaration md) {
        if (md == null || md.getBody().isEmpty()) {
            return null;
        }
        java.util.LinkedHashSet<String> why = new java.util.LinkedHashSet<>();
        md.getBody().get().walk(n -> {
            if (n instanceof com.github.javaparser.ast.stmt.ForStmt || n instanceof com.github.javaparser.ast.stmt.ForEachStmt
                    || n instanceof com.github.javaparser.ast.stmt.WhileStmt || n instanceof com.github.javaparser.ast.stmt.DoStmt) {
                why.add("반복문");
            } else if (n instanceof com.github.javaparser.ast.stmt.TryStmt) {
                why.add("try");
            } else if (n instanceof com.github.javaparser.ast.stmt.ThrowStmt) {
                why.add("throw");
            } else if (n instanceof com.github.javaparser.ast.stmt.SwitchStmt || n instanceof com.github.javaparser.ast.expr.SwitchExpr) {
                why.add("switch");
            } else if (n instanceof com.github.javaparser.ast.expr.ObjectCreationExpr o) {
                why.add("new " + o.getType().getNameAsString());
            } else if (n instanceof AssignExpr a && !(a.getTarget() instanceof NameExpr t
                    && md.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(t.getNameAsString())).size()
                    + md.getParameters().stream().filter(x -> x.getNameAsString().equals(t.getNameAsString())).count() > 0)) {
                why.add("필드 대입 " + a.getTarget());
            } else if (n instanceof MethodCallExpr c && !READ_CALLS.contains(c.getNameAsString())
                    && !TRIVIAL_CALLS.contains(c.getNameAsString())) {
                why.add("호출 " + c.getScope().map(x -> x + ".").orElse("") + c.getNameAsString());
            }
        });
        return why.isEmpty() ? null : String.join(" · ", why);
    }

    /**
     * 기본값 래퍼: 감싸는 인자가 요청 읽기 호출이면 그 요청 파라미터를 래퍼 호출로 옮기고 기본값·받는 변수를 붙인다.
     * (안쪽 읽기는 인자 안 호출이 되어 흐름도에 따로 그려지지 않는다.) 감싸는 인자가 요청 읽기가 아니면 아무것도 하지 않는다.
     */
    private List<RequestParameterInfo> wrap(MethodCallExpr call, Rule rule) {
        int idx = WRAP_BASE - rule.nameArgIndex();
        if (call.getArguments().size() <= idx) {
            return List.of();
        }
        Expression arg = call.getArgument(idx);
        while (arg instanceof EnclosedExpr e) {
            arg = e.getInner();
        }
        while (arg instanceof CastExpr c) {
            arg = c.getExpression();
        }
        List<RequestParameterInfo> inner = arg instanceof MethodCallExpr mc ? produced.get(mc) : null;
        if (inner == null || inner.size() != 1) {
            return List.of();
        }
        RequestParameterInfo p = inner.get(0);
        if (p.defaultValue == null && rule.defaultArgIndex() != null && call.getArguments().size() > rule.defaultArgIndex()) {
            p.defaultValue = call.getArgument(rule.defaultArgIndex()).toString();
        }
        p.extraction = p.extraction + " → " + (rule.className() == null ? "" : TypeNames.simple(rule.className()) + ".") + rule.method();
        assignedVariable(call, p);
        if (rule.type() != null) {
            p.type = rule.type();
        }
        produced.put(call, inner);
        return inner;
    }

    public List<RequestParameterInfo> fromAnnotations(MethodDeclaration md, MethodInfo m, String file) {
        List<RequestParameterInfo> out = new ArrayList<>();
        for (Parameter param : md.getParameters()) {
            for (AnnotationExpr a : param.getAnnotations()) {
                String an = TypeNames.simple(a.getNameAsString());
                if (!SPRING.contains(an)) {
                    continue;
                }
                RequestParameterInfo p = new RequestParameterInfo();
                p.extraction = "@" + an;
                p.sourceMethodId = m.id;
                p.variable = param.getNameAsString();
                p.type = param.getType().asString();
                p.location = SourceLocation.of(param, file);
                Expression nameExpr = null;
                if (a instanceof SingleMemberAnnotationExpr s) {
                    nameExpr = s.getMemberValue();
                } else if (a instanceof NormalAnnotationExpr n) {
                    for (MemberValuePair pair : n.getPairs()) {
                        switch (pair.getNameAsString()) {
                            case "value", "name" -> nameExpr = pair.getValue();
                            case "required" -> p.required = pair.getValue() instanceof BooleanLiteralExpr b ? b.getValue() : null;
                            case "defaultValue" -> p.defaultValue = pair.getValue().toString();
                            default -> {
                            }
                        }
                    }
                }
                if (an.equals("RequestBody")) {
                    p.name = null;
                    p.nameResolution = "REQUEST_BODY";
                } else if (nameExpr instanceof StringLiteralExpr lit) {
                    p.name = lit.asString();
                    p.nameResolution = "ANNOTATION";
                } else if (nameExpr != null) {
                    p.nameExpression = nameExpr.toString();
                    resolveName(nameExpr, md.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t).orElse(null), md, p);
                } else {
                    p.name = param.getNameAsString();
                    p.nameResolution = "PARAMETER_NAME";
                }
                if (p.required == null && !an.equals("ModelAttribute")) {
                    p.required = true; // Spring 기본값
                }
                out.add(register(p));
            }
        }
        return out;
    }

    private RequestParameterInfo register(RequestParameterInfo p) {
        p.id = result.nextId("P");
        result.parameters.add(p);
        result.index(p.id, p);
        return p;
    }

    private Rule match(MethodCallExpr call, CallTarget target) {
        List<String> typeNames = new ArrayList<>();
        if (target.receiverType() != null) {
            typeNames.add(TypeNames.erasure(target.receiverType()));
        }
        if (target.declaringType() != null) {
            typeNames.add(TypeNames.erasure(target.declaringType()));
        }
        // 인자 수가 다른 오버로드(getParameter(req, name) / getParameter(req, name, def))를 한 규칙으로도, 따로 적어도
        // 오류 없이: 이름 인자가 없는 호출엔 규칙을 쓰지 않고, 기본값 인자는 있을 때만 읽는다.
        // 같은 메서드 규칙이 여럿이면 기본값 위치까지 인자 수에 맞는 규칙을 먼저 고른다.
        Rule fallback = null;
        int n = call.getArguments().size();
        for (Rule r : rules) {
            if (!r.method().equals(call.getNameAsString())) {
                continue;
            }
            if (r.nameArgIndex() > WRAP_BASE && r.nameArgIndex() >= 0 && n <= r.nameArgIndex()) {
                continue;
            }
            boolean typeOk = r.className() == null; // 클래스 무관 규칙
            for (String tn : typeNames) {
                if (tn.equals(r.className()) || TypeNames.simple(tn).equals(r.className())) {
                    typeOk = true;
                }
            }
            if (!typeOk) {
                continue;
            }
            if (r.defaultArgIndex() == null || r.defaultArgIndex() < n) {
                if (fallback == null || fallback.defaultArgIndex() == null && r.defaultArgIndex() != null) {
                    fallback = r; // 인자 수에 맞는 규칙 (기본값까지 쓰는 규칙 우선)
                }
            } else if (fallback == null) {
                fallback = r;     // 기본값 인자가 없는 호출 — 기본값 없이 적용
            }
        }
        return fallback;
    }

    /** getSystemId → systemId, getDeviceAbiType → deviceAbiType, systemId → systemId */
    static String nameFromMethod(String method) {
        String n = method.matches("(get|read|fetch)[A-Z].*") ? method.replaceFirst("^(get|read|fetch)", "") : method;
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }

    /** 문자열 리터럴 또는 static final String 상수(같은 클래스/상위/다른 source 타입) 값. Map 키 해석에도 사용 */
    public Optional<String> stringValue(Expression arg, TypeDeclaration<?> callerType, Node context) {
        if (arg instanceof StringLiteralExpr s) {
            return Optional.of(s.asString());
        }
        if (arg instanceof NameExpr n && callerType != null) {
            return constantValue(callerType, n.getNameAsString(), new HashSet<>());
        }
        if (arg instanceof FieldAccessExpr f) {
            return types.resolveTypeName(f.getScope().toString(), context)
                    .filter(TypeRef::inSource)
                    .flatMap(ref -> constantValue(ref.declaration(), f.getNameAsString(), new HashSet<>()));
        }
        return Optional.empty();
    }

    private void resolveName(Expression arg, TypeDeclaration<?> callerType, Node context, RequestParameterInfo p) {
        if (arg instanceof StringLiteralExpr s) {
            p.name = s.asString();
            p.nameResolution = "LITERAL";
            return;
        }
        Optional<String> constant = Optional.empty();
        if (arg instanceof NameExpr n && callerType != null) {
            constant = constantValue(callerType, n.getNameAsString(), new HashSet<>());
        } else if (arg instanceof FieldAccessExpr f) {
            constant = types.resolveTypeName(f.getScope().toString(), context)
                    .filter(TypeRef::inSource)
                    .flatMap(ref -> constantValue(ref.declaration(), f.getNameAsString(), new HashSet<>()));
        }
        if (constant.isPresent()) {
            p.name = constant.get();
            p.nameResolution = "CONSTANT";
        } else {
            p.name = "UNKNOWN";
            p.nameResolution = "UNKNOWN";
        }
    }

    /** static final String 상수(문자열 리터럴 초기화)만 값으로 인정한다. */
    private Optional<String> constantValue(TypeDeclaration<?> type, String field, Set<TypeDeclaration<?>> visited) {
        if (!visited.add(type)) {
            return Optional.empty();
        }
        Optional<FieldDeclaration> fd = type.getFieldByName(field);
        if (fd.isPresent()) {
            boolean iface = type instanceof ClassOrInterfaceDeclaration c && c.isInterface();
            if (!iface && !(fd.get().isStatic() && fd.get().isFinal())) {
                return Optional.empty();
            }
            return fd.get().getVariables().stream()
                    .filter(v -> v.getNameAsString().equals(field))
                    .findFirst()
                    .flatMap(VariableDeclarator::getInitializer)
                    .filter(Expression::isStringLiteralExpr)
                    .map(e -> e.asStringLiteralExpr().asString());
        }
        for (TypeDeclaration<?> sup : types.sourceSupertypes(type)) {
            Optional<String> v = constantValue(sup, field, visited);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    private static void assignedVariable(MethodCallExpr call, RequestParameterInfo p) {
        Node cur = call;
        Node parent = cur.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            cur = parent;
            parent = cur.getParentNode().orElse(null);
        }
        if (parent instanceof VariableDeclarator v) {
            p.variable = v.getNameAsString();
            p.variableType = v.getTypeAsString();
            if (p.type == null) {
                p.type = v.getType().asString();
            }
        } else if (parent instanceof AssignExpr a && a.getValue() == cur) {
            p.variable = a.getTarget().toString();
        }
    }
}
