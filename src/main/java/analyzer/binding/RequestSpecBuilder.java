package analyzer.binding;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.RequestParameterInfo;
import analyzer.model.RequestSpecEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 이름이 확정된 Request Parameter(P*)를 이름별 요청 스펙(RQ*)으로 집계하고, 그 값을 출처로 쓰는 바인딩을 연결한다. */
public final class RequestSpecBuilder {
    private RequestSpecBuilder() {
    }

    public static void build(AnalysisResult r) {
        Map<String, RequestSpecEntry> byName = new LinkedHashMap<>();
        for (RequestParameterInfo p : r.parameters) {
            if (!p.named()) {
                continue; // 이름 미확정·전체 조회·body·객체 바인딩은 별도 표기
            }
            RequestSpecEntry e = byName.computeIfAbsent(p.name, n -> {
                RequestSpecEntry x = new RequestSpecEntry();
                x.name = n;
                return x;
            });
            e.parameterIds.add(p.id);
            addOnce(e.types, p.type);
            addOnce(e.defaultValues, p.defaultValue);
            addOnce(e.extractions, p.extraction);
            addOnce(e.methodIds, p.sourceMethodId);
            if (p.required != null && !e.required.contains(p.required)) {
                e.required.add(p.required);
            }
            for (Binding b : r.bindings) {
                if (b.sources.stream().anyMatch(s -> p.id.equals(s.parameterId()))) {
                    addOnce(e.bindingIds, b.id);
                }
            }
        }
        for (RequestSpecEntry e : byName.values()) {
            e.id = r.nextId("RQ");
            r.requestSpec.add(e);
            r.index(e.id, e);
        }
    }

    private static <T> void addOnce(List<T> list, T v) {
        if (v != null && !list.contains(v)) {
            list.add(v);
        }
    }
}
