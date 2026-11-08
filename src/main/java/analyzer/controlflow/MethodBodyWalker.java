package analyzer.controlflow;

import analyzer.controlflow.ExceptionTypeMatcher.Match;
import analyzer.controlflow.WalkContext.Region;
import analyzer.controlflow.WalkContext.TryFrame;
import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.ResolutionStatus;
import analyzer.model.SourceLocation;
import analyzer.model.TryResource;
import analyzer.parser.TypeNames;
import analyzer.resolver.TypeLookup;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 메서드 본문 AST 를 실행(평가) 순서대로 순회하며 제어 블록과 호출 위치를 기록한다.
 * 블록이 중첩되어도 메서드 호출 Depth 는 증가하지 않는다.
 */
public final class MethodBodyWalker {

    /** 호출 위치 해석·필터링을 담당하는 콜백 */
    public interface CallSink {
        /** 해석 결과를 채운 CallSiteInfo 를 반환. 출력에서 제외할 호출이면 null */
        CallSiteInfo resolve(MethodCallExpr call, WalkContext ctx);

        /** id/order/위치가 확정된 뒤 호출됨 */
        void recorded(CallSiteInfo site, MethodCallExpr call);
    }

    public record Options(boolean includeConditions, boolean includeExceptions) {
    }

    private record PendingThrow(ControlBlock block, List<TryFrame> frames, List<Type> thrown, boolean deferred) {
    }

    private record PendingExit(ControlBlock block, List<TryFrame> frames) {
    }

    private final AnalysisResult result;
    private final MethodInfo method;
    private final String file;
    private final Options options;
    private final CallSink sink;
    private final TypeLookup types;
    private final ExceptionTypeMatcher matcher = new ExceptionTypeMatcher();

    private final Map<String, List<Type>> catchTypes = new HashMap<>();
    private final List<PendingThrow> pendingThrows = new ArrayList<>();
    private final List<PendingExit> pendingExits = new ArrayList<>();
    private int seq;
    private int callOrder;

    public MethodBodyWalker(AnalysisResult result, MethodInfo method, String file, Options options, CallSink sink,
                            TypeLookup types) {
        this.result = result;
        this.method = method;
        this.file = file;
        this.options = options;
        this.sink = sink;
        this.types = types;
    }

    public void walk(CallableDeclaration<?> declaration) {
        Optional<BlockStmt> body = declaration instanceof com.github.javaparser.ast.body.MethodDeclaration m
                ? m.getBody()
                : Optional.of(((com.github.javaparser.ast.body.ConstructorDeclaration) declaration).getBody());
        body.ifPresent(b -> walkStmt(b, WalkContext.root()));
        resolvePendingExits();
        resolvePendingThrows();
    }

    // ------------------------------------------------------------------ statements

    private void walkStmt(Statement s, WalkContext ctx) {
        if (s instanceof BlockStmt b) {
            b.getStatements().forEach(x -> walkStmt(x, ctx));
        } else if (s instanceof ExpressionStmt e) {
            walkExpr(e.getExpression(), ctx);
        } else if (s instanceof IfStmt i) {
            walkIf(i, ctx);
        } else if (s instanceof SwitchStmt sw) {
            walkSwitch(sw.getSelector(), sw.getEntries(), sw, ctx);
        } else if (s instanceof ForStmt f) {
            walkFor(f, ctx);
        } else if (s instanceof ForEachStmt f) {
            walkForEach(f, ctx);
        } else if (s instanceof WhileStmt w) {
            walkWhile(w, ctx);
        } else if (s instanceof DoStmt d) {
            walkDo(d, ctx);
        } else if (s instanceof TryStmt t) {
            walkTry(t, ctx);
        } else if (s instanceof ReturnStmt r) {
            walkReturn(r, ctx);
        } else if (s instanceof ThrowStmt t) {
            walkThrow(t, ctx);
        } else if (s instanceof SynchronizedStmt sy) {
            walkExpr(sy.getExpression(), ctx);
            walkStmt(sy.getBody(), ctx);
        } else if (s instanceof LabeledStmt l) {
            walkStmt(l.getStatement(), ctx);
        } else if (s instanceof LocalClassDeclarationStmt || s instanceof LocalRecordDeclarationStmt) {
            note(s, "로컬 클래스/레코드 선언 본문은 분석하지 않음");
        } else if (s instanceof AssertStmt a) {
            walkExpr(a.getCheck(), ctx);
            a.getMessage().ifPresent(m -> walkExpr(m, guard(ctx, "assert 실패 시: " + text(a.getCheck()))));
        } else if (s instanceof YieldStmt y) {
            walkExpr(y.getExpression(), ctx);
        } else if (s instanceof ExplicitConstructorInvocationStmt ec) {
            ec.getArguments().forEach(a -> walkExpr(a, ctx));
        } else {
            walkChildren(s, ctx);
        }
    }

