package analyzer.response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XML 을 출력하는 JSP 를 태그 트리로 읽는다 (읽기 전용, 실행하지 않음).
 * <ul>
 *   <li>출력 XML 태그(taglib 접두사가 없는 태그) → {@link Elem}, 그 안 텍스트·${…}·&lt;%= %&gt; → {@link Text}</li>
 *   <li>JSTL core: c:forEach/c:forTokens → {@link Loop}, c:if → {@link Cond}, c:choose/when/otherwise → {@link Choose},
 *       c:set → {@link SetVar}, c:out → 값 {@link Text}, c:import → {@link Include}</li>
 *   <li>&lt;%@ include file %&gt;·jsp:include → {@link Include} (내용을 그 자리에 펼침, 순환 방지)</li>
 *   <li>그 밖의 taglib 태그(fmt:, bean:, logic: …) → {@link Custom}, 스크립틀릿 &lt;% %&gt; → {@link Script}</li>
 * </ul>
 * 닫는 태그가 맞지 않으면 가장 가까운 같은 이름까지 닫고, 없으면 무시한다.
 */
public final class JspParser {

    public interface N {
        int line();
    }

    public interface Container extends N {
        List<N> children();
    }

    public record Attr(String name, String value) {
    }

    public record Elem(String name, List<Attr> attrs, List<N> children, int line) implements Container {
    }

    public record Text(String value, int line) implements N {
    }

    public record Loop(String tag, String items, String var, String varStatus, List<N> children, int line) implements Container {
    }

    /** c:if (test) / c:when (test) / c:otherwise (test == null) */
    public record Cond(String tag, String test, List<N> children, int line) implements Container {
    }

    public record Choose(List<N> children, int line) implements Container {
    }

    public record SetVar(String var, String value, String scope, int line) implements N {
    }

    public record Script(String code, int line) implements N {
    }

    public record Custom(String tag, List<Attr> attrs, List<N> children, int line) implements Container {
    }

    /** status: FOUND(펼침) | NOT_FOUND | DYNAMIC(경로가 식) | CYCLE */
    public record Include(String kind, String path, Path file, String status, List<N> children, int line) implements Container {
    }

    /** 파싱 결과: 최상위 노드들 + 읽은 파일들(include 포함) + 알림 */
    public record Doc(List<N> nodes, List<Path> files, List<String> notes, String contentType) {
    }

    private final Path webRoot;
    /** taglib 접두사 → uri */
    private final Map<String, String> taglibs = new LinkedHashMap<>();
    private final List<Path> files = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final Set<Path> active = new HashSet<>();
    /** 첫 &lt;%@ page contentType %&gt; (include 된 파일 포함, 먼저 나온 것) */
    private String contentType;

    private JspParser(Path webRoot) {
        this.webRoot = webRoot;
    }

    public static Doc parse(Path file, Path webRoot) {
        JspParser p = new JspParser(webRoot);
        List<N> nodes = p.parseFile(file, 0);
        return new Doc(nodes, p.files, p.notes, p.contentType);
    }

    /** 문자열 JSP (테스트용) */
    public static List<N> parseText(String text) {
        return new JspParser(null).parse(text, null, 0);
    }

    private List<N> parseFile(Path file, int depth) {
        Path f = file.toAbsolutePath().normalize();
        if (!active.add(f)) {
            notes.add("include 순환: " + f.getFileName());
            return List.of();
        }
        try {
            files.add(f);
            String text = analyzer.sql.MapperXmlIndex.decodeText(Files.readAllBytes(f));
            return parse(text, f, depth);
        } catch (IOException e) {
            notes.add(f + ": " + e.getMessage());
            return List.of();
        } finally {
            active.remove(f);
        }
    }

    /** 스택 원소: 태그 이름 + 자식 목록을 가진 노드 */
    private record Open(String tag, Container node) {
    }

