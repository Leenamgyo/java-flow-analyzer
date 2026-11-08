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
        for (var r : custom) {
            for (var m : r.methods) {
                if (m.nameArgIndex != null) {
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
        assignedVariable(call, p);
        return List.of(register(p));
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
        for (Rule r : rules) {
            if (!r.method().equals(call.getNameAsString())) {
                continue;
            }
            if (r.nameArgIndex() >= 0 && call.getArguments().size() <= r.nameArgIndex()) {
                continue;
            }
            if (r.className() == null) {
                return r; // 클래스 무관 규칙
            }
            for (String tn : typeNames) {
                if (tn.equals(r.className()) || TypeNames.simple(tn).equals(r.className())) {
                    return r;
                }
            }
        }
        return null;
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
            if (p.type == null) {
                p.type = v.getType().asString();
            }
        } else if (parent instanceof AssignExpr a && a.getValue() == cur) {
            p.variable = a.getTarget().toString();
        }
    }
}