    /** 일반 노드: 자식 표현식/문장을 순서대로 순회 (VariableDeclarator 초기화식 등 포함) */
    private void walkChildren(Node n, WalkContext ctx) {
        for (Node child : n.getChildNodes()) {
            if (child instanceof Expression ce) {
                walkExpr(ce, ctx);
            } else if (child instanceof Statement cs) {
                walkStmt(cs, ctx);
            } else if (child instanceof VariableDeclarator vd) {
                vd.getInitializer().ifPresent(init -> walkExpr(init, ctx));
            } else if (child instanceof com.github.javaparser.ast.ArrayCreationLevel acl) {
                acl.getDimension().ifPresent(d -> walkExpr(d, ctx));
            }
        }
    }

    private void walkIf(IfStmt ifs, WalkContext ctx) {
        if (!options.includeConditions()) {
            walkExpr(ifs.getCondition(), ctx);
            walkStmt(ifs.getThenStmt(), ctx.conditionalWith(null));
            ifs.getElseStmt().ifPresent(e -> walkStmt(e, ctx.conditionalWith(null)));
            return;
        }
        String c1 = text(ifs.getCondition());
        ControlBlock primary = newBlock(BlockType.IF, ctx, null, ifs);
        primary.condition = c1;
        primary.branchOrder = 1;
        walkExpr(ifs.getCondition(), ctx.at(primary, "CONDITION"));
        walkStmt(ifs.getThenStmt(), ctx.at(primary, "BODY").conditionalWith("if (" + c1 + ")"));

        List<String> prior = new ArrayList<>(List.of(c1));
        int order = 1;
        Optional<Statement> els = ifs.getElseStmt();
        while (els.isPresent()) {
            Statement e = els.get();
            order++;
            if (e instanceof IfStmt ei) {
                String c = text(ei.getCondition());
                ControlBlock b = newBlock(BlockType.ELSE_IF, ctx, primary, ei);
                b.condition = c;
                b.branchOrder = order;
                walkExpr(ei.getCondition(), ctx.at(b, "CONDITION").conditionalWith("none of: " + String.join(" | ", prior)));
                walkStmt(ei.getThenStmt(), ctx.at(b, "BODY").conditionalWith("else if (" + c + ")"));
                prior.add(c);
                els = ei.getElseStmt();
            } else {
                ControlBlock b = newBlock(BlockType.ELSE, ctx, primary, e);
                b.branchOrder = order;
                walkStmt(e, ctx.at(b, "BODY").conditionalWith("else (none of: " + String.join(" | ", prior) + ")"));
                els = Optional.empty();
            }
        }
    }

