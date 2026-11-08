package analyzer.serializer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * analysis.yaml 내장 스키마 검증 (schemaVersion 1.0).
 * 필수 키·타입·열거값·ID 형식과 ID 참조(호출 → 메서드, 바인딩 → 객체/요청 파라미터 …)를 확인한다.
 * 비교 도구 등 다른 소비자가 같은 구조를 기대할 수 있도록 형식을 고정하는 용도다.
 */
public final class AnalysisSchema {
    private AnalysisSchema() {
    }

    private enum T { STR, INT, BOOL, LIST, MAP, STR_OR_NULL, INT_OR_NULL, BOOL_OR_NULL, MAP_OR_NULL }

    private record F(String name, T type, Set<String> allowed, String idPattern) {
        F(String name, T type) {
            this(name, type, null, null);
        }
    }

    private static F id(String name, String pattern) {
        return new F(name, T.STR, null, pattern);
    }

    private static F ref(String name, String pattern) {
        return new F(name, T.STR_OR_NULL, null, pattern);
    }

    private static F oneOf(String name, String... values) {
        return new F(name, T.STR, Set.of(values), null);
    }

    private static final String V = "V\\d+", VC = "VC\\d+";
    private static final String C = "C\\d+", M = "M\\d+", CALL = "CALL\\d+", P = "P\\d+", O = "O\\d+", B = "B\\d+",
            R = "R\\d+", BLOCK = "(IF|ELSEIF|ELSE|SW|CASE|FOR|FOREACH|WHILE|DO|T|CATCH|FINALLY|RET|THROW)\\d+";

    private static final List<F> CLASS = List.of(id("id", C), new F("name", T.STR), new F("role", T.STR),
            oneOf("origin", "SOURCE", "LIBRARY", "UNKNOWN"), new F("methodIds", T.LIST), new F("location", T.MAP_OR_NULL));
    private static final List<F> METHOD = List.of(id("id", M), id("classId", C), new F("name", T.STR),
            new F("signature", T.STR), oneOf("visibility", "public", "protected", "package-private", "private", "unknown"),
            new F("parameters", T.LIST), new F("depth", T.INT), new F("analyzed", T.BOOL),
            oneOf("origin", "SOURCE", "LIBRARY", "UNKNOWN"), new F("location", T.MAP_OR_NULL));
    private static final List<F> CALLS = List.of(id("id", CALL), id("from", M), ref("to", M), new F("order", T.INT),
            new F("depth", T.INT), new F("expression", T.STR), new F("arguments", T.LIST),
            oneOf("resolution", "RESOLVED", "PARTIALLY_RESOLVED", "UNRESOLVED", "AMBIGUOUS"),
            oneOf("expansion", "EXPANDED", "SHARED", "CYCLE", "DEPTH_LIMIT", "NO_BODY", "EXTERNAL", "NOT_APPLICABLE"),
            ref("controlBlockId", BLOCK), new F("conditional", T.BOOL), new F("requestParameterIds", T.LIST),
            new F("bindingIds", T.LIST), new F("location", T.MAP));
    private static final List<F> RELATION = List.of(id("id", R), id("from", M), id("to", M), new F("callSiteIds", T.LIST));
    private static final List<F> BLOCKS = List.of(id("id", BLOCK), new F("type", T.STR), id("methodId", M),
            ref("parentBlockId", BLOCK), new F("nestingDepth", T.INT), new F("callIds", T.LIST),
            new F("childBlockIds", T.LIST), new F("location", T.MAP));
    private static final List<F> PARAM = List.of(id("id", P), new F("name", T.STR_OR_NULL), new F("nameResolution", T.STR),
            new F("extraction", T.STR), id("methodId", M), ref("callSiteId", CALL), new F("location", T.MAP));
    private static final List<F> OBJECT = List.of(id("id", O), oneOf("kind", "VO", "MAP"), new F("class", T.STR),
            ref("classId", C), new F("variable", T.STR), ref("methodId", M), new F("fields", T.MAP), new F("bindings", T.LIST));
    private static final List<F> BINDING = List.of(id("id", B), new F("order", T.INT), new F("field", T.STR),
            new F("source", T.STR), oneOf("assignment", "setter", "builder", "constructor", "field", "map_put", "copy"),
            new F("method", T.STR), new F("condition", T.STR), new F("transform", T.STR_OR_NULL),
            new F("default", T.STR_OR_NULL), new F("sources", T.LIST), new F("conditions", T.LIST),
            id("methodId", M), ref("callSiteId", CALL), ref("controlBlockId", BLOCK),
            oneOf("resolution", "RESOLVED", "PARTIALLY_RESOLVED", "UNRESOLVED", "AMBIGUOUS"), new F("location", T.MAP));
    private static final List<F> VARIABLE = List.of(id("id", V), new F("name", T.STR), new F("type", T.STR),
            oneOf("kind", "LOCAL", "PARAMETER"), id("methodId", M), new F("location", T.MAP), new F("changes", T.LIST));
    private static final List<F> CHANGE = List.of(id("id", VC), new F("order", T.INT),
            oneOf("op", "INIT", "PARAM", "ASSIGN", "COMPOUND", "INCREMENT", "DECREMENT"), new F("operator", T.STR_OR_NULL),
            new F("expression", T.STR), new F("value", T.STR_OR_NULL), new F("sources", T.LIST), new F("condition", T.STR),
            new F("conditions", T.LIST), ref("controlBlockId", BLOCK), new F("callSiteIds", T.LIST), new F("location", T.MAP));
    private static final List<F> SOURCE = List.of(
            oneOf("kind", "REQUEST", "LITERAL", "CONSTANT", "PARAMETER", "CALL", "FIELD", "NEW", "LOOP", "UNRESOLVED"),
            new F("value", T.STR), new F("display", T.STR), ref("parameterId", P), new F("via", T.LIST));
    private static final List<F> LOCATION = List.of(new F("file", T.STR), new F("beginLine", T.INT),
            new F("beginColumn", T.INT), new F("endLine", T.INT), new F("endColumn", T.INT));

