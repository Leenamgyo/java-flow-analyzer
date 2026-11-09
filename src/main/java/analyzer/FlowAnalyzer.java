package analyzer;

import analyzer.classifier.ClassRoleClassifier;
import analyzer.config.AnalyzerConfig;
import analyzer.controlflow.MethodBodyWalker;
import analyzer.controlflow.WalkContext;
import analyzer.model.AnalysisResult;
import analyzer.model.CallRelation;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassRole;
import analyzer.model.ClassInfo;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.MethodParameter;
import analyzer.model.Origin;
import analyzer.model.ResolutionStatus;
import analyzer.model.SourceLocation;
import analyzer.model.Visibility;
import analyzer.parameter.RequestParameterExtractor;
import analyzer.parser.EntryPointLocator;
import analyzer.parser.ProjectSources;
import analyzer.parser.TypeNames;
import analyzer.resolver.CallResolver;
import analyzer.resolver.CallTarget;
import analyzer.resolver.ImplementationFinder;
import analyzer.resolver.TypeLookup;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 지정 Action/Controller 의 진입 메서드에서 시작해 BFS 로 호출 관계를 분석한다.
 * - 메서드 본문 분석 조건: 대상 Depth < max-depth, 또는 (expand-same-class-methods && 호출자와 같은 클래스),
 *   또는 대상 클래스 역할이 expand-roles 에 포함
 * - link-single-implementation: 본문 없는 인터페이스/추상 메서드 호출은 source-root 내 유일 구현을 구현 후보로 연결해 전개 판정
 * - Depth 는 최단 호출 경로 기준이며 동일 클래스 확장 여부와 무관하게 실제 값을 유지한다.
 */
public final class FlowAnalyzer {

    private record Work(MethodInfo method, MethodDeclaration declaration, int depth) {
    }

    private final AnalyzerConfig cfg;
    private final ProjectSources sources;
    private final TypeLookup types;
    private final CallResolver resolver;
    private final ClassRoleClassifier classifier = new ClassRoleClassifier();
    private final AnalysisResult result = new AnalysisResult();
    private final RequestParameterExtractor params;
    private final ImplementationFinder implementations;
    /** max-depth 를 넘어도 본문을 분석할 역할 */
    private final java.util.Set<ClassRole> expandRoles;

    private final Deque<Work> queue = new ArrayDeque<>();
    private final Map<String, Work> scheduled = new HashMap<>();
    /** 전개 트리 부모: methodId → 호출자 methodId */
    private final Map<String, String> expansionParent = new HashMap<>();
    /** methodId → 분석기 AST 상의 선언 (source 메서드만) */
    private final Map<String, MethodDeclaration> declarations = new HashMap<>();
    /** 호출 표현식(노드 동일성) → 그 호출에서 읽은 Request Parameter. JavaParser Node.equals 는 구조 비교라 IdentityHashMap 사용 */
    private final Map<MethodCallExpr, List<analyzer.model.RequestParameterInfo>> paramsByCall = new java.util.IdentityHashMap<>();
    /** SqlSession 류 호출의 statement id (recorded 에서 호출 위치에 붙임) */
    private final Map<MethodCallExpr, analyzer.model.SqlRef> sqlByCall = new java.util.IdentityHashMap<>();
    /** callSiteId → 호출 표현식 (값 추적용) */
    private final Map<String, MethodCallExpr> callExprs = new HashMap<>();