    private void walkSwitch(Expression selector, NodeList<SwitchEntry> entries, Node node, WalkContext ctx) {
        if (!options.includeConditions()) {
            walkExpr(selector, ctx);
            for (SwitchEntry entry : entries) {
                entry.getStatements().forEach(st -> walkStmt(st, ctx.conditionalWith(null)));
            }
            return;
        }
        String sel = text(selector);
        ControlBlock sw = newBlock(BlockType.SWITCH, ctx, null, node);
        sw.condition = sel;
        walkExpr(selector, ctx.at(sw, "SELECTOR"));
        int order = 0;
        for (SwitchEntry entry : entries) {
            ControlBlock cb = newBlock(BlockType.CASE, ctx, sw, entry);
            entry.getLabels().forEach(l -> cb.caseLabels.add(text(l)));
            cb.isDefault = entry.isDefault() || entry.getLabels().isEmpty();
            cb.branchOrder = ++order;
            String g = "switch (" + sel + ") " + (cb.isDefault && cb.caseLabels.isEmpty()
                    ? "default" : "case " + String.join(", ", cb.caseLabels) + (cb.isDefault ? ", default" : ""));
            WalkContext cc = ctx.at(cb, "BODY").conditionalWith(g);
            entry.getStatements().forEach(st -> walkStmt(st, cc));
        }
    }

    private void walkFor(ForStmt f, WalkContext ctx) {
        String header = f.getInitialization().stream().map(MethodBodyWalker::text).reduce((a, b) -> a + ", " + b).orElse("")
                + "; " + f.getCompare().map(MethodBodyWalker::text).orElse("")
                + "; " + f.getUpdate().stream().map(MethodBodyWalker::text).reduce((a, b) -> a + ", " + b).orElse("");
        if (!options.includeConditions()) {
            f.getInitialization().forEach(e -> walkExpr(e, ctx));
            f.getCompare().ifPresent(e -> walkExpr(e, ctx));
            walkStmt(f.getBody(), ctx.conditionalWith(null));
            f.getUpdate().forEach(e -> walkExpr(e, ctx.conditionalWith(null)));
            return;
        }
        ControlBlock b = newBlock(BlockType.FOR, ctx, null, f);
        b.condition = header;
        f.getInitialization().forEach(e -> walkExpr(e, ctx.at(b, "INITIALIZER")));
        f.getCompare().ifPresent(e -> walkExpr(e, ctx.at(b, "CONDITION")));
        WalkContext body = ctx.at(b, "BODY").conditionalWith("for (" + header + ")");
        walkStmt(f.getBody(), body);
        f.getUpdate().forEach(e -> walkExpr(e, body.position("UPDATE")));
    }

    private void walkForEach(ForEachStmt f, WalkContext ctx) {
        String header = text(f.getVariable()) + " : " + text(f.getIterable());
        if (!options.includeConditions()) {
            walkExpr(f.getIterable(), ctx);
            walkStmt(f.getBody(), ctx.conditionalWith(null));
            return;
        }
        ControlBlock b = newBlock(BlockType.FOREACH, ctx, null, f);
        b.condition = header;
        walkExpr(f.getIterable(), ctx.at(b, "ITERABLE"));
        walkStmt(f.getBody(), ctx.at(b, "BODY").conditionalWith("for (" + header + ")"));
    }

    private void walkWhile(WhileStmt w, WalkContext ctx) {
        if (!options.includeConditions()) {
            walkExpr(w.getCondition(), ctx);
            walkStmt(w.getBody(), ctx.conditionalWith(null));
            return;
        }
        ControlBlock b = newBlock(BlockType.WHILE, ctx, null, w);
        b.condition = text(w.getCondition());
        walkExpr(w.getCondition(), ctx.at(b, "CONDITION"));
        walkStmt(w.getBody(), ctx.at(b, "BODY").conditionalWith("while (" + b.condition + ")"));
    }

    private void walkDo(DoStmt d, WalkContext ctx) {
        if (!options.includeConditions()) {
            walkStmt(d.getBody(), ctx);
            walkExpr(d.getCondition(), ctx);
            return;
        }
        ControlBlock b = newBlock(BlockType.DO_WHILE, ctx, null, d);
        b.condition = text(d.getCondition());
        // do 본문은 최소 1회 실행되므로 조건부가 아니다
        walkStmt(d.getBody(), ctx.at(b, "BODY"));
        walkExpr(d.getCondition(), ctx.at(b, "CONDITION"));
    }

