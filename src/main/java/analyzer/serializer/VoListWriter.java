package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.ClassRole;
import analyzer.model.Binding;
import analyzer.model.MethodInfo;
import analyzer.model.ObjectInfo;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * vo.md — 내부용 VO 목록 (spec.md 는 공개용이라 따로, 사용자 요구). 컨플루언스로 옮길 수 있게 표 하나:
 * VO 클래스 · 변수명 · 사용 위치 클래스 표 + VO 마다 사용하는 프로퍼티(이름 · 타입 · set/get).
 * 대상 = 값을 채우는 VO 객체(역할 VO) + getter 를 부르는 VO 변수(읽기만 하는 resultVO 등) — 셋업 클래스·Map 제외.
 * 프로퍼티 = 값을 넣는 필드(바인딩, set) + VO getter 호출 변수.getX()(get).
 * 타입 = VO 소스의 필드 선언 타입(상위 클래스 포함), 필드가 없으면 getter 반환 타입, 둘 다 모르면 -.
 * 사용 위치 클래스 = 그 객체 변수가 있는 메서드의 클래스, 변수가 없으면(필드·this) 값을 넣는 메서드들의 클래스.
 */
public final class VoListWriter {

    public String render(AnalysisResult r) {
        MethodInfo entry = r.method(r.entryMethodId);
        StringBuilder sb = new StringBuilder("# VO 목록: ").append(r.classOf(entry).name).append('.').append(entry.name)
                .append("()\n\n");
        // VO 클래스 + 변수명 단위로 모은다 (같은 VO·변수명이 여러 곳이면 한 항목)
        Map<String, Entry> rows = new TreeMap<>();
        for (ObjectInfo o : r.objects) {
            if (o.classId == null || r.clazz(o.classId).role != ClassRole.VO) {
                continue;
            }
            String cls = r.clazz(o.classId).name;
            String var = o.variable == null ? "-" : o.variable;
            Entry e = rows.computeIfAbsent(cls + "\u0000" + var, k -> new Entry(cls, var));
            if (o.methodId != null) {
                e.where.add(r.classOf(r.method(o.methodId)).name);
            } else {
                r.bindingsOf(o).forEach(b -> e.where.add(r.classOf(r.method(b.methodId)).name));
            }
            // 값을 넣는 프로퍼티 (setter·builder·생성자·필드 대입·copyProperties 는 *)
            for (Binding b : r.bindingsOf(o)) {
                e.property(b.field, b.fieldType != null ? b.fieldType : o.fieldTypes.get(b.field)).set = true;
            }
            e.fieldTypes.putAll(o.fieldTypes);
        }
        // 값을 읽는 프로퍼티: VO getter 읽기 (값을 넣지 않고 읽기만 하는 resultVO·for-each 변수도 여기서 목록에 들어간다)
        for (AnalysisResult.VoRead v : r.voReads) {
            String cls = r.clazz(v.classId()).name;
            Entry e = rows.computeIfAbsent(cls + "\u0000" + v.variable(), k -> new Entry(cls, v.variable()));
            e.where.add(r.classOf(r.method(v.methodId())).name);
            String type = v.type() != null ? v.type() : e.fieldTypes.get(v.property());
            e.property(v.property(), type).get = true;
        }
        if (rows.isEmpty()) {
            return sb.append("없음.\n").toString();
        }
        sb.append("| VO 클래스 | 변수명 | 사용 위치 클래스 |\n|---|---|---|\n");
        rows.values().forEach(e -> sb.append("| ").append(e.cls).append(" | ").append(e.var).append(" | ")
                .append(e.where.isEmpty() ? "-" : String.join(", ", e.where)).append(" |\n"));
        sb.append("\n## 프로퍼티\n");
        for (Entry e : rows.values()) {
            sb.append("\n### ").append(e.cls).append(" (").append(e.var).append(")\n\n");
            if (e.props.isEmpty()) {
                sb.append("사용하는 프로퍼티 없음.\n");
                continue;
            }
            sb.append("| 프로퍼티 | 타입 | 사용 |\n|---|---|---|\n");
            e.props.forEach((name, p) -> sb.append("| ").append(name).append(" | ").append(p.type == null ? "-" : p.type)
                    .append(" | ").append(p.set && p.get ? "set, get" : p.set ? "set" : "get").append(" |\n"));
        }
        return sb.toString();
    }

    private static final class Prop {
        String type;
        boolean set;
        boolean get;
    }

    private static final class Entry {
        final String cls;
        final String var;
        final Set<String> where = new LinkedHashSet<>();
        /** 프로퍼티 이름 → 타입·사용 (처음 나온 순서) */
        final Map<String, Prop> props = new LinkedHashMap<>();
        /** 값을 넣는 객체에서 알게 된 필드 선언 타입 */
        final Map<String, String> fieldTypes = new LinkedHashMap<>();

        Entry(String cls, String var) {
            this.cls = cls;
            this.var = var;
        }

        Prop property(String name, String type) {
            Prop p = props.computeIfAbsent(name, k -> new Prop());
            if (p.type == null) {
                p.type = type;
            }
            return p;
        }
    }
}
