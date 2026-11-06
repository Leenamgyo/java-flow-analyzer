package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.CallRelation;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.TryResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 공통 모델 → classes / call-relations / control-blocks / parameters YAML */
public final class YamlWriter {
    private final ObjectMapper mapper = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .build());

    public void write(AnalysisResult r, Path dir) throws IOException {
        Files.writeString(dir.resolve("classes.yaml"), toYaml(classes(r)));
        Files.writeString(dir.resolve("call-relations.yaml"), toYaml(calls(r)));
        Files.writeString(dir.resolve("control-blocks.yaml"), toYaml(blocks(r)));
        Files.writeString(dir.resolve("parameters.yaml"), toYaml(parameters(r)));
    }

    public String toYaml(Object o) throws IOException {
        return mapper.writeValueAsString(o);
    }

    public Map<String, Object> classes(AnalysisResult r) {
        List<Object> classes = new ArrayList<>();
        for (ClassInfo c : r.classes()) {
            List<Object> methods = new ArrayList<>();
            for (String mid : c.methodIds) {
                methods.add(method(r.method(mid)));
            }
            List<Object> evidence = c.roleEvidence.stream()
                    .map(e -> (Object) Y.map().put2("kind", e.kind()).put2("value", e.value()).put2("role", e.role().label()))
                    .toList();
            classes.add(Y.map()
                    .put2("id", c.id)
                    .put2("name", c.name)
                    .put2("qualified-name", c.qualifiedName)
                    .put2("package", c.packageName)
                    .put2("kind", c.kind)
                    .opt("abstract", c.isAbstract)
                    .put2("origin", c.origin.name())
                    .put2("scope", c.scope)
                    .put2("role", c.role.label())
                    .put2("role-decided-by", c.roleDecidedBy)
                    .put2("role-conflict", c.roleConflict)
                    .put2("role-evidence", evidence)
                    .opt("annotations", c.annotations)
                    .opt("extends", c.extendsTypes)
                    .opt("implements", c.implementsTypes)
                    .opt("location", Y.loc(c.location))
                    .put2("methods", methods));
        }
        Y meta = Y.map()
                .put2("source-root", r.sourceRoot)
                .put2("target-class-id", r.targetClassId)
                .put2("entry-method-id", r.entryMethodId)
                .put2("entry-selection", r.entrySelection)
                .put2("settings", r.settings);
        return Y.map().put2("analysis", meta).put2("classes", classes);
    }

    private Y method(MethodInfo m) {
        List<Object> params = m.parameters.stream()
                .map(p -> (Object) Y.map().put2("name", p.name()).put2("type", p.type())).toList();
        return Y.map()
                .put2("id", m.id)
                .put2("key", m.key)
                .put2("name", m.name)
                .put2("signature", m.signature)
                .put2("signature-resolved", m.signatureResolved)
                .put2("visibility", m.visibility.text())
                .opt("modifiers-implicit", m.modifiersImplicit)
                .put2("static", m.isStatic)
                .put2("abstract", m.isAbstract)
                .put2("has-body", m.hasBody)
                .put2("return-type", m.returnType)
                .put2("parameters", params)
                .opt("throws", m.thrownTypes)
                .opt("annotations", m.annotations)
                .put2("origin", m.origin.name())
                .opt("location", Y.loc(m.location))
                .put2("depth", m.depth)
                .put2("analyzed", m.analyzed)
                .opt("expanded-at", m.expandedAt)
                .opt("not-analyzed-reason", m.notAnalyzedReason == null ? null : m.notAnalyzedReason.name());
    }

    public Map<String, Object> calls(AnalysisResult r) {
        List<Object> sites = new ArrayList<>();
        for (CallSiteInfo s : r.callSites) {
            sites.add(Y.map()
                    .put2("id", s.id)
                    .put2("from", s.callerMethodId)
                    .put2("to", s.targetMethodId)
                    .put2("order", s.order)
                    .put2("seq", s.seq)
                    .put2("depth", s.depth)
                    .put2("expression", s.expression)
                    .put2("method-name", s.methodName)
                    .put2("argument-count", s.argumentCount)
                    .opt("scope", s.scopeExpression)
                    .opt("receiver-type", s.receiverType)
                    .put2("resolved", s.resolution == analyzer.model.ResolutionStatus.RESOLVED)
                    .put2("resolution", s.resolution.name())
                    .put2("resolution-basis", s.resolutionBasis)
                    .opt("resolution-reason", s.resolutionReason)
                    .opt("candidates", s.candidates)
                    .put2("target-origin", s.targetOrigin.name())
                    .put2("expansion", s.expansion.name())
                    .opt("expanded-at", s.expandedAt)
                    .put2("control-block-id", s.controlBlockId)
                    .put2("position", s.position)
                    .put2("conditional", s.conditional)
                    .opt("conditions", s.conditions)
                    .opt("deferred", s.deferred)
                    .put2("location", Y.loc(s.location)));
        }
        List<Object> rels = new ArrayList<>();
        for (CallRelation rel : r.relations) {
            rels.add(Y.map()
                    .put2("id", rel.id)
                    .put2("from", rel.fromMethodId)
                    .put2("to", rel.toMethodId)
                    .put2("depth", rel.depth)
                    .put2("cycle", rel.cycle)
                    .put2("call-site-ids", rel.callSiteIds));
        }
        return Y.map().put2("calls", sites).put2("relations", rels)
                .put2("excluded-external-library-calls", r.excludedExternalCalls);
    }

    public Map<String, Object> blocks(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (ControlBlock b : r.blocks) {
            Y y = Y.map()
                    .put2("id", b.id)
                    .put2("type", b.type.yamlName())
                    .put2("parent-method-id", b.methodId)
                    .put2("parent-block-id", b.parentBlockId)
                    .opt("relation", b.relation)
                    .put2("nesting-depth", b.nestingDepth)
                    .put2("seq", b.seq)
                    .opt("condition", b.condition)
                    .opt("deferred", b.deferred);
            switch (b.type) {
                case IF, ELSE_IF, ELSE -> y.opt("branch-order", b.branchOrder);
                case CASE -> y.opt("branch-order", b.branchOrder).put2("labels", b.caseLabels).put2("default", b.isDefault);
                case TRY -> {
                    y.put2("catch-block-ids", b.catchBlockIds).put2("finally-block-id", b.finallyBlockId);
                    if (!b.resources.isEmpty()) {
                        List<Object> res = new ArrayList<>();
                        for (TryResource t : b.resources) {
                            res.add(Y.map().put2("variable", t.variable()).put2("type", t.type())
                                    .put2("initializer", t.initializer()).put2("declaration-order", t.declarationOrder())
                                    .put2("close-order", t.closeOrder()).put2("auto-close", true)
                                    .put2("location", Y.loc(t.location())));
                        }
                        y.put2("try-with-resources", true).put2("resources", res)
                                .put2("resource-note", "리소스는 try 본문 종료 시(정상/예외 모두) 선언 역순으로 close() 되며, "
                                        + "그 후 catch/finally 가 실행된다. close() 호출은 소스에 없으므로 call 로 기록하지 않는다.");
                    }
                }
                case CATCH -> y.put2("try-block-id", b.tryBlockId).put2("catch-order", b.catchOrder)
                        .put2("exception-types", b.exceptionTypes).put2("multi-catch", b.exceptionTypes.size() > 1)
                        .put2("exception-variable", b.exceptionVariable).put2("rethrows", b.rethrows)
                        .put2("throws-new-exception", b.throwsNewException);
                case FINALLY -> y.put2("try-block-id", b.tryBlockId).put2("contains-return", b.containsReturn)
                        .put2("contains-throw", b.containsThrow)
                        .put2("alters-completion", b.containsReturn || b.containsThrow);
                case RETURN -> y.put2("expression", b.expression).put2("executes-finally", b.executesFinally);
                case THROW -> y.put2("expression", b.expression).put2("exception-types", b.exceptionTypes)
                        .put2("rethrow", b.rethrow)
                        .put2("exception-resolution", b.exceptionResolution == null ? null : b.exceptionResolution.name())
                        .put2("caught-by", b.caughtBy).put2("propagates-to-caller", b.propagatesToCaller)
                        .opt("exception-reason", b.exceptionReason).put2("executes-finally", b.executesFinally);
                default -> {
                }
            }
            y.put2("calls", b.callIds).put2("child-block-ids", b.childBlockIds).put2("location", Y.loc(b.location));
            out.add(y);
        }
        return Y.map().put2("blocks", out);
    }

    public Map<String, Object> parameters(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (RequestParameterInfo p : r.parameters) {
            out.add(Y.map()
                    .put2("id", p.id)
                    .put2("name", p.name)
                    .put2("name-resolution", p.nameResolution)
                    .opt("name-expression", p.nameExpression)
                    .put2("source-method", p.extraction)
                    .put2("variable", p.variable)
                    .put2("type", p.type)
                    .put2("default-value", p.defaultValue)
                    .opt("required", p.required)
                    .put2("parent-method-id", p.sourceMethodId)
                    .put2("call-site-id", p.callSiteId)
                    .put2("location", Y.loc(p.location)));
        }
        return Y.map().put2("parameters", out);
    }
}
