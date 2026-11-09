package analyzer.response;

import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Response(JSP) 분석: 사용자가 정한 JSP 를 찾아(JspLocator) 읽고(JspParser), XML 태그 구조와 각 값의 출처를 만든다.
 * 값 출처는 JSP 의 식(${…}, &lt;%= %&gt;)만 보고 정한다 — 반복 변수는 items 로, c:set 변수는 그 값으로,
 * 나머지 이름은 Action 이 넘긴 attribute(분석한 코드의 request.setAttribute("x", v) 등)로. 넣는 곳을 못 찾으면 그렇게 적는다(추측 금지).
 */
public final class ResponseAnalyzer {

    /** JSP 로 값을 넘기는 호출 (첫 인자 = attribute 이름 문자열) */
    static final Set<String> PUT_METHODS = Set.of("setAttribute", "addAttribute", "addObject");
    /** EL 연산자·리터럴 (이름이 아님) */
    private static final Set<String> EL_WORDS = Set.of("empty", "not", "and", "or", "eq", "ne", "lt", "gt", "le", "ge",
            "true", "false", "null", "div", "mod", "instanceof");
    /** EL 함수 호출 vf:getServerType( / fn:length( */
    private static final Pattern EL_FUNCTION = Pattern.compile("\\b([A-Za-z_]\\w*:[A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern EXPR = Pattern.compile("\\$\\{((?:[^}\"']|\"[^\"]*\"|'[^']*')*)}|<%=(.*?)%>", Pattern.DOTALL);
    private static final Pattern REF = Pattern.compile("(?<![\\w$.:])([A-Za-z_$][\\w$]*)((?:\\s*\\.\\s*[A-Za-z_$][\\w$]*|\\[[^\\]]*])*)(?!\\s*[:(\\w])");

    private final AnalysisResult r;
    private final ResponseModel model = new ResponseModel();
    private final Map<String, List<ResponseModel.Attribute>> attributes = new LinkedHashMap<>();
    private final Map<String, Set<String>> usage = new LinkedHashMap<>();
    private Path base;
    /** 지금 보고 있는 JSP 위치 (attribute 사용 기록용) */
    private String currentAt;

    private ResponseAnalyzer(AnalysisResult r) {
        this.r = r;
    }

    public static ResponseModel analyze(AnalyzerConfig cfg, AnalysisResult r) {
        ResponseAnalyzer a = new ResponseAnalyzer(r);
        List<Path> srcRoots = cfg.target.effectiveSourceRoots().stream().map(Path::of).toList();
        a.base = srcRoots.isEmpty() ? null : JspLocator.projectDir(srcRoots.get(0).toAbsolutePath().normalize());
        a.collectAttributes();
        JspLocator loc = JspLocator.of(cfg.response.webRoots, srcRoots);
        loc.webRoots().forEach(w -> a.model.webRoots.add(a.rel(w)));
        for (String req : cfg.response.jsp) {
            a.jsp(loc.find(req));
        }
        a.attributes.forEach((name, list) -> list.forEach(x -> a.model.attributes.add(new ResponseModel.Attribute(x.name(),
                x.callSiteId(), x.code(), x.value(), x.at(), new ArrayList<>(a.usage.getOrDefault(name, Set.of()))))));
        return a.model;
    }

    /** 분석한 코드의 request.setAttribute("x", v) / model.addAttribute("x", v) / mav.addObject("x", v) */
    private void collectAttributes() {
        for (CallSiteInfo s : r.callSites) {
            if (!PUT_METHODS.contains(s.methodName) || s.arguments.size() < 2) {
                continue;
            }
            String first = s.arguments.get(0).strip();
            if (!first.matches("\"[^\"]*\"")) {
                continue; // 이름이 식이면 어느 attribute 인지 확정 불가
            }
            String name = first.substring(1, first.length() - 1);
            attributes.computeIfAbsent(name, k -> new ArrayList<>()).add(new ResponseModel.Attribute(name, s.id, s.expression,
                    s.arguments.get(1).strip(), r.method(s.callerMethodId).name + " " + s.location.shortText(), List.of()));
        }
    }

    private void jsp(JspLocator.Result found) {
        ResponseModel.Jsp j = new ResponseModel.Jsp();
        j.requested = found.requested();
        j.status = found.status().name();
        found.candidates().forEach(c -> j.candidates.add(rel(c)));
        model.jsps.add(j);
        if (found.status() != JspLocator.Status.FOUND) {
            return;
        }
        j.file = rel(found.file());
        JspParser.Doc doc = JspParser.parse(found.file(), found.webRoot());
        doc.files().stream().skip(1).forEach(f -> j.includes.add(rel(f)));
        j.notes.addAll(doc.notes());
        walk(j, doc.nodes(), "", "", new ArrayDeque<>(), new LinkedHashMap<>(), j.file);
    }

    /** 반복 변수 바인딩 (var → items 식) */
    private record Frame(String var, String items, String status) {
    }

    private void walk(ResponseModel.Jsp j, List<JspParser.N> nodes, String path, String indent, Deque<Frame> loops,
                      Map<String, String> sets, String file) {
        for (JspParser.N n : nodes) {
            String at = file + ":" + n.line();
            currentAt = at;
            List<String> ctx = context(loops);
            if (n instanceof JspParser.Elem e) {
                String p = path + "/" + e.name();
                StringBuilder head = new StringBuilder(indent + "<" + e.name());
                for (JspParser.Attr a : e.attrs()) {
                    head.append(' ').append(a.name()).append("=\"").append(a.value()).append('"');
                    j.values.add(new ResponseModel.Value(p + "/@" + a.name(), "attribute", a.value(), ctx,
                            sources(a.value(), loops, sets), at));
                }
                boolean empty = e.children().isEmpty();
                j.tree.add(head + (empty ? "/>" : ">"));
                if (!empty) {
                    walk(j, e.children(), p, indent + "  ", loops, sets, file);
                    j.tree.add(indent + "</" + e.name() + ">");
                }
            } else if (n instanceof JspParser.Text t) {
                j.tree.add(indent + t.value() + tag(sources(t.value(), loops, sets)));
                j.values.add(new ResponseModel.Value(path + "/text()", "text", t.value(), ctx,
                        sources(t.value(), loops, sets), at));
            } else if (n instanceof JspParser.Loop l) {
                j.tree.add(indent + "↻ " + l.tag() + " " + (l.var() == null ? "" : l.var() + " : ") + nz(l.items())
                        + tag(sources(nz(l.items()), loops, sets)));
                loops.push(new Frame(l.var(), l.items(), l.varStatus()));
                walk(j, l.children(), path, indent + "  ", loops, sets, file);
                loops.pop();
            } else if (n instanceof JspParser.Choose c) {
                j.tree.add(indent + "◇ choose");
                walk(j, c.children(), path, indent + "  ", loops, sets, file);
            } else if (n instanceof JspParser.Cond c) {
                j.tree.add(indent + "◇ " + (c.test() == null ? "otherwise" : c.tag() + " " + c.test())
                        + (c.test() == null ? "" : tag(sources(c.test(), loops, sets))));
                loops.push(new Frame(null, c.test() == null ? "otherwise" : c.test(), "COND"));
                walk(j, c.children(), path, indent + "  ", loops, sets, file);
                loops.pop();
            } else if (n instanceof JspParser.SetVar sv) {
                if (sv.var() != null) {
                    sets.put(sv.var(), nz(sv.value()));
                }
                j.tree.add(indent + "≔ " + sv.var() + " = " + nz(sv.value()) + tag(sources(nz(sv.value()), loops, sets)));
            } else if (n instanceof JspParser.Include inc) {
                j.tree.add(indent + "⇲ include " + inc.path() + (inc.status().equals("FOUND") ? "" : "  (" + inc.status() + ")"));
                if (!inc.status().equals("FOUND")) {
                    j.notes.add(at + " include " + inc.path() + " — " + inc.status());
                }
                walk(j, inc.children(), path, indent + "  ", loops, sets, inc.file() == null ? file : rel(inc.file()));
            } else if (n instanceof JspParser.Script s) {
                String code = s.code().replaceAll("\\s+", " ");
                j.tree.add(indent + "<% " + code + " %>");
                j.notes.add(at + " 스크립틀릿 — 응답에 영향을 주는지 확인: " + code);
            } else if (n instanceof JspParser.Custom c) {
                StringBuilder head = new StringBuilder("<" + c.tag());
                c.attrs().forEach(a -> head.append(' ').append(a.name()).append("=\"").append(a.value()).append('"'));
                String code = head + (c.children().isEmpty() ? "/>" : ">");
                List<String> src = new ArrayList<>();
                c.attrs().forEach(a -> src.addAll(sources(a.value(), loops, sets)));
                j.tree.add(indent + code + tag(src));
                j.values.add(new ResponseModel.Value(path + "/" + c.tag(), "tag", code, ctx, src, at));
                walk(j, c.children(), path, indent + "  ", loops, sets, file);
            }
        }
    }

    /** 반복·조건 (바깥 → 안쪽) */
    private static List<String> context(Deque<Frame> loops) {
        List<String> out = new ArrayList<>();
        loops.descendingIterator().forEachRemaining(f -> out.add("COND".equals(f.status())
                ? "otherwise".equals(f.items()) ? "otherwise" : "if " + f.items()
                : "loop " + (f.var() == null ? "" : f.var() + " : ") + nz(f.items())));
        return out;
    }

    /** 값 문자열 안 식들의 출처 (중복 제거). 식이 없으면 빈 목록 = 고정 값 */
    private List<String> sources(String value, Deque<Frame> loops, Map<String, String> sets) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null) {
            return List.of();
        }
        Matcher m = EXPR.matcher(value);
        while (m.find()) {
            if (m.group(1) != null) {
                Matcher fn = EL_FUNCTION.matcher(m.group(1).replaceAll("\"[^\"]*\"|'[^']*'", "\"\""));
                while (fn.find()) {
                    out.add(fn.group(1) + "() ← EL 함수 " + fn.group(1) + "()"); // 값은 함수가 만든다 (인자 출처는 아래)
                }
                for (String ref : refs(m.group(1))) {
                    out.add(resolve(ref, loops, sets, 0));
                }
            } else {
                out.add(scriptlet(m.group(2).strip()));
            }
        }
        return new ArrayList<>(out);
    }

    /** EL 식 안의 이름 경로들 (문자열 리터럴·연산자 단어·함수 접두사 제외) */
    static List<String> refs(String el) {
        String noStrings = el.replaceAll("\"[^\"]*\"|'[^']*'", "\"\"");
        List<String> out = new ArrayList<>();
        Matcher m = REF.matcher(noStrings);
        while (m.find()) {
            String root = m.group(1);
            if (EL_WORDS.contains(root) || root.matches("\\d.*")) {
                continue;
            }
            out.add((root + m.group(2)).replaceAll("\\s+", ""));
        }
        return out;
    }

    private String resolve(String ref, Deque<Frame> loops, Map<String, String> sets, int depth) {
        String root = ref.split("[.\\[]", 2)[0];
        String rest = ref.substring(root.length());
        if (depth > 5) {
            return ref + " (출처 사슬이 깊어 중단)";
        }
        for (Frame f : loops) {
            if (root.equals(f.status()) && !"COND".equals(f.status())) {
                return ref + " ← 반복 상태(varStatus)";
            }
            if (root.equals(f.var())) {
                List<String> itemRefs = f.items() == null ? List.of() : refsOf(f.items());
                if (itemRefs.size() == 1) {
                    String inner = resolve(itemRefs.get(0), loops.stream().dropWhile(x -> x != f).skip(1)
                            .collect(ArrayDeque::new, ArrayDeque::addLast, ArrayDeque::addAll), sets, depth + 1);
                    return ref + " ← " + itemRefs.get(0) + "[]" + rest + " ← " + inner.substring(inner.indexOf(" ← ") + 3);
                }
                return ref + " ← 반복 " + nz(f.items()) + " 의 원소";
            }
        }
        if (sets.containsKey(root)) {
            // c:set x = ${x} 처럼 자기 이름을 쓰면 그 x 는 바깥(attribute) — 자기 자신을 빼고 푼다 (무한 재귀 방지)
            Map<String, String> outer = new LinkedHashMap<>(sets);
            outer.remove(root);
            List<String> inner = sources(sets.get(root), loops, outer);
            return ref + " ← c:set " + root + " = " + sets.get(root) + (inner.isEmpty() ? " (고정 값)" : " ← " + String.join(", ", inner));
        }
        String tail = rest.replaceFirst("^\\.", "");
        switch (root) {
            case "param", "paramValues" -> {
                return ref + " ← 요청 파라미터 request." + tail;
            }
            case "header", "headerValues" -> {
                return ref + " ← 요청 헤더 " + tail;
            }
            case "requestScope", "sessionScope", "applicationScope", "pageScope" -> {
                return ref + " ← " + root.replace("Scope", "") + " attribute " + attribute(tail.split("[.\\[]", 2)[0]);
            }
            case "pageContext", "cookie", "initParam" -> {
                return ref + " ← JSP 내장 객체 " + root;
            }
            default -> {
                return ref + " ← attribute " + attribute(root);
            }
        }
    }

    private List<String> refsOf(String value) {
        List<String> out = new ArrayList<>();
        Matcher m = EXPR.matcher(value);
        while (m.find()) {
            if (m.group(1) != null) {
                out.addAll(refs(m.group(1)));
            }
        }
        return out;
    }

    /** attribute 이름 → 넣는 곳 (분석한 코드), 사용 기록 */
    private String attribute(String name) {
        usage.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(currentAt);
        List<ResponseModel.Attribute> put = attributes.get(name);
        if (put == null || put.isEmpty()) {
            return name + " — 분석한 코드에서 넣는 곳 없음";
        }
        return name + " = " + String.join(" / ", put.stream().map(a -> a.value() + " (" + a.callSiteId() + " " + a.at() + ")").toList());
    }

    /** &lt;%= … %&gt; 식: request.getAttribute("x") / request.getParameter("x") 만 알아보고 나머지는 확인 필요 */
    private String scriptlet(String code) {
        Matcher a = Pattern.compile("getAttribute\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(code);
        if (a.find()) {
            return "<%= " + code + " %> ← attribute " + attribute(a.group(1));
        }
        Matcher p = Pattern.compile("getParameter\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(code);
        if (p.find()) {
            return "<%= " + code + " %> ← 요청 파라미터 request." + p.group(1);
        }
        return "<%= " + code + " %> ← 스크립틀릿 식 (확인 필요)";
    }

    private static String tag(List<String> sources) {
        return sources.isEmpty() ? "" : "    ← " + String.join(" · ", sources.stream()
                .map(s -> s.contains(" ← ") ? s.substring(s.indexOf(" ← ") + 3) : s).toList());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private String rel(Path p) {
        Path a = p.toAbsolutePath().normalize();
        return base != null && a.startsWith(base) ? base.relativize(a).toString().replace('\\', '/') : a.toString();
    }
}