    public FlowAnalyzer(AnalyzerConfig cfg) {
        this.cfg = cfg;
        List<Path> jars = cfg.analysis.classpathJars.stream().map(Path::of).toList();
        this.sources = new ProjectSources(cfg.target.effectiveSourceRoots().stream().map(Path::of).toList(), jars);
        this.types = new TypeLookup(sources);
        this.resolver = new CallResolver(sources, types);
        this.params = new RequestParameterExtractor(result, types, cfg.requestParameterRules);
        this.implementations = new ImplementationFinder(sources);
        this.expandRoles = cfg.analysis.expandRoles.stream()
                .map(x -> ClassRole.fromLabel(x).orElseThrow(() -> new IllegalArgumentException("알 수 없는 역할: " + x)))
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(ClassRole.class)));
    }

    public ProjectSources sources() {
        return sources;
    }

    public AnalysisResult analyze() {
        EntryPointLocator.EntryPoint entry = EntryPointLocator.locate(sources, cfg.target.className,
                cfg.target.entryMethod, cfg.target.entryMethodParameterTypes);
        result.sourceRoot = sources.sourceBase().toString();
        sources.sourceRoots().forEach(p -> result.sourceRoots.add(p.toString()));
        result.entrySelection = entry.selection();
        recordSettings();

        ClassInfo target = sourceClass(entry.type());
        target.scope = "TARGET";
        result.targetClassId = target.id;
        MethodInfo entryMethod = sourceMethod(entry.method());
        entryMethod.depth = 0;
        result.entryMethodId = entryMethod.id;
        schedule(entryMethod, entry.method(), 0, null, null);

        while (!queue.isEmpty()) {
            analyzeBody(queue.poll());
        }
        finish();
        if (cfg.analysis.includeSqlIds) {
            sqlIds();
        }
        if (cfg.analysis.includeBindings) {
            new analyzer.binding.BindingCollector(result, declarations, paramsByCall, callExprs, types, params,
                    new analyzer.binding.BindingCollector.Types() {
                        @Override
                        public boolean isVo(TypeDeclaration<?> td) {
                            return classify(td).role() == ClassRole.VO;
                        }

                        @Override
                        public ClassInfo register(TypeDeclaration<?> td) {
                            return sourceClass(td);
                        }
                    }).inheritedFields(cfg.analysis.includeInheritance).collect();
        }
        if (cfg.analysis.includeVariableChanges) {
            new analyzer.binding.VariableStateCollector(result, declarations, paramsByCall, callExprs).collect();
        }
        if (cfg.analysis.includeRequestParameters) {
            analyzer.binding.RequestSpecBuilder.build(result);
        }
        result.notes.addAll(sources.parseProblems().stream().map(p -> "파싱 문제: " + p).toList());
        java.util.Map<String, List<String>> dups = new java.util.TreeMap<>(sources.duplicateTypes());
        Set<List<String>> usedDup = result.callSites.stream()
                .filter(c -> c.resolutionBasis != null && c.resolutionBasis.endsWith("+DUPLICATE_FQN"))
                .map(c -> List.copyOf(c.candidates)).collect(Collectors.toSet());
        sources.duplicateFqns().forEach((fqn, files) -> {
            if (usedDup.contains(files)) {
                dups.putIfAbsent(fqn, files);
            }
        });
        dups.forEach((fqn, files) -> result.notes.add(
                "중복 FQN(여러 source-root): " + fqn + " → " + String.join(", ", files) + " — 어느 것이 쓰이는지 확정 불가, 해석하지 않음"));
        return result;
    }

    /**
     * 매퍼 XML statement: 매퍼 인터페이스 호출도 SqlRef 로, 그다음 원본 프로젝트의 XML(읽기 전용)에서
     * 파일·id·종류·테이블을 찾는다. 쿼리 본문은 모델에 넣지 않는다 (사용자: id 만 알면 쿼리는 손으로 옮김).
     */
    private void sqlIds() {
        for (CallSiteInfo s : result.callSites) {
            if (s.sql == null) {
                s.sql = analyzer.sql.SqlCalls.mapperRef(result, s);
            }
            if (s.sql == null) {
                s.sql = analyzer.sql.SqlCalls.interfaceRef(result, s); // *DAO 이름의 MyBatis 인터페이스 등
            }
        }
        if (result.callSites.stream().noneMatch(s -> s.sql != null)) {
            return;
        }
        List<java.nio.file.Path> roots = analyzer.sql.SqlCalls.xmlRoots(cfg.analysis.mapperXmlRoots, sources.sourceRoots());
        analyzer.sql.MapperXmlIndex idx = analyzer.sql.MapperXmlIndex.scan(roots);
        idx.errors().forEach(e -> result.notes.add("매퍼 XML: " + e));
        java.nio.file.Path base = projectBase();
        for (CallSiteInfo s : result.callSites) {
            if (s.sql != null) {
                analyzer.sql.SqlCalls.match(s.sql, idx, f -> {
                    java.nio.file.Path b = base;
                    java.nio.file.Path abs = f.toAbsolutePath().normalize();
                    while (b != null && !abs.startsWith(b)) {
                        b = b.getParent();
                    }
                    return b == null ? abs.toString() : b.relativize(abs).toString();
                });
                // 모양만으로 확정할 수 없던 호출은 XML 에 statement 가 있어야 SQL 호출 (없으면 지움 — 추측 금지)
                if (s.sql.requiresXmlMatch && !"FOUND".equals(s.sql.xmlStatus) && !"AMBIGUOUS".equals(s.sql.xmlStatus)) {
                    s.sql = null;
                }
            }
        }
        // 공통 DAO·상위 DAO 안의 sqlSession.selectList(statementId, …): id 가 이 메서드 파라미터면 실제 id 는 호출하는 쪽에 있다
        for (CallSiteInfo s : result.callSites) {
            if (s.sql == null || !"UNRESOLVED".equals(s.sql.basis)) {
                continue;
            }
            MethodInfo m = result.method(s.callerMethodId);
            if (m.parameters.stream().noneMatch(p -> p.name().equals(s.sql.statementId))) {
                continue;
            }
            List<String> callers = result.callSites.stream().filter(c -> c.sql != null && m.id.equals(c.expansionTargetId())
                    && !"UNRESOLVED".equals(c.sql.basis)).map(c -> c.id + " " + c.sql.statementId).toList();
            if (!callers.isEmpty()) {
                s.sql.notes.add("id 는 파라미터 " + s.sql.statementId + " — 호출하는 쪽에서 정함: " + String.join(", ", callers));
            }
        }
    }

    /** 경로 표시 기준: source 기준 폴더가 …/src/… 아래면 src 의 부모(프로젝트 폴더) */
    private java.nio.file.Path projectBase() {
        java.nio.file.Path base = sources.sourceBase().toAbsolutePath().normalize();
        for (java.nio.file.Path p = base; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("src") && p.getParent() != null) {
                return p.getParent();
            }
        }
        return base;
    }

    private void recordSettings() {
        var a = cfg.analysis;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("max-depth", a.maxDepth);
        s.put("expand-same-class-methods", a.expandSameClassMethods);
        s.put("include-conditions", a.includeConditions);
        s.put("include-exceptions", a.includeExceptions);
        s.put("include-request-parameters", a.includeRequestParameters);
        s.put("include-external-library-calls", a.includeExternalLibraryCalls);
        s.put("expand-roles", a.expandRoles);
        s.put("link-single-implementation", a.linkSingleImplementation);
        s.put("include-bindings", a.includeBindings);
        s.put("include-inheritance", a.includeInheritance);
        s.put("classpath-jars", a.classpathJars);
        s.put("include-sql-ids", a.includeSqlIds);
        s.put("mapper-xml-roots", a.mapperXmlRoots);
        result.settings = s;
    }

    private void schedule(MethodInfo m, MethodDeclaration md, int depth, CallSiteInfo site, MethodInfo caller) {
        Work w = new Work(m, md, depth);
        scheduled.put(m.id, w);
        queue.add(w);
        m.analyzed = true;
        if (site != null) {
            m.expandedAt = site.id;
            site.expansion = Expansion.EXPANDED;
            expansionParent.put(m.id, caller.id);
        }
    }

    private void analyzeBody(Work w) {
        MethodInfo caller = w.method();
        MethodDeclaration md = w.declaration();
        TypeDeclaration<?> callerType = md.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t).orElseThrow();
        String file = sources.relativeFile(md);
        if (cfg.analysis.includeRequestParameters) {
            params.fromAnnotations(md, caller, file);
        }

        MethodBodyWalker.CallSink sink = new MethodBodyWalker.CallSink() {
            @Override
            public CallSiteInfo resolve(MethodCallExpr call, WalkContext ctx) {
                CallTarget t = resolver.resolve(call, callerType);
                boolean readsRequest = false;
                if (cfg.analysis.includeRequestParameters) {
                    List<analyzer.model.RequestParameterInfo> read = params.fromCall(call, t, caller, callerType, file);
                    paramsByCall.put(call, read);
                    readsRequest = !read.isEmpty();
                }
                // Request 를 읽는 호출(getParameter 등)은 요청 스펙의 근거, 로깅 호출은 흐름도 로그 블록의 근거이므로
                // 외부 라이브러리 제외 대상이어도 남긴다
                boolean logging = analyzer.model.LogCalls.isLog(call.getNameAsString(),
                        call.getScope().map(Object::toString).orElse(null));
                // 문장의 가장 바깥 호출(extraInfo.add(x) 처럼 문장이 곧 JDK 호출)은 빼면 그 코드가 흐름도에서 사라지므로 남긴다.
                // 다른 호출의 인자·수신 객체 안 호출은 바깥 호출 코드에 보이므로 제외 대상
                // SqlSession.selectList("ns.id") 류는 매퍼 XML statement 의 근거이므로 남긴다
                boolean sql = cfg.analysis.includeSqlIds && analyzer.sql.SqlCalls.isSessionCall(call, t.receiverType());
                if (sql) {
                    sqlByCall.put(call, analyzer.sql.SqlCalls.sessionRef(call, e -> params.stringValue(e, callerType, call)));
                } else if (cfg.analysis.includeSqlIds) {
                    // 상속 메서드(EgovAbstractDAO.selectList …)·공통 DAO 빈 호출: 첫 인자가 XML statement 와 맞을 때만 SQL (sqlIds)
                    analyzer.model.SqlRef ref = analyzer.sql.SqlCalls.byStatementArgument(call, e -> params.stringValue(e, callerType, call));
                    if (ref != null) {
                        sqlByCall.put(call, ref);
                        sql = true;
                    }
                }
                if (!cfg.analysis.includeExternalLibraryCalls && t.origin() == Origin.LIBRARY && !readsRequest && !logging
                        && !sql && !outermostInStatement(call)) {
                    result.excludedExternalCalls++;
                    return null;
                }
                return toSite(t, call);
            }

            @Override
            public void recorded(CallSiteInfo site, MethodCallExpr call) {
                paramsByCall.getOrDefault(call, List.of()).forEach(p -> p.callSiteId = site.id);
                site.sql = sqlByCall.remove(call);
                callExprs.put(site.id, call);
                site.depth = w.depth() + 1;
                if (site.targetMethodId == null) {
                    return;
                }
                MethodInfo declared = result.method(site.targetMethodId);
                declared.depth = Math.min(declared.depth, site.depth);
                MethodInfo target = result.method(site.expansionTargetId());
                target.depth = Math.min(target.depth, site.depth);
                if (target.origin != Origin.SOURCE || !target.hasBody || scheduled.containsKey(target.id)) {
                    return; // 최종 Expansion 은 finish() 에서 결정
                }
                boolean byDepth = site.depth < cfg.analysis.maxDepth;
                boolean sameClass = cfg.analysis.expandSameClassMethods && target.classId.equals(caller.classId);
                boolean byRole = expandRoles.contains(result.clazz(target.classId).role);
                if (byDepth || sameClass || byRole) {
                    schedule(target, declarations.get(target.id), site.depth, site, caller);
                }
            }
        };
        new MethodBodyWalker(result, caller, file,
                new MethodBodyWalker.Options(cfg.analysis.includeConditions, cfg.analysis.includeExceptions),
                sink, types).walk(md);
    }

    private CallSiteInfo toSite(CallTarget t, MethodCallExpr call) {
        CallSiteInfo s = new CallSiteInfo();
        s.resolution = t.status();
        s.resolutionBasis = t.basis();
        s.resolutionReason = t.reason();
        s.receiverType = t.receiverType();
        s.targetOrigin = t.origin();
        s.candidates.addAll(t.candidates());
        if (t.status() == ResolutionStatus.RESOLVED && t.sourceDeclaration() != null) {
            MethodInfo declared = sourceMethod(t.sourceDeclaration());
            s.targetMethodId = declared.id;
            if (cfg.analysis.linkSingleImplementation && !declared.hasBody && !declared.isStatic) {
                linkImplementation(s, t.sourceDeclaration());
            }
        } else if (t.status() == ResolutionStatus.RESOLVED && t.libraryDeclaration() != null) {
            s.targetMethodId = libraryMethod(t.libraryDeclaration()).id;
        } else if (t.status() == ResolutionStatus.PARTIALLY_RESOLVED && t.declaringType() != null) {
            s.targetMethodId = partialMethod(t, call.getNameAsString(), call.getArguments().size()).id;
        }
        return s;
    }

    /** source-root 내 유일 구현이 있으면 구현 후보로 연결한다. 선언 대상(target-method-id)은 바꾸지 않는다. */
    private void linkImplementation(CallSiteInfo s, MethodDeclaration declaration) {
        ImplementationFinder.Result f = implementations.find(declaration);
        s.implementationBasis = f.basis();
        s.implementationReason = f.reason();
        s.implementationCandidates.addAll(f.candidates());
        f.method().ifPresent(md -> s.implementationMethodId = sourceMethod(md).id);
    }

    // ------------------------------------------------------------------ registration

    private ClassInfo sourceClass(TypeDeclaration<?> td) {
        String key = td.getFullyQualifiedName().orElse(td.getNameAsString());
        ClassInfo existing = result.classesByKey.get(key);
        if (existing != null) {
            return existing;
        }
        ClassInfo c = new ClassInfo();
        c.key = key;
        c.name = td.getNameAsString();
        c.qualifiedName = key;
        c.packageName = td.findCompilationUnit().flatMap(cu -> cu.getPackageDeclaration())
                .map(p -> p.getNameAsString()).orElse("");
        c.origin = Origin.SOURCE;
        c.scope = "REFERENCED";
        if (td instanceof ClassOrInterfaceDeclaration ci) {
            c.kind = ci.isInterface() ? "INTERFACE" : "CLASS";
            c.isAbstract = ci.isAbstract();
            if (cfg.analysis.includeInheritance) {
                ci.getExtendedTypes().forEach(t -> c.extendsTypes.add(t.asString()));
            }
            ci.getImplementedTypes().forEach(t -> c.implementsTypes.add(t.asString()));
        } else if (td instanceof EnumDeclaration e) {
            c.kind = "ENUM";
            e.getImplementedTypes().forEach(t -> c.implementsTypes.add(t.asString()));
        } else if (td instanceof RecordDeclaration r) {
            c.kind = "RECORD";
            r.getImplementedTypes().forEach(t -> c.implementsTypes.add(t.asString()));
        } else if (td instanceof AnnotationDeclaration) {
            c.kind = "ANNOTATION";
        } else {
            c.kind = "UNKNOWN";
        }
        td.getAnnotations().forEach(a -> c.annotations.add(a.getNameAsString()));
        applyRole(c, shownClassification(td));
        c.location = SourceLocation.of(td, sources.relativeFile(td));
        register(c, "C");
        return c;
    }

    /**
     * 출력할 역할 분류: include-inheritance: false 면 상위 클래스(extends) 없이 분류해 역할이 같을 때 그 결과(근거에 상위 클래스 없음).
     * 상위 클래스로만 역할이 정해지면 근거를 지우지 않는다.
     */
    private ClassRoleClassifier.Classification shownClassification(TypeDeclaration<?> td) {
        ClassRoleClassifier.Classification full = classify(td);
        if (cfg.analysis.includeInheritance) {
            return full;
        }
        ClassRoleClassifier.Classification own = classify(td, false);
        return own.role() == full.role() ? own : full;
    }

    private ClassRoleClassifier.Classification classify(TypeDeclaration<?> td) {
        return classify(td, true);
    }

    private ClassRoleClassifier.Classification classify(TypeDeclaration<?> td, boolean withExtends) {
        List<String> annotations = td.getAnnotations().stream().map(a -> a.getNameAsString()).toList();
        List<String> supers = new ArrayList<>();
        if (td instanceof ClassOrInterfaceDeclaration ci) {
            if (withExtends) {
                ci.getExtendedTypes().forEach(t -> supers.add(t.asString()));
            }
            ci.getImplementedTypes().forEach(t -> supers.add(t.asString()));
        } else if (td instanceof EnumDeclaration e) {
            e.getImplementedTypes().forEach(t -> supers.add(t.asString()));
        } else if (td instanceof RecordDeclaration rd) {
            rd.getImplementedTypes().forEach(t -> supers.add(t.asString()));
        }
        return classifier.classify(td.getNameAsString(), annotations, supers);
    }

    private ClassInfo externalClass(String typeName, Origin origin, ResolvedReferenceTypeDeclaration decl) {
        String key = TypeNames.erasure(typeName);
        ClassInfo existing = result.classesByKey.get(key);
        if (existing != null) {
            return existing;
        }
        ClassInfo c = new ClassInfo();
        c.key = key;
        c.name = TypeNames.simple(key);
        c.qualifiedName = key.contains(".") ? key : null;
        c.packageName = key.contains(".") ? TypeNames.packageOf(key) : null;
        c.origin = origin;
        c.scope = "REFERENCED";
        c.kind = "UNKNOWN";
        if (decl != null) {
            try {
                c.kind = decl.isInterface() ? "INTERFACE" : decl.isEnum() ? "ENUM" : "CLASS";
            } catch (RuntimeException ignored) {
                // keep UNKNOWN
            }
        }
        applyRole(c, classifier.classify(c.name, List.of(), List.of()));
        register(c, "C");
        return c;
    }

    private void applyRole(ClassInfo c, ClassRoleClassifier.Classification cl) {
        c.role = cl.role();
        c.roleDecidedBy = cl.decidedBy();
        c.roleConflict = cl.conflict();
        c.roleEvidence.addAll(cl.evidence());
    }

    private MethodInfo sourceMethod(MethodDeclaration md) {
        TypeDeclaration<?> td = md.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t).orElseThrow();
        ClassInfo c = sourceClass(td);
        List<String> erased = md.getParameters().stream().map(FlowAnalyzer::paramType).map(TypeNames::erasure).toList();
        String key = c.key + "#" + md.getNameAsString() + "(" + String.join(",", erased) + ")";
        MethodInfo existing = result.methodsByKey.get(key);
        if (existing != null) {
            return existing;
        }
        boolean iface = td instanceof ClassOrInterfaceDeclaration ci && ci.isInterface();
        MethodInfo m = new MethodInfo();
        m.key = key;
        m.classId = c.id;
        m.name = md.getNameAsString();
        m.signature = m.name + "(" + md.getParameters().stream().map(FlowAnalyzer::paramType).collect(Collectors.joining(", ")) + ")";
        m.signatureResolved = true;
        AccessSpecifier as = md.getAccessSpecifier();
        if (iface && as == AccessSpecifier.NONE) {
            m.visibility = Visibility.PUBLIC;
            m.modifiersImplicit = true;
        } else {
            m.visibility = Visibility.of(as);
        }
        m.isStatic = md.isStatic();
        m.hasBody = md.getBody().isPresent();
        m.isAbstract = md.isAbstract() || (iface && !m.hasBody && !md.isStatic() && !md.isDefault());
        if (iface && m.isAbstract && !md.isAbstract()) {
            m.modifiersImplicit = true;
        }
        m.returnType = md.getType().asString();
        for (Parameter p : md.getParameters()) {
            m.parameters.add(new MethodParameter(p.getNameAsString(), paramType(p)));
        }
        md.getThrownExceptions().forEach(t -> m.thrownTypes.add(t.asString()));
        md.getAnnotations().forEach(a -> m.annotations.add(a.getNameAsString()));
        m.origin = Origin.SOURCE;
        m.location = SourceLocation.of(md, sources.relativeFile(md));
        m.depth = Integer.MAX_VALUE;
        register(m, c);
        declarations.put(m.id, md);
        return m;
    }

    private MethodInfo libraryMethod(ResolvedMethodDeclaration rmd) {
        String declaring = rmd.declaringType().getQualifiedName();
        List<String> ptypes = new ArrayList<>();
        try {
            for (int i = 0; i < rmd.getNumberOfParams(); i++) {
                ptypes.add(TypeNames.simplify(rmd.getParam(i).describeType()));
            }
        } catch (RuntimeException e) {
            ptypes = null;
        }
        String key = declaring + "#" + rmd.getName() + (ptypes == null ? "/?" :
                "(" + ptypes.stream().map(TypeNames::erasure).collect(Collectors.joining(",")) + ")");
        MethodInfo existing = result.methodsByKey.get(key);
        if (existing != null) {
            return existing;
        }
        ClassInfo c = externalClass(declaring, Origin.LIBRARY, rmd.declaringType());
        MethodInfo m = new MethodInfo();
        m.key = key;
        m.classId = c.id;
        m.name = rmd.getName();
        m.signatureResolved = ptypes != null;
        m.signature = m.name + "(" + (ptypes == null ? "?" : String.join(", ", ptypes)) + ")";
        if (ptypes != null) {
            ptypes.forEach(t -> m.parameters.add(new MethodParameter(null, t)));
        }
        try {
            m.visibility = Visibility.of(rmd.accessSpecifier());
        } catch (RuntimeException e) {
            m.visibility = Visibility.UNKNOWN;
        }
        try {
            m.isStatic = rmd.isStatic();
            m.isAbstract = rmd.isAbstract();
        } catch (RuntimeException ignored) {
            // leave false
        }
        try {
            m.returnType = TypeNames.simplify(rmd.getReturnType().describe());
        } catch (RuntimeException e) {
            m.returnType = null;
        }
        m.origin = Origin.LIBRARY;
        m.depth = Integer.MAX_VALUE;
        register(m, c);
        return m;
    }

    /** 수신 타입과 메서드 이름·인자 수만 확인된 호출. 파라미터 타입은 추측하지 않는다. */
    private MethodInfo partialMethod(CallTarget t, String name, int argc) {
        String key = TypeNames.erasure(t.declaringType()) + "#" + name + "/" + argc + "?";
        MethodInfo existing = result.methodsByKey.get(key);
        if (existing != null) {
            return existing;
        }
        ClassInfo c = externalClass(t.declaringType(), t.origin(), null);
        MethodInfo m = new MethodInfo();
        m.key = key;
        m.classId = c.id;
        m.name = name;
        m.signatureResolved = false;
        m.signature = name + "(" + String.join(", ", java.util.Collections.nCopies(argc, "?")) + ")";
        m.visibility = Visibility.UNKNOWN;
        m.origin = t.origin();
        m.depth = Integer.MAX_VALUE;
        register(m, c);
        return m;
    }

    private void register(ClassInfo c, String prefix) {
        c.id = result.nextId(prefix);
        result.classesByKey.put(c.key, c);
        result.index(c.id, c);
    }

    private void register(MethodInfo m, ClassInfo c) {
        m.id = result.nextId("M");
        result.methodsByKey.put(m.key, m);
        result.index(m.id, m);
        c.methodIds.add(m.id);
    }

    private static String paramType(Parameter p) {
        return p.getType().asString() + (p.isVarArgs() ? "..." : "");
    }

    // ------------------------------------------------------------------ finish

    private void finish() {
        for (CallSiteInfo s : result.callSites) {
            if (s.expansion == Expansion.EXPANDED) {
                continue;
            }
            if (s.targetMethodId == null) {
                s.expansion = Expansion.NOT_APPLICABLE;
                continue;
            }
            MethodInfo t = result.method(s.expansionTargetId());
            if (t.origin != Origin.SOURCE) {
                s.expansion = Expansion.EXTERNAL;
            } else if (!t.hasBody) {
                s.expansion = Expansion.NO_BODY;
            } else if (t.analyzed) {
                if (isAncestorOrSelf(t.id, s.callerMethodId)) {
                    s.expansion = Expansion.CYCLE;
                } else {
                    s.expansion = Expansion.SHARED;
                    s.expandedAt = t.expandedAt;
                }
            } else {
                s.expansion = Expansion.DEPTH_LIMIT;
            }
        }
        for (MethodInfo m : result.methods()) {
            if (m.depth == Integer.MAX_VALUE) {
                m.depth = -1;
            }
            if (!m.analyzed) {
                m.notAnalyzedReason = m.origin != Origin.SOURCE ? Expansion.EXTERNAL
                        : !m.hasBody ? Expansion.NO_BODY : Expansion.DEPTH_LIMIT;
            }
        }
        Map<String, CallRelation> rel = new LinkedHashMap<>();
        for (CallSiteInfo s : result.callSites) {
            if (s.targetMethodId == null) {
                continue;
            }
            CallRelation r = rel.computeIfAbsent(s.callerMethodId + "->" + s.targetMethodId, k -> {
                CallRelation n = new CallRelation();
                n.fromMethodId = s.callerMethodId;
                n.toMethodId = s.targetMethodId;
                n.depth = s.depth;
                return n;
            });
            r.callSiteIds.add(s.id);
            r.depth = Math.min(r.depth, s.depth);
            r.cycle |= s.expansion == Expansion.CYCLE;
        }
        for (CallRelation r : rel.values()) {
            r.id = result.nextId("R");
            result.relations.add(r);
            result.index(r.id, r);
        }
    }

    private boolean isAncestorOrSelf(String candidate, String methodId) {
        String cur = methodId;
        while (cur != null) {
            if (cur.equals(candidate)) {
                return true;
            }
            cur = expansionParent.get(cur);
        }
        return false;
    }

    /** 감싸는 ExpressionStmt(선언·대입 포함) 안에서 다른 호출·생성자 인자/수신 객체가 아닌 가장 바깥 호출인지 */
    static boolean outermostInStatement(MethodCallExpr call) {
        Node p = call.getParentNode().orElse(null);
        while (p != null) {
            if (p instanceof MethodCallExpr || p instanceof ObjectCreationExpr) {
                return false;
            }
            if (p instanceof Statement st) {
                // 식 람다 본문(x -> list.add(x))은 바깥 호출 코드에 보인다
                return st instanceof ExpressionStmt && !(st.getParentNode().orElse(null) instanceof LambdaExpr);
            }
            p = p.getParentNode().orElse(null);
        }
        return false;
    }
}
