package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.CallRelation;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.ObjectInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.RequestSpecEntry;
import analyzer.model.TryResource;
import analyzer.model.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 공통 모델 → analysis.yaml (단일 문서, Source of Truth). 키는 camelCase.
 * 쓰고 나서 다시 읽어 {@link AnalysisSchema} 로 구조·참조를 검증한다.
 */
public final class AnalysisYamlWriter {
    public static final String SCHEMA_VERSION = "1.0";

    private final ObjectMapper mapper = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .build());

    /** 파일을 쓰고 스키마 검증. 실패하면 예외 */
    @SuppressWarnings("unchecked")
    public void write(AnalysisResult r, Path file) throws IOException {
        Files.writeString(file, mapper.writeValueAsString(document(r)));
        Map<String, Object> back = mapper.readValue(file.toFile(), Map.class);
        List<String> errors = AnalysisSchema.validate(back);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("analysis.yaml 스키마 검증 실패:\n" + String.join("\n", errors));
        }
    }

    public Map<String, Object> document(AnalysisResult r) {
        ClassInfo target = r.clazz(r.targetClassId);
        MethodInfo entry = r.method(r.entryMethodId);
        return Y.map()
                .put2("schemaVersion", SCHEMA_VERSION)
                .put2("analysis", Y.map()
                        .put2("targetClass", target.name)
                        .put2("targetClassQualifiedName", target.qualifiedName)
                        .put2("targetClassId", target.id)
                        .put2("targetMethod", entry.name)
                        .put2("targetMethodSignature", entry.signature)
                        .put2("entryMethodId", entry.id)
                        .put2("entrySelection", r.entrySelection)
                        .put2("depth", r.settings.get("max-depth"))
                        .put2("sourceRoot", r.sourceRoot)
                        .put2("sourceRoots", r.sourceRoots)
                        .put2("settings", r.settings))
                .put2("classes", classes(r))
                .put2("methods", methods(r))
                .put2("calls", calls(r))
                .put2("relations", relations(r))
                .put2("controlBlocks", blocks(r))
                .put2("requestParameters", parameters(r))
                .put2("requestSpec", requestSpec(r))
                .put2("objects", objects(r))
                .put2("excludedExternalLibraryCalls", r.excludedExternalCalls)
                .put2("notes", r.notes);
    }

    private List<Object> classes(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (ClassInfo c : r.classes()) {
            out.add(Y.map()
                    .put2("id", c.id)
                    .put2("name", c.name)
                    .put2("qualifiedName", c.qualifiedName)
                    .put2("package", c.packageName)
                    .put2("kind", c.kind)
                    .put2("abstract", c.isAbstract)
                    .put2("scope", c.scope)
                    .put2("origin", c.origin.name())
                    .put2("role", c.role.label())
                    .put2("roleDecidedBy", c.roleDecidedBy)
                    .put2("roleConflict", c.roleConflict)
                    .put2("roleEvidence", c.roleEvidence.stream().map(e -> Y.map().put2("kind", e.kind())
                            .put2("value", e.value()).put2("role", e.role().label())).toList())
                    .put2("extends", c.extendsTypes)
                    .put2("implements", c.implementsTypes)
                    .put2("annotations", c.annotations)
                    .put2("methodIds", c.methodIds)
                    .put2("location", Y.loc(c.location)));
        }
        return out;
    }

    private List<Object> methods(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (MethodInfo m : r.methods()) {
            out.add(Y.map()
                    .put2("id", m.id)
                    .put2("classId", m.classId)
                    .put2("class", r.classOf(m).name)
                    .put2("name", m.name)
                    .put2("signature", m.signature)
                    .put2("signatureResolved", m.signatureResolved)
                    .put2("visibility", m.visibility.text())
                    .put2("modifiersImplicit", m.modifiersImplicit)
                    .put2("static", m.isStatic)
                    .put2("abstract", m.isAbstract)
                    .put2("hasBody", m.hasBody)
                    .put2("returnType", m.returnType)
                    .put2("parameters", m.parameters.stream().map(p -> Y.map().put2("name", p.name()).put2("type", p.type())).toList())
                    .put2("throws", m.thrownTypes)
                    .put2("annotations", m.annotations)
                    .put2("origin", m.origin.name())
                    .put2("depth", m.depth)
                    .put2("analyzed", m.analyzed)
                    .put2("expandedAt", m.expandedAt)
                    .put2("notAnalyzedReason", m.notAnalyzedReason == null ? null : m.notAnalyzedReason.name())
                    .put2("location", Y.loc(m.location)));
        }
        return out;
    }

    private List<Object> calls(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (CallSiteInfo s : r.callSites) {
            out.add(Y.map()
                    .put2("id", s.id)
                    .put2("from", s.callerMethodId)
                    .put2("to", s.targetMethodId)
                    .put2("order", s.order)
                    .put2("seq", s.seq)
                    .put2("depth", s.depth)
                    .put2("expression", s.expression)
                    .put2("methodName", s.methodName)
                    .put2("arguments", s.arguments)
                    .put2("resultUsage", s.resultUsage)
                    .put2("scope", s.scopeExpression)
                    .put2("receiverType", s.receiverType)
                    .put2("resolution", s.resolution.name())
                    .put2("resolutionBasis", s.resolutionBasis)
                    .put2("resolutionReason", s.resolutionReason)
                    .put2("candidates", s.candidates)
                    .put2("targetOrigin", s.targetOrigin.name())
                    .put2("implementationMethodId", s.implementationMethodId)
                    .put2("implementationBasis", s.implementationBasis)
                    .put2("implementationReason", s.implementationReason)
                    .put2("implementationCandidates", s.implementationCandidates)
                    .put2("expansion", s.expansion.name())
                    .put2("expandedAt", s.expandedAt)
                    .put2("controlBlockId", s.controlBlockId)
                    .put2("position", s.position)
                    .put2("conditional", s.conditional)
                    .put2("conditions", s.conditions)
                    .put2("deferred", s.deferred)
                    .put2("requestParameterIds", r.parametersAt(s.id).stream().map(p -> p.id).toList())
                    .put2("bindingIds", r.bindings.stream().filter(b -> s.id.equals(b.callSiteId)).map(b -> b.id).toList())
                    .put2("location", Y.loc(s.location)));
        }
        return out;
    }

    private List<Object> relations(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (CallRelation rel : r.relations) {
            out.add(Y.map().put2("id", rel.id).put2("from", rel.fromMethodId).put2("to", rel.toMethodId)
                    .put2("depth", rel.depth).put2("cycle", rel.cycle).put2("callSiteIds", rel.callSiteIds));
        }
        return out;
    }

    private List<Object> blocks(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (ControlBlock b : r.blocks) {
            Y y = Y.map()
                    .put2("id", b.id)
                    .put2("type", b.type.yamlName())
                    .put2("methodId", b.methodId)
                    .put2("parentBlockId", b.parentBlockId)
                    .put2("relation", b.relation)
                    .put2("nestingDepth", b.nestingDepth)
                    .put2("seq", b.seq)
                    .opt("condition", b.condition)
                    .opt("deferred", b.deferred);
            switch (b.type) {
                case IF, ELSE_IF, ELSE -> y.opt("branchOrder", b.branchOrder);
                case CASE -> y.put2("caseLabels", b.caseLabels).put2("isDefault", b.isDefault);
                case TRY -> {
                    y.put2("catchBlockIds", b.catchBlockIds).put2("finallyBlockId", b.finallyBlockId);
                    List<Object> res = new ArrayList<>();
                    for (TryResource t : b.resources) {
                        res.add(Y.map().put2("variable", t.variable()).put2("type", t.type()).put2("initializer", t.initializer())
                                .put2("declarationOrder", t.declarationOrder()).put2("closeOrder", t.closeOrder())
                                .put2("location", Y.loc(t.location())));
                    }
                    y.opt("resources", res);
                }
                case CATCH -> y.put2("tryBlockId", b.tryBlockId).put2("catchOrder", b.catchOrder)
                        .put2("exceptionTypes", b.exceptionTypes).put2("exceptionVariable", b.exceptionVariable)
                        .put2("rethrows", b.rethrows).put2("throwsNewException", b.throwsNewException)
                        .put2("handlerCalls", b.handlerCalls).put2("logCalls", b.logCalls)
                        .put2("errorResponseCalls", b.errorResponseCalls);
                case FINALLY -> y.put2("tryBlockId", b.tryBlockId).put2("containsReturn", b.containsReturn)
                        .put2("containsThrow", b.containsThrow);
                case RETURN -> y.put2("expression", b.expression).put2("executesFinally", b.executesFinally);
                case THROW -> y.put2("expression", b.expression).put2("rethrow", b.rethrow)
                        .put2("exceptionResolution", b.exceptionResolution == null ? null : b.exceptionResolution.name())
                        .put2("caughtBy", b.caughtBy).put2("propagatesToCaller", b.propagatesToCaller)
                        .opt("exceptionReason", b.exceptionReason).put2("executesFinally", b.executesFinally);
                default -> {
                }
            }
            y.put2("callIds", b.callIds).put2("childBlockIds", b.childBlockIds).put2("location", Y.loc(b.location));
            out.add(y);
        }
        return out;
    }

    private List<Object> parameters(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (RequestParameterInfo p : r.parameters) {
            out.add(Y.map()
                    .put2("id", p.id)
                    .put2("name", p.name)
                    .put2("nameResolution", p.nameResolution)
                    .put2("nameExpression", p.nameExpression)
                    .put2("extraction", p.extraction)
                    .put2("variable", p.variable)
                    .put2("type", p.type)
                    .put2("defaultValue", p.defaultValue)
                    .put2("required", p.required)
                    .put2("methodId", p.sourceMethodId)
                    .put2("callSiteId", p.callSiteId)
                    .put2("location", Y.loc(p.location)));
        }
        return out;
    }

    private Map<String, Object> requestSpec(AnalysisResult r) {
        List<Object> spec = new ArrayList<>();
        for (RequestSpecEntry e : r.requestSpec) {
            spec.add(Y.map().put2("id", e.id).put2("name", e.name).put2("types", e.types)
                    .put2("defaultValues", e.defaultValues).put2("required", e.required).put2("extractions", e.extractions)
                    .put2("parameterIds", e.parameterIds).put2("methodIds", e.methodIds).put2("bindingIds", e.bindingIds));
        }
        List<Object> unknown = new ArrayList<>();
        List<Object> bulk = new ArrayList<>();
        for (RequestParameterInfo p : r.parameters) {
            if ("UNKNOWN".equals(p.nameResolution)) {
                unknown.add(Y.map().put2("parameterId", p.id).put2("nameExpression", p.nameExpression)
                        .put2("extraction", p.extraction).put2("methodId", p.sourceMethodId));
            } else if (!p.named()) {
                bulk.add(Y.map().put2("parameterId", p.id)
                        .put2("kind", "@ModelAttribute".equals(p.extraction) ? "OBJECT_BINDING" : p.nameResolution)
                        .put2("extraction", p.extraction).put2("variable", p.variable).put2("methodId", p.sourceMethodId));
            }
        }
        return Y.map().put2("parameters", spec).put2("unknownNameReads", unknown).put2("bulkReads", bulk);
    }

    private List<Object> objects(AnalysisResult r) {
        List<Object> out = new ArrayList<>();
        for (ObjectInfo o : r.objects) {
            List<Object> bs = new ArrayList<>();
            for (Binding b : r.bindingsOf(o)) {
                bs.add(Y.map()
                        .put2("id", b.id)
                        .put2("order", b.order)
                        .put2("field", b.field)
                        .put2("source", Labels.bindingSource(b))
                        .put2("assignment", b.assignment.toLowerCase())
                        .put2("method", b.method)
                        .put2("condition", Labels.bindingCondition(b))
                        .put2("transform", b.transforms.isEmpty() ? null : Labels.bindingTransform(b))
                        .put2("default", b.defaultValue)
                        .put2("value", b.valueExpression)
                        .put2("sources", b.sources.stream().map(AnalysisYamlWriter::source).toList())
                        .put2("transforms", b.transforms)
                        .put2("conditions", b.conditions)
                        .put2("reassigned", b.reassigned)
                        .put2("methodId", b.methodId)
                        .put2("callSiteId", b.callSiteId)
                        .put2("controlBlockId", b.controlBlockId)
                        .put2("resolution", b.resolution.name())
                        .put2("resolutionReason", b.resolutionReason)
                        .put2("location", Y.loc(b.location)));
            }
            out.add(Y.map()
                    .put2("id", o.id)
                    .put2("kind", o.kind)
                    .put2("class", o.className)
                    .put2("classId", o.classId)
                    .put2("variable", o.variable)
                    .put2("methodId", o.methodId)
                    .put2("fields", Y.map()
                            .put2("declared", o.declaredFields)
                            .put2("assigned", o.assignedFields)
                            .put2("conditional", o.conditionalFields)
                            .put2("initialOnly", o.initialOnlyFields)
                            .put2("unassigned", o.unassignedFields)
                            .put2("unknown", o.unknownFields))
                    .put2("bindings", bs));
        }
        return out;
    }

    private static Map<String, Object> source(ValueSource v) {
        return Y.map().put2("kind", v.kind()).put2("value", v.value()).put2("display", v.display())
                .put2("parameterId", v.parameterId()).put2("via", v.via());
    }
}