    @SuppressWarnings("unchecked")
    public static List<String> validate(Map<String, Object> doc) {
        List<String> e = new ArrayList<>();
        if (!"1.0".equals(String.valueOf(doc.get("schemaVersion")))) {
            e.add("schemaVersion 은 \"1.0\" 이어야 함: " + doc.get("schemaVersion"));
        }
        Map<String, Object> analysis = asMap(doc.get("analysis"), "analysis", e);
        if (analysis != null) {
            check(analysis, List.of(new F("targetClass", T.STR), new F("targetMethod", T.STR), id("entryMethodId", M),
                    id("targetClassId", C), new F("depth", T.INT), new F("settings", T.MAP)), "analysis", e);
        }
        Set<String> classIds = items(doc, "classes", CLASS, e);
        Set<String> methodIds = items(doc, "methods", METHOD, e);
        Set<String> callIds = items(doc, "calls", CALLS, e);
        items(doc, "relations", RELATION, e);
        Set<String> blockIds = items(doc, "controlBlocks", BLOCKS, e);
        Set<String> paramIds = items(doc, "requestParameters", PARAM, e);
        Set<String> objectIds = items(doc, "objects", OBJECT, e);
        items(doc, "variables", VARIABLE, e);
        if (!(doc.get("excludedExternalLibraryCalls") instanceof Integer)) {
            e.add("excludedExternalLibraryCalls: 정수 필요");
        }
        Map<String, Object> spec = asMap(doc.get("requestSpec"), "requestSpec", e);
        if (spec != null && !(spec.get("parameters") instanceof List)) {
            e.add("requestSpec.parameters: 목록 필요");
        }
        if (e.isEmpty()) {
            refs(doc, classIds, methodIds, callIds, blockIds, paramIds, objectIds, e);
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> items(Map<String, Object> doc, String section, List<F> fields, List<String> e) {
        Set<String> ids = new HashSet<>();
        if (!(doc.get(section) instanceof List<?> list)) {
            e.add(section + ": 목록 필요");
            return ids;
        }
        for (int i = 0; i < list.size(); i++) {
            String where = section + "[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                e.add(where + ": 맵 필요");
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) m;
            check(item, fields, where, e);
            Object id = item.get("id");
            if (id != null && !ids.add(id.toString())) {
                e.add(where + ": ID 중복 " + id);
            }
            if (item.get("location") instanceof Map<?, ?> loc) {
                check((Map<String, Object>) loc, LOCATION, where + ".location", e);
            }
            if (section.equals("variables") && item.get("changes") instanceof List<?> cs) {
                for (int j = 0; j < cs.size(); j++) {
                    Map<String, Object> c = (Map<String, Object>) cs.get(j);
                    check(c, CHANGE, where + ".changes[" + j + "]", e);
                    if (c.get("location") instanceof Map<?, ?> loc) {
                        check((Map<String, Object>) loc, LOCATION, where + ".changes[" + j + "].location", e);
                    }
                    if (c.get("sources") instanceof List<?> ss) {
                        for (int k = 0; k < ss.size(); k++) {
                            check((Map<String, Object>) ss.get(k), SOURCE, where + ".changes[" + j + "].sources[" + k + "]", e);
                        }
                    }
                }
            }
            if (section.equals("objects") && item.get("bindings") instanceof List<?> bs) {
                for (int j = 0; j < bs.size(); j++) {
                    Map<String, Object> b = (Map<String, Object>) bs.get(j);
                    check(b, BINDING, where + ".bindings[" + j + "]", e);
                    if (b.get("sources") instanceof List<?> ss) {
                        for (int k = 0; k < ss.size(); k++) {
                            check((Map<String, Object>) ss.get(k), SOURCE, where + ".bindings[" + j + "].sources[" + k + "]", e);
                        }
                    }
                }
            }
        }
        return ids;
    }

    private static void check(Map<String, Object> item, List<F> fields, String where, List<String> e) {
        for (F f : fields) {
            if (!item.containsKey(f.name())) {
                e.add(where + ": 필수 키 누락 " + f.name());
                continue;
            }
            Object v = item.get(f.name());
            boolean ok = switch (f.type()) {
                case STR -> v instanceof String;
                case INT -> v instanceof Integer;
                case BOOL -> v instanceof Boolean;
                case LIST -> v instanceof List;
                case MAP -> v instanceof Map;
                case STR_OR_NULL -> v == null || v instanceof String;
                case INT_OR_NULL -> v == null || v instanceof Integer;
                case BOOL_OR_NULL -> v == null || v instanceof Boolean;
                case MAP_OR_NULL -> v == null || v instanceof Map;
            };
            if (!ok) {
                e.add(where + "." + f.name() + ": 타입 " + f.type() + " 아님 (" + v + ")");
                continue;
            }
            if (f.allowed() != null && !f.allowed().contains(v)) {
                e.add(where + "." + f.name() + ": 허용되지 않은 값 " + v + " (가능: " + f.allowed() + ")");
            }
            if (f.idPattern() != null && v != null && !v.toString().matches(f.idPattern())) {
                e.add(where + "." + f.name() + ": ID 형식 오류 " + v);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void refs(Map<String, Object> doc, Set<String> classIds, Set<String> methodIds, Set<String> callIds,
                             Set<String> blockIds, Set<String> paramIds, Set<String> objectIds, List<String> e) {
        Map<String, Object> analysis = (Map<String, Object>) doc.get("analysis");
        need(methodIds, analysis.get("entryMethodId"), "analysis.entryMethodId", e);
        need(classIds, analysis.get("targetClassId"), "analysis.targetClassId", e);
        for (Map<String, Object> m : (List<Map<String, Object>>) doc.get("methods")) {
            need(classIds, m.get("classId"), m.get("id") + ".classId", e);
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) doc.get("calls")) {
            need(methodIds, c.get("from"), c.get("id") + ".from", e);
            need(methodIds, c.get("to"), c.get("id") + ".to", e);
            need(blockIds, c.get("controlBlockId"), c.get("id") + ".controlBlockId", e);
            ((List<Object>) c.get("requestParameterIds")).forEach(p -> need(paramIds, p, c.get("id") + ".requestParameterIds", e));
        }
        for (Map<String, Object> p : (List<Map<String, Object>>) doc.get("requestParameters")) {
            need(methodIds, p.get("methodId"), p.get("id") + ".methodId", e);
            need(callIds, p.get("callSiteId"), p.get("id") + ".callSiteId", e);
        }
        for (Map<String, Object> o : (List<Map<String, Object>>) doc.get("objects")) {
            need(classIds, o.get("classId"), o.get("id") + ".classId", e);
            for (Map<String, Object> b : (List<Map<String, Object>>) o.get("bindings")) {
                String w = o.get("id") + "/" + b.get("id");
                need(methodIds, b.get("methodId"), w + ".methodId", e);
                need(callIds, b.get("callSiteId"), w + ".callSiteId", e);
                need(blockIds, b.get("controlBlockId"), w + ".controlBlockId", e);
                for (Map<String, Object> s : (List<Map<String, Object>>) b.get("sources")) {
                    need(paramIds, s.get("parameterId"), w + ".sources.parameterId", e);
                    ((List<Object>) s.get("via")).forEach(v -> need(callIds, v, w + ".sources.via", e));
                }
            }
        }
        for (Map<String, Object> v : (List<Map<String, Object>>) doc.get("variables")) {
            need(methodIds, v.get("methodId"), v.get("id") + ".methodId", e);
            for (Map<String, Object> c : (List<Map<String, Object>>) v.get("changes")) {
                String w = v.get("id") + "/" + c.get("id");
                need(blockIds, c.get("controlBlockId"), w + ".controlBlockId", e);
                ((List<Object>) c.get("callSiteIds")).forEach(x -> need(callIds, x, w + ".callSiteIds", e));
                for (Map<String, Object> s : (List<Map<String, Object>>) c.get("sources")) {
                    need(paramIds, s.get("parameterId"), w + ".sources.parameterId", e);
                    ((List<Object>) s.get("via")).forEach(x -> need(callIds, x, w + ".sources.via", e));
                }
            }
        }
        Map<String, Object> spec = (Map<String, Object>) doc.get("requestSpec");
        for (Map<String, Object> q : (List<Map<String, Object>>) spec.get("parameters")) {
            ((List<Object>) q.get("parameterIds")).forEach(p -> need(paramIds, p, q.get("id") + ".parameterIds", e));
        }
        if (objectIds.isEmpty() && !((List<?>) doc.get("objects")).isEmpty()) {
            e.add("objects: ID 없음");
        }
    }

    private static void need(Set<String> ids, Object v, String where, List<String> e) {
        if (v != null && !ids.contains(v.toString())) {
            e.add(where + ": 존재하지 않는 ID " + v);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String where, List<String> e) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        e.add(where + ": 맵 필요");
        return null;
    }
}
