package analyzer.binding;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.ObjectInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.ResolutionStatus;
import analyzer.model.SourceLocation;
import analyzer.model.ValueSource;
import analyzer.parameter.RequestParameterExtractor;
import analyzer.parser.TypeNames;
import analyzer.resolver.TypeLookup;
import analyzer.resolver.TypeRef;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 분석된(본문을 순회한) 메서드에서 객체 필드에 값을 설정하는 코드를 모두 수집한다.
 * 대상: 역할 VO(*VO/*Vo/*DTO/*Dto/*Form/*Command) source 클래스의 setter · builder · 생성자 · 직접 필드 대입 · 객체 복사,
 * 그리고 Map 계열 변수의 put(키, 값). 같은 필드의 여러 할당은 모두 기록하고 조건·순서를 보존한다.
 */
public final class BindingCollector {

    /** source 타입 판정/등록 콜백 (FlowAnalyzer 가 제공) */
    public interface Types {
        /** 분류 결과가 VO 역할인지 (결과 모델에 등록하지 않고 판정) */
        boolean isVo(TypeDeclaration<?> td);

        /** 결과 모델에 클래스를 등록하고 반환 */
        ClassInfo register(TypeDeclaration<?> td);
    }

    private static final Pattern SETTER = Pattern.compile("set[A-Z_].*");
    private static final Set<String> MAP_TYPES = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap",
            "ConcurrentHashMap", "Hashtable", "SortedMap", "NavigableMap");

    private record Key(String scope, String name) {
    }

    private final AnalysisResult r;
    private final Map<String, CallableDeclaration<?>> declarations;
    private final Map<String, Expression> callExprs;
    private final Map<MethodCallExpr, CallSiteInfo> siteByExpr = new IdentityHashMap<>();
    private final TypeLookup lookup;
    private final RequestParameterExtractor params;
    private final Types types;
    private final SourceTracer tracer;
    /** 필드 커버리지에 상위 클래스 필드 포함 (analysis.include-inherited-fields) */
    private boolean inheritedFields;

    private final Map<Key, ObjectInfo> objects = new LinkedHashMap<>();
    private final Map<String, TypeDeclaration<?>> objectTypes = new HashMap<>();

    public BindingCollector(AnalysisResult r, Map<String, CallableDeclaration<?>> declarations,
                            Map<MethodCallExpr, List<RequestParameterInfo>> paramsByCall,
                            Map<String, Expression> callExprs, TypeLookup lookup, RequestParameterExtractor params,
                            Types types) {
        this.r = r;
        this.declarations = declarations;
        this.callExprs = callExprs;
        this.lookup = lookup;
        this.params = params;
        this.types = types;
        this.tracer = new SourceTracer(r, declarations, paramsByCall, callExprs);
        callExprs.forEach((id, e) -> {
            if (e instanceof MethodCallExpr mc) {
                siteByExpr.put(mc, r.callSite(id));
            }
        });
    }

    public BindingCollector inheritedFields(boolean include) {
        this.inheritedFields = include;
        return this;
    }

    public void collect() {
        for (MethodInfo m : r.methods()) {
            CallableDeclaration<?> md = declarations.get(m.id);
            if (!m.analyzed || md == null || analyzer.parser.Code.body(md).isEmpty()) {
                continue;
            }
            analyzer.parser.Code.body(md).get().walk(Node.TreeTraversal.PREORDER, n -> visit(n, m, md));
        }
        flushSelfAssigns();
        objects.values().forEach(this::coverage);
        objects.values().forEach(this::fieldTypes);
    }

    private void visit(Node n, MethodInfo m, CallableDeclaration<?> md) {
        if (n instanceof MethodCallExpr mc) {
            String name = mc.getNameAsString();
            if (SETTER.matcher(name).matches() && mc.getArguments().size() == 1 && mc.getScope().isPresent()
                    && !(mc.getScope().get() instanceof ThisExpr)) {
                setter(mc, m, md);
            } else if (name.equals("build") && mc.getArguments().isEmpty() && mc.getScope().isPresent()) {
                builder(mc, m, md);
            } else if (name.equals("put") && mc.getArguments().size() == 2 && mc.getScope().orElse(null) instanceof NameExpr s) {
                mapPut(mc, s, m, md);
            } else if (name.equals("copyProperties") && mc.getArguments().size() >= 2) {
                copy(mc, m, md);
            }
        } else if (n instanceof ObjectCreationExpr oc && oc.getArguments().isNonEmpty()) {
            constructor(oc, m, md);
        } else if (n instanceof AssignExpr a && a.getTarget() instanceof FieldAccessExpr f && !(f.getScope() instanceof ThisExpr)) {
            fieldAssign(a, f, m, md);
        } else if (n instanceof AssignExpr a) {
            selfAssign(a, m, md);
        }
    }

    /**
     * 요청 셋업 클래스(예: XxxAction.ParamMgr.setup(request))의 자기 필드 대입 {@code appId = request.getParameter("appId")} /
     * {@code this.mcc = …}. VO 가 아닌 클래스에서 값의 출처에 요청 값이 있을 때만 바인딩으로 본다(VO 의 setter 본문
     * {@code this.x = x} 는 setter 호출 쪽 바인딩과 겹치므로 제외). 객체 = 그 메서드를 부른 분석된 호출 위치가 정확히 1개이고
     * 수신 객체가 변수면 호출자 쪽 변수(pm), 아니면 this.
     */
    private void selfAssign(AssignExpr a, MethodInfo m, CallableDeclaration<?> md) {
        TypeDeclaration<?> owner = md.findAncestor(TypeDeclaration.class).map(x -> (TypeDeclaration<?>) x).orElse(null);
        if (owner == null || types.isVo(owner) || md.isStatic()) {
            return;
        }
        // 람다·익명 클래스 안 대입은 다른 this
        for (Node p = a.getParentNode().orElse(null); p != null && p != md; p = p.getParentNode().orElse(null)) {
            if (p instanceof com.github.javaparser.ast.expr.LambdaExpr || p instanceof TypeDeclaration<?>
                    || p instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isPresent()) {
                return;
            }
        }
        String field;
        if (a.getTarget() instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
            field = f.getNameAsString();
        } else if (a.getTarget() instanceof NameExpr n && !localOrParam(n.getNameAsString(), md)) {
            field = n.getNameAsString();
        } else {
            return;
        }
        if (owner.getFieldByName(field).isEmpty()) {
            return;
        }
        Binding b = base("FIELD", "=", field, a, m);
        value(b, a.getValue(), m);
        if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
            b.transforms.add(0, a.getOperator().asString());
        }
        selfAssigns.computeIfAbsent(owner, x -> new ArrayList<>()).add(new SelfAssign(b, m));
    }

    private record SelfAssign(Binding binding, MethodInfo method) {
    }

    /** 자기 필드 대입 후보 (클래스별, 순서대로) — 요청 값이 하나라도 들어가는 클래스만 객체로 만든다 */
    private final Map<TypeDeclaration<?>, List<SelfAssign>> selfAssigns = new java.util.IdentityHashMap<>();

    /**
     * 요청 셋업 클래스 확정: 자기 필드 대입 중 하나라도 값의 출처가 요청 값이면 그 클래스의 자기 필드 대입을 모두 바인딩으로
     * (요청 값이 아닌 대입도 넣어야 필드 커버리지에서 '미할당'으로 잘못 나오지 않는다).
     */
    private void flushSelfAssigns() {
        selfAssigns.forEach((owner, list) -> {
            if (list.stream().noneMatch(x -> x.binding().sources.stream().anyMatch(v -> v.kind().equals("REQUEST")))) {
                return;
            }
            for (SelfAssign x : list) {
                add(thisObject(owner, x.method()), x.binding());
            }
        });
    }

    private static boolean localOrParam(String name, CallableDeclaration<?> md) {
        return md.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))
                || !md.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(name)).isEmpty();
    }

    /** this 객체: 분석된 호출 위치가 정확히 1개이고 수신 객체가 변수면 호출자 쪽 그 변수(같은 객체), 아니면 (클래스, this) */
    private ObjectInfo thisObject(TypeDeclaration<?> owner, MethodInfo m) {
        return thisObject(owner, m, 0);
    }

    private ObjectInfo thisObject(TypeDeclaration<?> owner, MethodInfo m, int hops) {
        List<CallSiteInfo> callers = r.callSites.stream()
                .filter(s -> m.id.equals(s.targetMethodId) || m.id.equals(s.implementationMethodId)).toList();
        if (callers.size() == 1) {
            CallSiteInfo s = callers.get(0);
            Expression call = callExprs.get(s.id);
            CallableDeclaration<?> callerDecl = declarations.get(s.callerMethodId);
            if (call instanceof MethodCallExpr mc && callerDecl != null && mc.getScope().orElse(null) instanceof NameExpr recv) {
                return object(recv, owner, r.method(s.callerMethodId), callerDecl);
            }
            // 같은 객체 안에서 부른 경우(생성자에서 setup(request), this.setup(…)) — 부른 메서드의 this 를 따라간다
            MethodInfo callerM = r.method(s.callerMethodId);
            if (call instanceof MethodCallExpr mc && callerDecl != null && hops < 8 && callerM != null
                    && callerM.classId.equals(m.classId) && !callerDecl.isStatic()
                    && (mc.getScope().isEmpty() || mc.getScope().get() instanceof ThisExpr)) {
                return thisObject(owner, callerM, hops + 1);
            }
            // 생성자 new ParamMgr(request): 만든 객체를 받는 변수 (ParamMgr pm = new … / pm = new …)
            if (call instanceof ObjectCreationExpr oc && callerDecl != null) {
                Optional<Expression> target = assignedTarget(oc);
                if (target.isPresent() && target.get() instanceof NameExpr recv) {
                    return object(recv, owner, r.method(s.callerMethodId), callerDecl);
                }
            }
        }
        Key k = new Key("field:" + m.classId, "this");
        return objects.computeIfAbsent(k, x -> {
            ClassInfo c = types.register(owner);
            ObjectInfo o = newObject("VO", c.id, c.name, x);
            objectTypes.put(o.id, owner);
            return o;
        });
    }

    // ------------------------------------------------------------------ assignment kinds

    private void setter(MethodCallExpr mc, MethodInfo m, CallableDeclaration<?> md) {
        Expression scope = mc.getScope().get();
        TypeDeclaration<?> td = typeOf(scope, md).orElse(null);
        CallSiteInfo site = siteByExpr.get(mc);
        if (td == null || !types.isVo(td)) {
            return;
        }
        Binding b = base("SETTER", mc.getNameAsString(), property(mc.getNameAsString().substring(3)), mc, m);
        b.callSiteId = site == null ? null : site.id;
        value(b, mc.getArgument(0), m);
        if (site != null && site.targetMethodId == null) {
            b.resolution = ResolutionStatus.PARTIALLY_RESOLVED;
            b.resolutionReason = "setter 선언을 소스에서 확인하지 못함(" + site.resolution + ") — 메서드명으로 필드 추정 불가 시 검토 필요";
        }
        add(object(scope, td, m, md), b);
    }

    private void builder(MethodCallExpr build, MethodInfo m, CallableDeclaration<?> md) {
        List<MethodCallExpr> chain = new ArrayList<>();
        Expression cur = build.getScope().get();
        TypeDeclaration<?> td = null;
        while (cur instanceof MethodCallExpr c) {
            if (c.getNameAsString().equals("builder") && c.getArguments().isEmpty()) {
                td = c.getScope().flatMap(s -> resolveType(s.toString(), c)).orElse(null);
                break;
            }
            chain.add(c);
            cur = c.getScope().orElse(null);
        }
        if (cur instanceof ObjectCreationExpr oc && oc.getType().asString().endsWith(".Builder")) {
            String outer = oc.getType().asString().replaceAll("\\.Builder$", "");
            td = resolveType(outer, oc).orElse(null);
        }
        if (td == null || !types.isVo(td) || chain.isEmpty()) {
            return;
        }
        Collections.reverse(chain);
        ObjectInfo o = object(assignedTarget(build).orElse(null), td, m, md, "builder@" + line(build));
        for (MethodCallExpr c : chain) {
            if (c.getArguments().size() != 1) {
                continue;
            }
            Binding b = base("BUILDER", c.getNameAsString(), c.getNameAsString(), c, m);
            CallSiteInfo site = siteByExpr.get(c);
            b.callSiteId = site == null ? null : site.id;
            value(b, c.getArgument(0), m);
            if (site == null || site.targetMethodId == null) {
                b.resolution = ResolutionStatus.PARTIALLY_RESOLVED;
                b.resolutionReason = "builder 메서드가 소스에 없음(Lombok 등 생성 코드) — 메서드명을 필드명으로 기록";
            }
            add(o, b);
        }
    }

    private void constructor(ObjectCreationExpr oc, MethodInfo m, CallableDeclaration<?> md) {
        TypeDeclaration<?> td = resolveType(oc.getType().getNameWithScope(), oc).orElse(null);
        if (td == null || !types.isVo(td)) {
            return;
        }
        int argc = oc.getArguments().size();
        List<ConstructorDeclaration> ctors = td.getConstructors().stream()
                .filter(c -> c.getParameters().size() == argc).toList();
        Map<String, String> paramToField = new HashMap<>();
        String reason = null;
        if (ctors.size() == 1) {
            ConstructorDeclaration c = ctors.get(0);
            for (AssignExpr a : c.findAll(AssignExpr.class)) {
                String field = a.getTarget() instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr ? f.getNameAsString()
                        : a.getTarget() instanceof NameExpr n ? n.getNameAsString() : null;
                if (field != null && a.getValue() instanceof NameExpr v) {
                    paramToField.putIfAbsent(v.getNameAsString(), field);
                }
            }
        } else {
            reason = ctors.isEmpty() ? "인자 " + argc + "개 생성자가 소스에 없음(Lombok 등) — 인자 위치로만 기록"
                    : "인자 수가 같은 생성자가 " + ctors.size() + "개 — 필드 매핑 확정 불가";
        }
        ObjectInfo o = object(assignedTarget(oc).orElse(null), td, m, md, "new@" + line(oc));
        for (int i = 0; i < argc; i++) {
            String pname = ctors.size() == 1 ? ctors.get(0).getParameter(i).getNameAsString() : null;
            String field = pname != null && paramToField.containsKey(pname) ? paramToField.get(pname) : null;
            Binding b = base("CONSTRUCTOR", td.getNameAsString(), field != null ? field
                    : "?(arg" + i + (pname == null ? "" : ":" + pname) + ")", oc, m);
            value(b, oc.getArgument(i), m);
            if (field == null) {
                b.resolution = ResolutionStatus.UNRESOLVED;
                b.resolutionReason = reason != null ? reason : "생성자 본문에서 파라미터 '" + pname + "' 의 필드 대입을 확인하지 못함";
            }
            add(o, b);
        }
    }

    private void fieldAssign(AssignExpr a, FieldAccessExpr f, MethodInfo m, CallableDeclaration<?> md) {
        TypeDeclaration<?> td = typeOf(f.getScope(), md).orElse(null);
        if (td == null || !types.isVo(td)) {
            return;
        }
        Binding b = base("FIELD", "=", f.getNameAsString(), a, m);
        value(b, a.getValue(), m);
        if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
            b.transforms.add(0, a.getOperator().asString());
        }
        add(object(f.getScope(), td, m, md), b);
    }

    private void mapPut(MethodCallExpr mc, NameExpr scope, MethodInfo m, CallableDeclaration<?> md) {
        Optional<Type> t = lookup.findVariableType(scope.getNameAsString(), mc);
        if (t.isEmpty() || !t.get().isClassOrInterfaceType()
                || !MAP_TYPES.contains(TypeNames.simple(TypeNames.erasure(t.get().asString())))) {
            return;
        }
        TypeDeclaration<?> callerType = md.findAncestor(TypeDeclaration.class).map(x -> (TypeDeclaration<?>) x).orElse(null);
        Optional<String> key = params.stringValue(mc.getArgument(0), callerType, mc);
        Binding b = base("MAP_PUT", "put", key.orElse("?(" + ConditionText.text(mc.getArgument(0)) + ")"), mc, m);
        CallSiteInfo site = siteByExpr.get(mc);
        b.callSiteId = site == null ? null : site.id;
        value(b, mc.getArgument(1), m);
        if (key.isEmpty()) {
            b.resolution = ResolutionStatus.UNRESOLVED;
            b.resolutionReason = "Map 키를 정적으로 확정할 수 없음(리터럴/상수 아님)";
        }
        Key k = key(scope, m, md, 0);
        ObjectInfo o = objects.computeIfAbsent(k, x -> newObject("MAP", null, TypeNames.simplify(t.get().asString()), x));
        add(o, b);
    }

    private void copy(MethodCallExpr mc, MethodInfo m, CallableDeclaration<?> md) {
        String scope = mc.getScope().map(Object::toString).orElse("");
        if (!scope.endsWith("BeanUtils") && !scope.endsWith("PropertyUtils")) {
            return;
        }
        Set<String> imports = new LinkedHashSet<>();
        md.findCompilationUnit().ifPresent(cu -> cu.getImports().forEach(i -> imports.add(i.getNameAsString())));
        Integer targetIdx = null;
        String reason = null;
        if (imports.stream().anyMatch(i -> i.startsWith("org.springframework.beans."))) {
            targetIdx = 1; // Spring BeanUtils.copyProperties(source, target)
        } else if (imports.stream().anyMatch(i -> i.startsWith("org.apache.commons.beanutils."))) {
            targetIdx = 0; // Commons BeanUtils/PropertyUtils.copyProperties(dest, orig)
        } else {
            reason = "copyProperties 라이브러리(Spring: source,target / Commons: dest,orig)를 import 로 판별하지 못함";
        }
        int ti = targetIdx == null ? 1 : targetIdx;
        Expression target = mc.getArgument(ti);
        Expression source = mc.getArgument(ti == 1 ? 0 : 1);
        TypeDeclaration<?> td = typeOf(target, md).orElse(null);
        if (td == null || !types.isVo(td)) {
            return;
        }
        Binding b = base("COPY", "copyProperties", "*", mc, m);
        value(b, source, m);
        b.resolution = ResolutionStatus.PARTIALLY_RESOLVED;
        b.resolutionReason = reason != null ? reason : "이름이 같은 프로퍼티를 런타임에 복사 — 필드별 매핑은 정적으로 확인 불가";
        add(object(target, td, m, md), b);
    }

    // ------------------------------------------------------------------ helpers

    private Binding base(String assignment, String method, String field, Node node, MethodInfo m) {
        Binding b = new Binding();
        b.assignment = assignment;
        b.method = method;
        b.field = field;
        b.methodId = m.id;
        b.location = SourceLocation.of(node, m.location.file());
        b.controlBlockId = innermostBlock(m.id, b.location);
        b.conditions.addAll(ConditionText.of(node));
        b.resolution = ResolutionStatus.RESOLVED;
        return b;
    }

    private void value(Binding b, Expression value, MethodInfo m) {
        b.valueExpression = ConditionText.text(value);
        SourceTracer.Result t = tracer.trace(value, m.id);
        b.sources.addAll(t.sources());
        b.transforms.addAll(t.transforms());
        b.reassigned = t.reassigned();
        if (t.sources().size() == 1 && t.sources().get(0).parameterId() != null) {
            b.defaultValue = r.parameter(t.sources().get(0).parameterId()).defaultValue; // 출처가 요청 파라미터 하나일 때만
        }
        boolean allUnresolved = !t.sources().isEmpty() && t.sources().stream().allMatch(s -> s.kind().equals("UNRESOLVED"));
        boolean someUnresolved = t.sources().stream().anyMatch(s -> s.kind().equals("UNRESOLVED"));
        if (t.sources().isEmpty() || allUnresolved) {
            b.resolution = ResolutionStatus.UNRESOLVED;
            b.resolutionReason = "값의 출처를 정적으로 확인하지 못함";
        } else if (someUnresolved) {
            b.resolution = ResolutionStatus.PARTIALLY_RESOLVED;
            b.resolutionReason = "일부 출처를 확인하지 못함";
        }
    }

    private void add(ObjectInfo o, Binding b) {
        b.id = r.nextId("B");
        b.objectId = o.id;
        b.order = o.bindingIds.size() + 1;
        o.bindingIds.add(b.id);
        r.bindings.add(b);
        r.index(b.id, b);
    }

    private ObjectInfo object(Expression receiver, TypeDeclaration<?> td, MethodInfo m, CallableDeclaration<?> md) {
        return object(receiver, td, m, md, "expr@" + (receiver == null ? "?" : ConditionText.text(receiver)));
    }

    private ObjectInfo object(Expression receiver, TypeDeclaration<?> td, MethodInfo m, CallableDeclaration<?> md, String anon) {
        Key k = receiver == null ? new Key(m.id, anon) : key(receiver, m, md, 0);
        return objects.computeIfAbsent(k, x -> {
            ClassInfo c = types.register(td);
            ObjectInfo o = newObject("VO", c.id, c.name, x);
            objectTypes.put(o.id, td);
            return o;
        });
    }

    private ObjectInfo newObject(String kind, String classId, String className, Key k) {
        ObjectInfo o = new ObjectInfo();
        o.id = r.nextId("O");
        o.kind = kind;
        o.classId = classId;
        o.className = className;
        o.variable = k.name();
        o.methodId = k.scope().startsWith("field:") ? null : k.scope();
        r.objects.add(o);
        r.index(o.id, o);
        return o;
    }

    /**
     * 객체 정체성: 지역 변수 → (메서드, 변수). 메서드 파라미터이고 분석된 호출 위치가 정확히 1개이며 그 인자가 변수면
     * 호출자 쪽 객체로 이어 붙인다. 필드 → (클래스, field:이름). 그 외 식 → (메서드, 식 원문).
     */
    private Key key(Expression receiver, MethodInfo m, CallableDeclaration<?> md, int hops) {
        if (receiver instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
            return new Key("field:" + m.classId, f.getNameAsString());
        }
        if (!(receiver instanceof NameExpr n)) {
            return new Key(m.id, ConditionText.text(receiver));
        }
        String name = n.getNameAsString();
        boolean local = !md.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(name)).isEmpty();
        for (int i = 0; i < md.getParameters().size() && !local; i++) {
            if (!md.getParameter(i).getNameAsString().equals(name)) {
                continue;
            }
            List<CallSiteInfo> callers = r.callSites.stream()
                    .filter(s -> m.id.equals(s.targetMethodId) || m.id.equals(s.implementationMethodId)).toList();
            if (callers.size() == 1 && hops < 8) {
                CallSiteInfo s = callers.get(0);
                var call = callExprs.get(s.id) instanceof com.github.javaparser.ast.nodeTypes.NodeWithArguments<?> a ? a : null;
                CallableDeclaration<?> callerDecl = declarations.get(s.callerMethodId);
                if (call != null && callerDecl != null && call.getArguments().size() > i
                        && call.getArgument(i) instanceof NameExpr) {
                    return key(call.getArgument(i), r.method(s.callerMethodId), callerDecl, hops + 1);
                }
            }
            return new Key(m.id, name);
        }
        if (!local) {
            TypeDeclaration<?> td = md.findAncestor(TypeDeclaration.class).map(x -> (TypeDeclaration<?>) x).orElse(null);
            if (td != null && td.getFieldByName(name).isPresent()) {
                return new Key("field:" + m.classId, name);
            }
        }
        return new Key(m.id, name);
    }

    private Optional<TypeDeclaration<?>> typeOf(Expression e, CallableDeclaration<?> md) {
        Optional<Type> t = Optional.empty();
        if (e instanceof NameExpr n) {
            t = lookup.findVariableType(n.getNameAsString(), e);
        } else if (e instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
            TypeDeclaration<?> owner = md.findAncestor(TypeDeclaration.class).map(x -> (TypeDeclaration<?>) x).orElse(null);
            if (owner != null) {
                t = lookup.findFieldType(owner, f.getNameAsString(), new java.util.HashSet<>());
            }
        }
        return t.filter(Type::isClassOrInterfaceType).flatMap(x -> resolveType(TypeNames.erasure(x.asString()), e));
    }

    private Optional<TypeDeclaration<?>> resolveType(String name, Node ctx) {
        return lookup.resolveTypeName(name, ctx).filter(TypeRef::inSource).map(TypeRef::declaration);
    }

    private static Optional<Expression> assignedTarget(Expression e) {
        Node p = e.getParentNode().orElse(null);
        if (p instanceof VariableDeclarator v) {
            return Optional.of(new NameExpr(v.getNameAsString()));
        }
        if (p instanceof AssignExpr a && a.getValue() == e) {
            return Optional.of(a.getTarget());
        }
        return Optional.empty();
    }

    private String innermostBlock(String methodId, SourceLocation loc) {
        ControlBlock best = null;
        for (ControlBlock b : r.blocksOf(methodId)) {
            if (contains(b.location, loc) && (best == null || contains(best.location, b.location))) {
                best = b;
            }
        }
        return best == null ? null : best.id;
    }

    static boolean contains(SourceLocation outer, SourceLocation inner) {
        if (outer == null || inner == null) {
            return false;
        }
        boolean begin = outer.beginLine() < inner.beginLine()
                || outer.beginLine() == inner.beginLine() && outer.beginColumn() <= inner.beginColumn();
        boolean end = inner.endLine() < outer.endLine()
                || inner.endLine() == outer.endLine() && inner.endColumn() <= outer.endColumn();
        return begin && end;
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(-1);
    }

    /** JavaBeans decapitalize: OrderNo → orderNo, URL → URL */
    static String property(String s) {
        if (s.isEmpty() || s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** 선언 필드 vs 실제 할당 필드 */
    private void coverage(ObjectInfo o) {
        List<Binding> bs = r.bindingsOf(o);
        Set<String> assigned = new LinkedHashSet<>();
        bs.stream().map(b -> b.field).filter(f -> !f.equals("*") && !f.startsWith("?(")).forEach(assigned::add);
        o.assignedFields.addAll(assigned);
        for (String f : assigned) {
            if (bs.stream().filter(b -> b.field.equals(f)).allMatch(Binding::conditional)) {
                o.conditionalFields.add(f);
            }
        }
        TypeDeclaration<?> td = objectTypes.get(o.id);
        if (td == null) {
            return;
        }
        Map<String, Boolean> declared = new LinkedHashMap<>();
        declaredFields(td, declared, new java.util.HashSet<>());
        o.declaredFields.addAll(declared.keySet());
        boolean copied = bs.stream().anyMatch(b -> b.assignment.equals("COPY"));
        for (var e : declared.entrySet()) {
            if (assigned.contains(e.getKey())) {
                continue;
            }
            if (copied) {
                o.unknownFields.add(e.getKey());
            } else if (e.getValue()) {
                o.initialOnlyFields.add(e.getKey());
            } else {
                o.unassignedFields.add(e.getKey());
            }
        }
    }

    /** 바인딩 대상 필드의 선언 타입 (source VO 와 상위 클래스의 인스턴스 필드) */
    private void fieldTypes(ObjectInfo o) {
        TypeDeclaration<?> td = objectTypes.get(o.id);
        if (td == null) {
            return;
        }
        Map<String, String> types = new LinkedHashMap<>();
        fieldTypes(td, types, new java.util.HashSet<>());
        for (Binding b : r.bindingsOf(o)) {
            b.fieldType = types.get(b.field);
        }
    }

    private void fieldTypes(TypeDeclaration<?> td, Map<String, String> out, Set<String> visited) {
        if (!visited.add(td.getFullyQualifiedName().orElse(td.getNameAsString()))) {
            return;
        }
        for (TypeDeclaration<?> sup : lookup.sourceSupertypes(td)) {
            fieldTypes(sup, out, visited);
        }
        for (FieldDeclaration f : td.getFields()) {
            if (!f.isStatic()) {
                f.getVariables().forEach(v -> out.putIfAbsent(v.getNameAsString(), v.getTypeAsString()));
            }
        }
    }

    private void declaredFields(TypeDeclaration<?> td, Map<String, Boolean> out, Set<String> visited) {
        if (!visited.add(td.getFullyQualifiedName().orElse(td.getNameAsString()))) {
            return;
        }
        if (inheritedFields) {
            for (TypeDeclaration<?> sup : lookup.sourceSupertypes(td)) {
                declaredFields(sup, out, visited);
            }
        }
        for (FieldDeclaration f : td.getFields()) {
            if (f.isStatic()) {
                continue;
            }
            f.getVariables().forEach(v -> out.putIfAbsent(v.getNameAsString(), v.getInitializer().isPresent()));
        }
    }
}