    private List<N> parse(String s, Path file, int depth) {
        List<N> top = new ArrayList<>();
        Deque<Open> stack = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        int[] textLine = {-1};
        int[] lineStarts = lineStarts(s);
        int i = 0;
        while (i < s.length()) {
            List<N> cur = stack.isEmpty() ? top : stack.peek().node().children();
            int line = lineOf(lineStarts, i);
            if (s.startsWith("<%--", i)) {
                i = skip(s, i, "--%>");
            } else if (s.startsWith("<%@", i)) {
                int end = skip(s, i, "%>");
                directive(s.substring(i + 3, Math.max(i + 3, end - 2)), file, depth, cur, line, text, textLine);
                i = end;
            } else if (s.startsWith("<%=", i)) {
                int end = skip(s, i, "%>");
                appendText(text, textLine, s.substring(i, end), line);
                i = end;
            } else if (s.startsWith("<%", i)) {
                int end = skip(s, i, "%>");
                flush(text, textLine, cur);
                String code = s.substring(i + 2, Math.max(i + 2, end - 2)).replaceFirst("^!", "").strip();
                if (!code.isEmpty()) {
                    cur.add(new Script(code, line));
                }
                i = end;
            } else if (s.startsWith("<!--", i)) {
                i = skip(s, i, "-->");
            } else if (s.startsWith("<![CDATA[", i)) {
                int end = skip(s, i, "]]>");
                appendText(text, textLine, s.substring(i, end), line); // CDATA 표시까지 원문 그대로 (명세 코드에 보임)
                i = end;
            } else if (s.startsWith("<?", i)) {
                i = skip(s, i, "?>");
            } else if (s.startsWith("<!", i)) {
                i = skip(s, i, ">");
            } else if (s.startsWith("</", i)) {
                int end = skip(s, i, ">");
                flush(text, textLine, cur);
                close(stack, s.substring(i + 2, Math.max(i + 2, end - 1)).strip());
                i = end;
            } else if (s.charAt(i) == '<' && i + 1 < s.length() && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '_')) {
                int end = tagEnd(s, i);
                flush(text, textLine, cur);
                String body = s.substring(i + 1, end - 1);
                boolean self = body.endsWith("/");
                if (self) {
                    body = body.substring(0, body.length() - 1);
                }
                open(body, self, stack, cur, file, depth, line);
                i = end;
            } else {
                appendText(text, textLine, String.valueOf(s.charAt(i)), line);
                i++;
            }
        }
        flush(text, textLine, stack.isEmpty() ? top : stack.peek().node().children());
        return top;
    }

    private void directive(String body, Path file, int depth, List<N> cur, int line, StringBuilder text, int[] textLine) {
        String b = body.strip();
        String kind = b.split("\\s+", 2)[0];
        Map<String, String> a = new LinkedHashMap<>();
        attrs(b.substring(kind.length())).forEach(x -> a.put(x.name(), x.value()));
        if (kind.equals("page") && a.containsKey("contentType") && contentType == null) {
            contentType = a.get("contentType").strip();
        } else if (kind.equals("taglib") && a.containsKey("prefix")) {
            taglibs.put(a.get("prefix"), a.getOrDefault("uri", a.getOrDefault("tagdir", "")));
        } else if (kind.equals("include") && a.containsKey("file")) {
            flush(text, textLine, cur);
            cur.add(include("<%@ include %>", a.get("file"), file, depth, line));
        }
    }

    private Include include(String kind, String path, Path from, int depth, int line) {
        if (path == null || path.contains("${") || path.contains("<%")) {
            return new Include(kind, path, null, "DYNAMIC", List.of(), line);
        }
        Path root = webRoot != null ? webRoot : from == null ? null : from.getParent();
        Path target = from == null || root == null ? null : JspLocator.resolveInclude(from, root, path);
        if (target == null || !Files.isRegularFile(target)) {
            return new Include(kind, path, target, "NOT_FOUND", List.of(), line);
        }
        if (active.contains(target.toAbsolutePath().normalize()) || depth > 10) {
            return new Include(kind, path, target, "CYCLE", List.of(), line);
        }
        return new Include(kind, path, target, "FOUND", parseFile(target, depth + 1), line);
    }

    private void open(String body, boolean self, Deque<Open> stack, List<N> cur, Path file, int depth, int line) {
        String name = body.split("[\\s/]", 2)[0];
        List<Attr> attrs = attrs(body.substring(name.length()));
        Map<String, String> a = new LinkedHashMap<>();
        attrs.forEach(x -> a.put(x.name(), x.value()));
        int colon = name.indexOf(':');
        String prefix = colon > 0 ? name.substring(0, colon) : null;
        String local = colon > 0 ? name.substring(colon + 1) : name;
        Container node = null;
        // c: 는 taglib 선언이 없어도 JSTL core (선언이 공통 include·web.xml 에 있는 JSP 가 흔함)
        if (prefix != null && (taglibs.containsKey(prefix) || prefix.equals("jsp") || prefix.equals("c"))) {
            boolean core = prefix.equals("c") || taglibs.getOrDefault(prefix, "").contains("jstl/core")
                    || taglibs.getOrDefault(prefix, "").contains("jsp/jstl/core");
            if (core && (local.equals("forEach") || local.equals("forTokens"))) {
                node = new Loop(name, a.get("items"), a.get("var"), a.get("varStatus"), new ArrayList<>(), line);
            } else if (core && local.equals("if")) {
                node = new Cond(name, a.get("test"), new ArrayList<>(), line);
            } else if (core && local.equals("choose")) {
                node = new Choose(new ArrayList<>(), line);
            } else if (core && local.equals("when")) {
                node = new Cond(name, a.get("test"), new ArrayList<>(), line);
            } else if (core && local.equals("otherwise")) {
                node = new Cond(name, null, new ArrayList<>(), line);
            } else if (core && local.equals("set")) {
                cur.add(new SetVar(a.get("var"), a.get("value"), a.get("scope"), line));
                if (!self) {
                    stack.push(new Open(name, new Custom(name, attrs, new ArrayList<>(), line))); // 본문 값은 Custom 으로 흡수
                }
                return;
            } else if (core && local.equals("out")) {
                cur.add(new Text(a.getOrDefault("value", ""), line));
                if (!self) {
                    stack.push(new Open(name, new Custom(name, attrs, new ArrayList<>(), line)));
                }
                return;
            } else if (core && local.equals("import") || prefix.equals("jsp") && local.equals("include")) {
                cur.add(include("<" + name + ">", a.getOrDefault("url", a.get("page")), file, depth, line));
                if (!self) {
                    stack.push(new Open(name, new Custom(name, attrs, new ArrayList<>(), line)));
                }
                return;
            } else {
                node = new Custom(name, attrs, new ArrayList<>(), line);
            }
        } else {
            node = new Elem(name, attrs, new ArrayList<>(), line);
        }
        cur.add(node);
        if (!self) {
            stack.push(new Open(name, node));
        }
    }

    private static void close(Deque<Open> stack, String name) {
        boolean found = stack.stream().anyMatch(o -> o.tag().equals(name));
        if (!found) {
            return;
        }
        while (!stack.isEmpty()) {
            if (stack.pop().tag().equals(name)) {
                return;
            }
        }
    }

    /** 속성: name="v" / name='v' / name=v, 값 안의 &lt;%= %&gt;·${…} 그대로 */
    static List<Attr> attrs(String s) {
        List<Attr> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '/')) {
                i++;
            }
            int ns = i;
            while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '=' && s.charAt(i) != '/') {
                i++;
            }
            String name = s.substring(ns, i);
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            String value = "";
            if (i < s.length() && s.charAt(i) == '=') {
                i++;
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i < s.length() && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                    char q = s.charAt(i);
                    int vs = ++i;
                    while (i < s.length() && s.charAt(i) != q) {
                        if (s.startsWith("<%", i)) {
                            int e = s.indexOf("%>", i);
                            i = e < 0 ? s.length() : e + 2;
                        } else {
                            i++;
                        }
                    }
                    value = s.substring(vs, Math.min(i, s.length()));
                    i++;
                } else {
                    int vs = i;
                    while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
                        i++;
                    }
                    value = s.substring(vs, i);
                }
            }
            if (!name.isEmpty()) {
                out.add(new Attr(name, value));
            }
        }
        return out;
    }

    /** '<' 부터 태그 끝 '>' 다음 위치 (따옴표·&lt;% %&gt; 안의 '>' 는 건너뜀) */
    private static int tagEnd(String s, int start) {
        char q = 0;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("<%", i)) {
                int e = s.indexOf("%>", i);
                if (e < 0) {
                    return s.length();
                }
                i = e + 1;
                continue;
            }
            if (q != 0) {
                if (c == q) {
                    q = 0;
                }
            } else if (c == '"' || c == '\'') {
                q = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return s.length();
    }

    private static int skip(String s, int i, String end) {
        int e = s.indexOf(end, i + 2);
        return e < 0 ? s.length() : e + end.length();
    }

    private static void appendText(StringBuilder text, int[] textLine, String v, int line) {
        if (textLine[0] < 0 && !v.isBlank()) {
            textLine[0] = line;
        }
        text.append(v);
    }

    private static void flush(StringBuilder text, int[] textLine, List<N> cur) {
        String t = text.toString().replaceAll("\\s+", " ").strip();
        if (!t.isEmpty()) {
            cur.add(new Text(t, Math.max(textLine[0], 1)));
        }
        text.setLength(0);
        textLine[0] = -1;
    }

    private static int[] lineStarts(String s) {
        List<Integer> l = new ArrayList<>(List.of(0));
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                l.add(i + 1);
            }
        }
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] starts, int pos) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (starts[mid] <= pos) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }
}
