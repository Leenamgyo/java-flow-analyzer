package analyzer.serializer;

import analyzer.model.SourceLocation;

import java.util.Collection;
import java.util.LinkedHashMap;

/** 순서 보존 YAML 맵 빌더. opt() 는 null/빈 컬렉션/false 를 생략한다. */
final class Y extends LinkedHashMap<String, Object> {

    static Y map() {
        return new Y();
    }

    Y put2(String k, Object v) {
        put(k, v);
        return this;
    }

    Y opt(String k, Object v) {
        if (v == null || (v instanceof Collection<?> c && c.isEmpty()) || Boolean.FALSE.equals(v)) {
            return this;
        }
        put(k, v);
        return this;
    }

    static Y loc(SourceLocation l) {
        if (l == null) {
            return null;
        }
        return map().put2("file", l.file()).put2("begin-line", l.beginLine()).put2("begin-column", l.beginColumn())
                .put2("end-line", l.endLine()).put2("end-column", l.endColumn());
    }
}
