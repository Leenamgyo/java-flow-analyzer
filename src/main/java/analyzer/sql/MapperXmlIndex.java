package analyzer.sql;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 원본 프로젝트의 MyBatis(&lt;mapper namespace&gt;)·iBatis(&lt;sqlMap&gt;) XML 색인 (읽기 전용).
 * statement(select/insert/update/delete/statement/procedure)와 &lt;sql&gt; 조각을 namespace·id 로 찾고,
 * 문장의 테이블 이름을 뽑는다. 쿼리 본문은 밖으로 내보내지 않는다.
 */
public final class MapperXmlIndex {

    public record Statement(Path file, String namespace, String id, String type, Element element) {
        public String fullId() {
            return namespace == null || namespace.isEmpty() ? id : namespace + "." + id;
        }
    }

    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete", "statement", "procedure");

    private final List<Statement> statements = new ArrayList<>();
    /** sql 조각: namespace.id 와 id 둘 다로 */
    private final Map<String, List<Statement>> fragments = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();

    public static MapperXmlIndex scan(List<Path> roots) {
        MapperXmlIndex idx = new MapperXmlIndex();
        Set<Path> seen = new HashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                    if (seen.add(f.toAbsolutePath().normalize())) {
                        idx.read(f);
                    }
                }
            } catch (IOException e) {
                idx.errors.add(root + ": " + e.getMessage());
            }
        }
        return idx;
    }

    public List<String> errors() {
        return errors;
    }

    public int statementCount() {
        return statements.size();
    }

    private void read(Path f) {
        String text;
        try {
            text = decode(Files.readAllBytes(f));
        } catch (IOException e) {
            errors.add(f + ": " + e.getMessage());
            return;
        }
        // BOM·선두 공백이 있으면 파서가 "Content is not allowed in prolog" (1:1) 로 실패한다 — 첫 '<' 앞을 버린다
        int lt = text.indexOf('<');
        if (lt > 0 && text.substring(0, lt).chars().allMatch(c -> c == '\uFEFF' || Character.isWhitespace(c))) {
            text = text.substring(lt);
        }
        if (!text.contains("<mapper") && !text.contains("<sqlMap")) {
            return; // 매퍼 XML 이 아님 (설정·빌드 파일 등)
        }
        Document doc;
        try {
            doc = builder().parse(new org.xml.sax.InputSource(new StringReader(text)));
        } catch (Exception e) {
            errors.add(f + ": XML 파싱 실패 — " + e.getMessage());
            return;
        }
        Element root = doc.getDocumentElement();
        if (!root.getTagName().equals("mapper") && !root.getTagName().equals("sqlMap")) {
            return;
        }
        String ns = root.getAttribute("namespace");
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && !e.getAttribute("id").isEmpty()) {
                Statement s = new Statement(f, ns, e.getAttribute("id"), e.getTagName(), e);
                if (STATEMENTS.contains(e.getTagName())) {
                    statements.add(s);
                } else if (e.getTagName().equals("sql")) {
                    fragments.computeIfAbsent(s.fullId(), k -> new ArrayList<>()).add(s);
                    if (!s.fullId().equals(s.id())) {
                        fragments.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(s);
                    }
                }
            }
        }
    }

    /** 외부 DTD 를 읽지 않는 파서 (네트워크 접근 금지 — 매퍼 XML 은 보통 mybatis DTD 를 가리킨다) */
    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setValidating(false);
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder b = f.newDocumentBuilder();
        // 기본 오류 처리기는 "[Fatal Error] :1:1: …" 를 화면(stderr)에 찍는다 — 찍지 않고 예외로만 (read 가 errors 에 기록)
        b.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(org.xml.sax.SAXParseException e) {
            }

            @Override
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        return b;
    }

    /** UTF-8 로 읽고, UTF-8 이 아니면 MS949(EUC-KR 포함) — 한글 주석이 있는 옛 매퍼 XML */
    static String decode(byte[] bytes) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("MS949"));
        }
    }

    /**
     * statement id 로 찾기. "ns.id" 는 namespace+id 정확히 일치, 점이 없거나 정확히 맞는 것이 없으면
     * (iBatis 처럼 namespace 를 안 쓰는 경우) id 만으로 — 여러 개면 모두 돌려준다(호출 쪽이 AMBIGUOUS 처리).
     */
    public List<Statement> find(String statementId) {
        List<Statement> exact = statements.stream().filter(s -> s.fullId().equals(statementId)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        String shortId = statementId.contains(".") ? statementId.substring(statementId.lastIndexOf('.') + 1) : statementId;
        if (statementId.contains(".")) {
            // ns 가 다른 짧은 이름(Mapper.selectX 처럼 단순명 namespace)만 허용: namespace 끝이 일치
            String nsPart = statementId.substring(0, statementId.lastIndexOf('.'));
            return statements.stream().filter(s -> s.id().equals(shortId) && s.namespace() != null
                    && (s.namespace().endsWith("." + nsPart) || s.namespace().equals(nsPart))).toList();
        }
        return statements.stream().filter(s -> s.id().equals(shortId)).toList();
    }

    /** statement 의 SQL 텍스트 (include 펼침, 동적 태그 내용 포함). 미해결 include 는 notes 에 */
    public String sqlText(Statement s, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        append(s.element(), s.namespace(), sb, notes, new HashSet<>());
        return sb.toString();
    }

    private void append(Node n, String ns, StringBuilder sb, List<String> notes, Set<String> visiting) {
        NodeList children = n.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(' ').append(c.getNodeValue()).append(' ');
            } else if (c instanceof Element e) {
                if (e.getTagName().equals("include")) {
                    String ref = e.getAttribute("refid");
                    List<Statement> frag = fragments.getOrDefault(ref.contains(".") ? ref : (ns == null || ns.isEmpty() ? ref : ns + "." + ref),
                            fragments.getOrDefault(ref, List.of()));
                    if (frag.size() == 1 && visiting.add(frag.get(0).fullId())) {
                        append(frag.get(0).element(), frag.get(0).namespace(), sb, notes, visiting);
                        visiting.remove(frag.get(0).fullId());
                    } else if (frag.isEmpty()) {
                        notes.add("include 미해결: " + ref);
                    } else if (frag.size() > 1) {
                        notes.add("include 후보 여러 개: " + ref);
                    }
                } else {
                    append(e, ns, sb, notes, visiting); // <if>·<where>·<foreach>·<isNotEmpty> … 의 내용
                }
            }
        }
    }

    // ------------------------------------------------------------------ 테이블 이름

    private static final Set<String> STOP = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "UNION", "MINUS", "EXCEPT",
            "INTERSECT", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "ON", "USING", "SET", "VALUES",
            "SELECT", "CONNECT", "START", "LIMIT", "OFFSET", "FETCH", "FOR", "WHEN", "THEN", "RETURNING", "PIVOT",
            "UNPIVOT", "MODEL", "WINDOW", "QUALIFY", "NATURAL", "LATERAL", "APPLY", "STRAIGHT_JOIN",
            "INSERT", "INTO", "UPDATE", "DELETE", "MERGE", "WITH");

    /**
     * SQL 텍스트에서 테이블 이름 (FROM/JOIN 목록·INTO·UPDATE·MERGE USING). 서브쿼리·함수 괄호 안의 FROM
     * (EXTRACT(x FROM y) 등)은 괄호가 SELECT 로 시작할 때만 본다. WITH 이름·DUAL 은 뺀다.
     * 정규식 수준 추출이라 동적 SQL·DB 고유 문법에서 빠지거나 더 나올 수 있다 — notes 에 남길 것.
     */
    public static List<String> tables(String sql) {
        List<String> tokens = tokenize(stripComments(sql));
        Set<String> cte = new HashSet<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            String t = tokens.get(i).toUpperCase();
            if ((t.equals("WITH") || t.equals(",") || t.equals("RECURSIVE")) && isIdent(tokens.get(i + 1))
                    && tokens.get(i + 2).equalsIgnoreCase("AS")) {
                cte.add(tokens.get(i + 1).toUpperCase());
            }
        }
        Map<String, String> found = new LinkedHashMap<>();
        // 괄호마다 SQL 문맥인지 (최상위 = true)
        List<Boolean> ctx = new ArrayList<>(List.of(true));
        for (int i = 0; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            String up = tok.toUpperCase();
            if (tok.equals("(")) {
                String next = i + 1 < tokens.size() ? tokens.get(i + 1).toUpperCase() : "";
                ctx.add(next.equals("SELECT") || next.equals("WITH"));
                continue;
            }
            if (tok.equals(")")) {
                if (ctx.size() > 1) {
                    ctx.remove(ctx.size() - 1);
                }
                continue;
            }
            if (!ctx.get(ctx.size() - 1)) {
                continue;
            }
            String prev = i > 0 ? tokens.get(i - 1).toUpperCase() : "";
            switch (up) {
                case "FROM" -> i = list(tokens, i + 1, found, cte);
                case "JOIN", "USING" -> one(tokens, i + 1, found, cte, false);
                case "INTO" -> one(tokens, i + 1, found, cte, true); // INSERT INTO T (컬럼…) — 괄호는 컬럼 목록
                case "UPDATE" -> {
                    if (!prev.equals("FOR") && !prev.equals("KEY") && !prev.equals("THEN")) {
                        one(tokens, i + 1, found, cte, false);
                    }
                }
                default -> {
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** FROM a x, b y, (select …) z → a, b (서브쿼리는 안쪽 FROM 이 따로 잡힌다) */
    private static int list(List<String> tokens, int i, Map<String, String> found, Set<String> cte) {
        while (i < tokens.size()) {
            String tok = tokens.get(i);
            if (tok.equals("(")) {
                return i - 1; // 서브쿼리 — 바깥 루프가 괄호 문맥을 처리
            }
            if (!one(tokens, i, found, cte, false)) {
                return i - 1;
            }
            // 별칭 등을 지나 쉼표면 다음 항목, 아니면 끝
            int j = i + 1;
            while (j < tokens.size() && !tokens.get(j).equals(",") && !tokens.get(j).equals("(") && !tokens.get(j).equals(")")
                    && !STOP.contains(tokens.get(j).toUpperCase())) {
                j++;
            }
            if (j < tokens.size() && tokens.get(j).equals(",")) {
                i = j + 1;
            } else {
                return j - 1;
            }
        }
        return i;
    }

    private static boolean one(List<String> tokens, int i, Map<String, String> found, Set<String> cte, boolean columnList) {
        if (i >= tokens.size()) {
            return false;
        }
        String t = tokens.get(i);
        if (!isIdent(t) || STOP.contains(t.toUpperCase())) {
            return false;
        }
        if (!columnList && i + 1 < tokens.size() && tokens.get(i + 1).equals("(")) {
            return false; // 함수 (TABLE(…) 등)
        }
        String key = t.toUpperCase();
        if (key.equals("DUAL") || cte.contains(key)) {
            return true;
        }
        found.putIfAbsent(key, t);
        return true;
    }

    private static boolean isIdent(String t) {
        return t.matches("[A-Za-z_\"`\\[][\\w$#.\"`\\[\\]@]*") || t.matches("\\$\\{[^}]+}([\\w$#.]*)?") || t.matches("[\\w$#.]*\\$\\{[^}]+}[\\w$#.]*");
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }

    /** 식별자(점·${…}·#{…} 포함)·괄호·쉼표 단위. 문자열 리터럴은 버린다 */
    private static List<String> tokenize(String sql) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n && !(sql.charAt(j) == '\'' && (j + 1 >= n || sql.charAt(j + 1) != '\''))) {
                    j += sql.charAt(j) == '\'' ? 2 : 1;
                }
                i = j + 1;
            } else if (c == '(' || c == ')' || c == ',') {
                out.add(String.valueOf(c));
                i++;
            } else if ((c == '#' || c == '$') && i + 1 < n && sql.charAt(i + 1) == '{') {
                int j = sql.indexOf('}', i);
                j = j < 0 ? n : j + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || "_$#.".indexOf(sql.charAt(j)) >= 0)) {
                    j++;
                }
                out.add(sql.substring(i, j));
                i = j;
            } else if (Character.isLetterOrDigit(c) || "_\"`[".indexOf(c) >= 0) {
                int j = i + 1;
                while (j < n) {
                    char d = sql.charAt(j);
                    if (Character.isLetterOrDigit(d) || "_$#.\"`[]@".indexOf(d) >= 0) {
                        j++;
                    } else if (d == '$' || d == '#') {
                        j++;
                    } else if (d == '{' && sql.charAt(j - 1) == '$') {
                        int k = sql.indexOf('}', j);
                        j = k < 0 ? n : k + 1;
                    } else {
                        break;
                    }
                }
                out.add(sql.substring(i, j));
                i = j;
            } else {
                out.add(String.valueOf(c));
                i++;
            }
        }
        return out;
    }
}
