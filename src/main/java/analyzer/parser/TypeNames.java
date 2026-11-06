package analyzer.parser;

/** 타입 문자열 정규화 유틸리티. */
public final class TypeNames {
    private TypeNames() {
    }

    /** 제네릭 인자 제거: Map<String, List<X>> → Map */
    public static String erasure(String type) {
        String prev;
        String cur = type;
        do {
            prev = cur;
            cur = cur.replaceAll("<[^<>]*>", "");
        } while (!cur.equals(prev));
        return cur.replace(" ", "");
    }

    /** 패키지 한정자 제거: java.util.List<java.lang.String> → List<String> */
    public static String simplify(String type) {
        return type.replaceAll("(?:[a-zA-Z_$][\\w$]*\\.)+([A-Za-z_$][\\w$]*)", "$1");
    }

    /** 정규화된 이름의 마지막 세그먼트 */
    public static String simple(String qualified) {
        String e = erasure(qualified);
        int i = e.lastIndexOf('.');
        return i < 0 ? e : e.substring(i + 1);
    }

    public static String packageOf(String qualified) {
        int i = qualified.lastIndexOf('.');
        return i < 0 ? "" : qualified.substring(0, i);
    }
}