    private void walkTry(TryStmt t, WalkContext ctx) {
        if (!options.includeExceptions()) {
            t.getResources().forEach(r -> walkExpr(r, ctx));
            walkStmt(t.getTryBlock(), ctx);
            t.getCatchClauses().forEach(c -> walkStmt(c.getBody(), ctx.conditionalWith(null)));
            t.getFinallyBlock().ifPresent(f -> walkStmt(f, ctx));
            return;
        }
        boolean hasFinally = t.getFinallyBlock().isPresent();
        ControlBlock tb = newBlock(BlockType.TRY, ctx, null, t);
        tb.bodyText = String.join(" ", t.getTryBlock().getStatements().stream().map(MethodBodyWalker::text).toList());
        WalkContext tryCtx = ctx.at(tb, "BODY").push(new TryFrame(tb, hasFinally, Region.TRY_BODY, null));

        NodeList<Expression> res = t.getResources();
        for (int i = 0; i < res.size(); i++) {
            Expression r = res.get(i);
            String var;
            String type = null;
            String init = null;
            if (r instanceof VariableDeclarationExpr v) {
                VariableDeclarator d = v.getVariable(0);
                var = d.getNameAsString();
                type = d.getType().asString();
                init = d.getInitializer().map(MethodBodyWalker::text).orElse(null);
            } else {
                var = text(r);
            }
            tb.resources.add(new TryResource(var, type, init, i + 1, res.size() - i, SourceLocation.of(r, file)));
            walkExpr(r, tryCtx.position("RESOURCE"));
        }
        walkStmt(t.getTryBlock(), tryCtx);

        int order = 0;
        for (CatchClause c : t.getCatchClauses()) {
            ControlBlock cb = newBlock(BlockType.CATCH, ctx, tb, c);
            cb.tryBlockId = tb.id;
            cb.catchOrder = ++order;
            cb.exceptionVariable = c.getParameter().getNameAsString();
            List<Type> typesOfCatch = new ArrayList<>();
            Type pt = c.getParameter().getType();
            if (pt instanceof UnionType u) {
                typesOfCatch.addAll(u.getElements());
            } else {
                typesOfCatch.add(pt);
            }
            typesOfCatch.forEach(x -> cb.exceptionTypes.add(x.asString()));
            catchTypes.put(cb.id, typesOfCatch);
            describeHandler(c, cb);
            tb.catchBlockIds.add(cb.id);
            WalkContext cc = ctx.at(cb, "BODY")
                    .conditionalWith("catch (" + String.join(" | ", cb.exceptionTypes) + ")")
                    .push(new TryFrame(tb, hasFinally, Region.CATCH, cb));
            walkStmt(c.getBody(), cc);
        }
        if (hasFinally) {
            BlockStmt fin = t.getFinallyBlock().get();
            ControlBlock fb = newBlock(BlockType.FINALLY, ctx, tb, fin);
            fb.tryBlockId = tb.id;
            tb.finallyBlockId = fb.id;
            walkStmt(fin, ctx.at(fb, "BODY").push(new TryFrame(tb, true, Region.FINALLY, null)));
        }
    }

    private void walkReturn(ReturnStmt r, WalkContext ctx) {
        ControlBlock rb = newBlock(BlockType.RETURN, ctx, null, r);
        rb.expression = r.getExpression().map(MethodBodyWalker::text).orElse(null);
        r.getExpression().ifPresent(e -> walkExpr(e, ctx.at(rb, "VALUE")));
        markFinallyContains(ctx, true);
        if (!ctx.deferred()) {
            pendingExits.add(new PendingExit(rb, ctx.frames()));
        }
    }

