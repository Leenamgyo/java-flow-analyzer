package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.ClassRole;
import analyzer.model.MethodInfo;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * vo.md — 내부용 VO 목록 (spec.md 는 공개용이라 따로, 사용자 요구). 컨플루언스로 옮길 수 있게 표 하나:
 * VO 클래스 · 변수명 · 사용 위치 클래스. 값을 채우는 VO 객체(역할 VO)마다 한 줄 — 셋업 클래스·Map 제외.
 * 사용 위치 클래스 = 그 객체 변수가 있는 메서드의 클래스, 변수가 없으면(필드·this) 값을 넣는 메서드들의 클래스.
 */
public final class VoListWriter {

    public String render(AnalysisResult r) {
        MethodInfo entry = r.method(r.entryMethodId);
        StringBuilder sb = new StringBuilder("# VO 목록: ").append(r.classOf(entry).name).append('.').append(entry.name)
                .append("()\n\n");
        Map<String, Set<String>> rows = new TreeMap<>();
        for (var o : r.objects) {
            if (o.classId == null || r.clazz(o.classId).role != ClassRole.VO) {
                continue;
            }
            Set<String> where = new LinkedHashSet<>();
            if (o.methodId != null) {
                where.add(r.classOf(r.method(o.methodId)).name);
            } else {
                r.bindingsOf(o).forEach(b -> where.add(r.classOf(r.method(b.methodId)).name));
            }
            String key = r.clazz(o.classId).name + "\u0000" + (o.variable == null ? "-" : o.variable);
            rows.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(where);
        }
        if (rows.isEmpty()) {
            return sb.append("없음.\n").toString();
        }
        sb.append("| VO 클래스 | 변수명 | 사용 위치 클래스 |\n|---|---|---|\n");
        rows.forEach((k, where) -> {
            String[] kv = k.split("\u0000", 2);
            sb.append("| ").append(kv[0]).append(" | ").append(kv[1]).append(" | ")
                    .append(where.isEmpty() ? "-" : String.join(", ", where)).append(" |\n");
        });
        return sb.toString();
    }
}