    private void walkThrow(ThrowStmt t, WalkContext ctx) {
        if (!options.includeExceptions()) {
            walkExpr(t.getExpression(), ctx);
            return;
        }
        ControlBlock tb = newBlock(BlockType.THROW, ctx, null, t);
        Expression ex = t.getExpression();
        tb.expression = text(ex);
        walkExpr(ex, ctx.at(tb, "VALUE"));

        List<Type> thrown = new ArrayList<>();
        TryFrame enclosingCatch = null;
        for (int i = ctx.frames().size() - 1; i >= 0; i--) {
            if (ctx.frames().get(i).region() == Region.CATCH) {
                enclosingCatch = ctx.frames().get(i);
                break;
            }
        }
        if (ex instanceof ObjectCreationExpr oce) {
            thrown.add(oce.getType());
        } else if (ex instanceof NameExpr n) {
            if (enclosingCatch != null && n.getNameAsString().equals(enclosingCatch.catchBlock().exceptionVariable)) {
                tb.rethrow = true;
                thrown.addAll(catchTypes.getOrDefault(enclosingCatch.catchBlock().id, List.of()));
            } else {
                types.findVariableType(n.getNameAsString(), t)
                        .filter(ty -> !ty.isVarType() && !ty.isUnknownType())
                        .ifPresent(thrown::add);
            }
        }
        thrown.forEach(ty -> tb.exceptionTypes.add(ty.asString()));
        if (enclosingCatch != null) {
            if (tb.rethrow) {
                enclosingCatch.catchBlock().rethrows = true;
            } else {
                enclosingCatch.catchBlock().throwsNewException = true;
            }
        }
        markFinallyContains(ctx, false);
        pendingThrows.add(new PendingThrow(tb, ctx.frames(), thrown, ctx.deferred()));
    }

    private void markFinallyContains(WalkContext ctx, boolean isReturn) {
        if (ctx.deferred()) {
            return;
        }
        for (int i = ctx.frames().size() - 1; i >= 0; i--) {
            TryFrame f = ctx.frames().get(i);
            if (f.region() == Region.FINALLY) {
                ControlBlock fb = result.block(f.tryBlock().finallyBlockId);
                if (isReturn) {
                    fb.containsReturn = true;
                } else {
                    fb.containsThrow = true;
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ expressions

    private void walkExpr(Expression e, WalkContext ctx) {
        if (e instanceof MethodCallExpr mc) {
            // 평가 순서: 수신 객체 → 인자 → 호출
            mc.getScope().ifPresent(s -> walkExpr(s, ctx));
            mc.getArguments().forEach(a -> walkExpr(a, ctx));
            record(mc, ctx);
        } else if (e instanceof LambdaExpr l) {
            walkStmt(l.getBody(), ctx.lambda());
        } else if (e instanceof ConditionalExpr c) {
            walkExpr(c.getCondition(), ctx);
            String cond = text(c.getCondition());
            walkExpr(c.getThenExpr(), guard(ctx, "(" + cond + ") ? true"));
            walkExpr(c.getElseExpr(), guard(ctx, "(" + cond + ") ? false"));
        } else if (e instanceof BinaryExpr b
                && (b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)) {
            walkExpr(b.getLeft(), ctx);
            String left = text(b.getLeft());
            walkExpr(b.getRight(), guard(ctx, b.getOperator() == BinaryExpr.Operator.AND
                    ? "(" + left + ") is true [&&]" : "(" + left + ") is false [||]"));
        } else if (e instanceof SwitchExpr sw) {
            walkSwitch(sw.getSelector(), sw.getEntries(), sw, ctx);
        } else if (e instanceof ObjectCreationExpr oc) {
            oc.getScope().ifPresent(s -> walkExpr(s, ctx));
            oc.getArguments().forEach(a -> walkExpr(a, ctx));
            if (oc.getAnonymousClassBody().isPresent()) {
                note(oc, "익명 클래스 본문은 분석하지 않음");
            }
        } else {
            walkChildren(e, ctx);
        }
    }

    private void record(MethodCallExpr mc, WalkContext ctx) {
        CallSiteInfo site = sink.resolve(mc, ctx);
        if (site == null) {
            return;
        }
        site.id = result.nextId("CALL");
        site.callerMethodId = method.id;
        site.methodName = mc.getNameAsString();
        site.argumentCount = mc.getArguments().size();
        site.expression = text(mc);
        site.scopeExpression = mc.getScope().map(MethodBodyWalker::text).orElse(null);
        site.order = ++callOrder;
        site.seq = ++seq;
        site.controlBlockId = ctx.block() == null ? null : ctx.block().id;
        site.position = ctx.position();
        site.conditional = ctx.conditional();
        site.conditions.addAll(ctx.guards());
        site.deferred = ctx.deferred();
        site.location = SourceLocation.of(mc, file);
        mc.getArguments().forEach(a -> site.arguments.add(text(a)));
        site.resultUsage = resultUsage(mc);
        site.resultType = declaredType(mc);
        enclosingStatement(mc, site);
        site.inArgument = inArgument(mc);
        result.callSites.add(site);
        result.index(site.id, site);
        if (ctx.block() != null) {
            ctx.block().callIds.add(site.id);
        }
        sink.recorded(site, mc);
    }

    // ------------------------------------------------------------------ post-processing

    /** return/throw 로 빠져나가며 실행되는 finally (try 본문·catch 영역일 때만) */
    private void resolvePendingExits() {
        for (PendingExit p : pendingExits) {
            p.block().executesFinally.addAll(finallyPath(p.frames(), p.frames().size() - 1, -1));
        }
    }

    private List<String> finallyPath(List<TryFrame> frames, int from, int toExclusive) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i > toExclusive; i--) {
            TryFrame f = frames.get(i);
            if (f.hasFinally() && f.region() != Region.FINALLY) {
                ids.add(f.tryBlock().finallyBlockId);
            }
        }
        return ids;
    }

    private void resolvePendingThrows() {
        for (PendingThrow p : pendingThrows) {
            ControlBlock b = p.block();
            if (p.deferred()) {
                b.exceptionResolution = ResolutionStatus.UNRESOLVED;
                b.exceptionReason = "람다 등 지연 실행 위치의 throw — 전파 경로는 호출 시점에 결정됨";
                continue;
            }
            if (p.thrown().isEmpty()) {
                b.exceptionResolution = ResolutionStatus.UNRESOLVED;
                b.exceptionReason = "throw 표현식의 예외 타입을 정적으로 확인할 수 없음";
                continue;
            }
            List<TryFrame> frames = p.frames();
            String caughtBy = null;
            int caughtFrame = -1;
            String unknownReason = null;
            outer:
            for (int i = frames.size() - 1; i >= 0; i--) {
                TryFrame f = frames.get(i);
                if (f.region() != Region.TRY_BODY) {
                    continue; // catch/finally 안의 throw 는 같은 try 의 catch 에 잡히지 않는다
                }
                for (String catchId : f.tryBlock().catchBlockIds) {
                    Match m = matchAll(p.thrown(), catchTypes.get(catchId));
                    if (m == Match.YES) {
                        caughtBy = catchId;
                        caughtFrame = i;
                        break outer;
                    }
                    if (m == Match.UNKNOWN) {
                        unknownReason = "예외 타입 " + b.exceptionTypes + " 과 catch " + result.block(catchId).exceptionTypes
                                + " 의 관계를 확정할 수 없음";
                        caughtFrame = i;
                        break outer;
                    }
                }
            }
            if (unknownReason != null) {
                b.exceptionResolution = ResolutionStatus.UNRESOLVED;
                b.exceptionReason = unknownReason;
                b.executesFinally.addAll(finallyPath(frames, frames.size() - 1, caughtFrame));
            } else if (caughtBy != null) {
                b.exceptionResolution = ResolutionStatus.RESOLVED;
                b.caughtBy = caughtBy;
                b.propagatesToCaller = false;
                b.executesFinally.addAll(finallyPath(frames, frames.size() - 1, caughtFrame));
            } else {
                b.exceptionResolution = ResolutionStatus.RESOLVED;
                b.propagatesToCaller = true;
                b.exceptionReason = "메서드 내에서 잡는 catch 없음 → 호출자로 전파";
                b.executesFinally.addAll(finallyPath(frames, frames.size() - 1, -1));
            }
        }
    }

    private Match matchAll(List<Type> thrown, List<Type> caught) {
        boolean anyYes = false;
        boolean anyNo = false;
        for (Type t : thrown) {
            Match best = Match.NO;
            for (Type c : caught) {
                Match m = matcher.match(t, c);
                if (m == Match.YES) {
                    best = Match.YES;
                    break;
                }
                if (m == Match.UNKNOWN) {
                    best = Match.UNKNOWN;
                }
            }
            if (best == Match.UNKNOWN) {
                return Match.UNKNOWN;
            }
            anyYes |= best == Match.YES;
            anyNo |= best == Match.NO;
        }
        if (anyYes && anyNo) {
            return Match.UNKNOWN; // multi-catch 재던지기 중 일부만 잡히는 경우
        }
        return anyYes ? Match.YES : Match.NO;
    }

    // ------------------------------------------------------------------ helpers

    private static final java.util.Set<String> LOG_METHODS = java.util.Set.of(
            "trace", "debug", "info", "warn", "error", "fatal", "printStackTrace");
    private static final java.util.Set<String> ERROR_RESPONSE_METHODS = java.util.Set.of(
            "setAttribute", "sendError", "setStatus", "sendRedirect", "findForward", "saveErrors", "addMessage",
            "status", "badRequest", "internalServerError");

    /** catch 본문의 호출을 처리 방식(로그/오류 응답) 근거로 기록한다. 라이브러리 호출도 포함(소스 원문 기준). */
    private void describeHandler(CatchClause c, ControlBlock cb) {
        for (MethodCallExpr mc : c.getBody().findAll(MethodCallExpr.class)) {
            String t = text(mc);
            cb.handlerCalls.add(t);
            String name = mc.getNameAsString();
            String scope = mc.getScope().map(Node::toString).orElse("").toLowerCase();
            if (LOG_METHODS.contains(name) && (scope.contains("log") || name.equals("printStackTrace"))
                    || name.toLowerCase().contains("log")) {
                cb.logCalls.add(t);
            }
            if (ERROR_RESPONSE_METHODS.contains(name)) {
                cb.errorResponseCalls.add(t);
            }
        }
    }

    /** 다른 호출·객체 생성의 인자 안(깊이 무관)에 있는지 — 수신 객체 체인(a.b().c())은 인자가 아님 */
    private static boolean inArgument(MethodCallExpr mc) {
        Node child = mc;
        for (Node p = mc.getParentNode().orElse(null); p != null && !(p instanceof com.github.javaparser.ast.stmt.Statement)
                && !(p instanceof LambdaExpr); p = p.getParentNode().orElse(null)) {
            Node c0 = child;
            if (p instanceof MethodCallExpr outer && outer.getArguments().stream().anyMatch(a -> a == c0)
                    || p instanceof ObjectCreationExpr oc && oc.getArguments().stream().anyMatch(a -> a == c0)) {
                return true;
            }
            child = p;
        }
        return false;
    }

    /** 호출이 들어 있는 변수 선언/대입 문장 (람다·다른 문장 경계에서 멈춤) */
    private void enclosingStatement(MethodCallExpr mc, CallSiteInfo site) {
        for (Node p = mc.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof LambdaExpr || p instanceof CallableDeclaration<?>) {
                return;
            }
            if (p instanceof com.github.javaparser.ast.stmt.Statement st) {
                if (st instanceof com.github.javaparser.ast.stmt.ExpressionStmt es) {
                    Expression e = es.getExpression();
                    if (e instanceof VariableDeclarationExpr || e instanceof com.github.javaparser.ast.expr.AssignExpr) {
                        String t = text(e);
                        site.statement = t.endsWith(";") ? t : t + ";";
                        site.statementKind = e instanceof VariableDeclarationExpr ? "DECLARATION" : "ASSIGNMENT";
                        site.statementLocation = SourceLocation.of(es, file);
                    }
                }
                return;
            }
        }
    }

    /** T x = call() 이면 T (괄호·캐스트 건너뜀), 아니면 null */
    private static String declaredType(MethodCallExpr mc) {
        Node cur = mc;
        Node p = cur.getParentNode().orElse(null);
        while (p instanceof com.github.javaparser.ast.expr.EnclosedExpr || p instanceof com.github.javaparser.ast.expr.CastExpr) {
            cur = p;
            p = p.getParentNode().orElse(null);
        }
        return p instanceof VariableDeclarator v ? v.getTypeAsString() : null;
    }

    /** 호출 결과가 어떻게 쓰이는지 (괄호·캐스트는 건너뜀) */
    private static String resultUsage(MethodCallExpr mc) {
        Node cur = mc;
        Node p = cur.getParentNode().orElse(null);
        while (p instanceof com.github.javaparser.ast.expr.EnclosedExpr || p instanceof com.github.javaparser.ast.expr.CastExpr) {
            cur = p;
            p = p.getParentNode().orElse(null);
        }
        if (p instanceof VariableDeclarator v) {
            return "assigned:" + v.getNameAsString();
        }
        if (p instanceof com.github.javaparser.ast.expr.AssignExpr a && a.getValue() == cur) {
            return "assigned:" + text(a.getTarget());
        }
        if (p instanceof ReturnStmt) {
            return "returned";
        }
        if (p instanceof ExpressionStmt) {
            return "discarded";
        }
        if (p instanceof MethodCallExpr outer && outer.getArguments().contains(cur)
                || p instanceof ObjectCreationExpr) {
            return "argument";
        }
        if (p instanceof IfStmt || p instanceof WhileStmt || p instanceof ConditionalExpr c && c.getCondition() == cur
                || p instanceof BinaryExpr || p instanceof com.github.javaparser.ast.expr.UnaryExpr) {
            return "condition";
        }
        if (p instanceof MethodCallExpr outer && outer.getScope().filter(x -> x == mc).isPresent()) {
            return "receiver";
        }
        return "expression";
    }

    private ControlBlock newBlock(BlockType type, WalkContext ctx, ControlBlock branchParent, Node node) {
        ControlBlock b = new ControlBlock();
        b.id = result.nextId(type.idPrefix());
        b.type = type;
        b.methodId = method.id;
        if (branchParent != null) {
            b.parentBlockId = branchParent.id;
            b.relation = "BRANCH";
            b.nestingDepth = branchParent.nestingDepth;
            branchParent.childBlockIds.add(b.id);
        } else if (ctx.block() != null) {
            b.parentBlockId = ctx.block().id;
            b.relation = "BODY";
            b.nestingDepth = ctx.block().nestingDepth + 1;
            ctx.block().childBlockIds.add(b.id);
        } else {
            b.nestingDepth = 0;
        }
        b.seq = ++seq;
        b.deferred = ctx.deferred();
        b.location = SourceLocation.of(node, file);
        result.blocks.add(b);
        result.index(b.id, b);
        return b;
    }

    private WalkContext guard(WalkContext ctx, String text) {
        return ctx.conditionalWith(options.includeConditions() ? text : null);
    }

    private void note(Node n, String msg) {
        result.notes.add(method.id + " " + SourceLocation.of(n, file).shortText() + ": " + msg);
    }

    static String text(Node n) {
        return analyzer.parser.Code.text(n); // 주석 제외, 줄이지 않음
    }

    static String simple(String type) {
        return TypeNames.simplify(type);
    }
}
