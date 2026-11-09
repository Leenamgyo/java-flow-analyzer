package analyzer.sql;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ClassRole;
import analyzer.model.MethodInfo;
import analyzer.model.SqlRef;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 호출 → 매퍼 XML statement (SqlRef). 두 가지만 판정한다 (추측 금지):
 * <ul>
 *   <li>SqlSession/SqlMapClient 호출 — 수신 객체 타입이 그 타입이거나 수신 객체가 getSqlSession() 등이고, 메서드가
 *       selectList·insert·queryForList … 이며 첫 인자가 문자열(리터럴·상수·그 연결)이면 그 값이 statement id</li>
 *   <li>매퍼 인터페이스 메서드 — 역할 Mapper 인 source 인터페이스의 본문 없는 메서드: namespace = 인터페이스 FQN, id = 메서드명</li>
 * </ul>
 */
public final class SqlCalls {

    static final Set<String> SESSION_METHODS = Set.of("selectOne", "selectList", "selectMap", "selectCursor", "select",
            "insert", "update", "delete", "queryForObject", "queryForList", "queryForMap", "queryForPaginatedList",
            "queryWithRowHandler");
    static final Set<String> SESSION_TYPES = Set.of("SqlSession", "SqlSessionTemplate", "SqlMapClient",
            "SqlMapClientTemplate", "SqlMapSession", "SqlMapExecutor", "SqlMapClientOperations");
    static final Set<String> SESSION_GETTERS = Set.of("getSqlSession", "getSqlSessionTemplate", "getSqlMapClient",
            "getSqlMapClientTemplate");
    static final Set<String> SQL_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete", "SelectProvider",
            "InsertProvider", "UpdateProvider", "DeleteProvider");

    /**
     * SqlSession 타입이 아닌 수신 객체에서도 SQL 호출로 쓰이는 메서드 이름 — 상속 메서드(EgovAbstractDAO·EgovAbstractMapper·
     * SqlSessionDaoSupport·프로젝트 BaseDAO 의 selectList/list/insert …)나 공통 DAO 빈(commonDao.selectList("ns.id"))
     */
    static final Set<String> SQL_VERBS;

    static {
        Set<String> v = new java.util.HashSet<>(SESSION_METHODS);
        v.addAll(Set.of("list", "listWithPaging", "selectByPk", "selectListWithPaging", "selectPaging", "queryForInt",
                "queryForLong", "queryForString", "selectCount", "count", "exists", "merge", "save", "execute"));
        SQL_VERBS = Set.copyOf(v);
    }

    private SqlCalls() {
    }

    /**
     * SqlSession 류가 아닌 호출 중 SQL 동사 이름 + 첫 인자가 문자열(리터럴·상수·그 연결)이고 statement id 모양인 것 →
     * XML 확인이 필요한 SqlRef (requiresXmlMatch). 아니면 null. 매퍼 XML 에 그 id 가 없으면 나중에 버린다.
     */
    public static SqlRef byStatementArgument(MethodCallExpr call, Function<Expression, Optional<String>> strings) {
        if (!SQL_VERBS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return null;
        }
        SqlRef ref = sessionRef(call, strings);
        if ("UNRESOLVED".equals(ref.basis) || !ref.statementId.matches("[A-Za-z_$][\\w$.\\-]*")) {
            return null;
        }
        ref.requiresXmlMatch = true;
        ref.notes.add("SqlSession 타입이 아닌 호출(" + call.getScope().map(x -> x + ".").orElse("") + call.getNameAsString()
                + ") — 첫 인자가 매퍼 XML statement 와 일치해서 SQL 호출로 봄");
        return ref;
    }

    /**
     * Mapper 역할이 아닌 source 인터페이스(예: MyBatis 매퍼를 *DAO 로 이름 붙인 경우)의 본문 없는 메서드 →
     * FQN.메서드명. 인터페이스에 @Mapper 가 있으면 그대로, 없으면 XML 에 그 statement 가 있을 때만 (requiresXmlMatch).
     */
    public static SqlRef interfaceRef(AnalysisResult r, CallSiteInfo s) {
        if (s.targetMethodId == null) {
            return null;
        }
        MethodInfo m = r.method(s.targetMethodId);
        ClassInfo c = r.classOf(m);
        if (m.hasBody || !"INTERFACE".equals(c.kind) || c.role == ClassRole.MAPPER || c.qualifiedName == null
                || m.origin != analyzer.model.Origin.SOURCE) {
            return null;
        }
        SqlRef ref = new SqlRef();
        ref.statementId = c.qualifiedName + "." + m.name;
        ref.basis = "MAPPER_INTERFACE";
        boolean mapperAnnotation = c.annotations.stream().anyMatch(a -> a.equals("Mapper") || a.endsWith(".Mapper"));
        if (m.annotations.stream().anyMatch(SQL_ANNOTATIONS::contains)) {
            ref.xmlStatus = "ANNOTATION";
            ref.notes.add("SQL 이 메서드 어노테이션(@" + String.join(", @", m.annotations.stream()
                    .filter(SQL_ANNOTATIONS::contains).toList()) + ")에 있음 — XML 없음");
            return ref;
        }
        ref.requiresXmlMatch = !mapperAnnotation;
        ref.notes.add("역할 " + c.role.label() + " 인터페이스 — " + (mapperAnnotation ? "@Mapper" : "XML namespace 가 인터페이스 FQN")
                + " 라서 매퍼로 봄");
        return ref;
    }

    /** SqlSession 류 호출인가 (수신 객체 타입 또는 getSqlSession() 수신) */
    public static boolean isSessionCall(MethodCallExpr call, String receiverType) {
        if (!SESSION_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return false;
        }
        if (receiverType != null) {
            String simple = receiverType.replaceAll("<.*", "");
            simple = simple.substring(simple.lastIndexOf('.') + 1);
            if (SESSION_TYPES.contains(simple)) {
                return true;
            }
        }
        return call.getScope().filter(s -> s instanceof MethodCallExpr m && SESSION_GETTERS.contains(m.getNameAsString())
                && m.getArguments().isEmpty()).isPresent();
    }

    /** SqlSession 호출의 statement id (첫 인자). strings: 리터럴·상수 해석기 */
    public static SqlRef sessionRef(MethodCallExpr call, Function<Expression, Optional<String>> strings) {
        SqlRef ref = new SqlRef();
        Expression arg = call.getArgument(0);
        boolean[] constant = {false};
        Optional<String> v = eval(arg, strings, constant);
        if (v.isPresent()) {
            ref.statementId = v.get();
            ref.basis = constant[0] ? "CONSTANT" : "LITERAL";
        } else {
            ref.statementId = arg.toString();
            ref.basis = "UNRESOLVED";
        }
        return ref;
    }

    /** "ns." + "selectX" / NAMESPACE + "selectX" 처럼 문자열 리터럴·상수의 + 연결만 계산 */
    private static Optional<String> eval(Expression e, Function<Expression, Optional<String>> strings, boolean[] constant) {
        while (e instanceof EnclosedExpr en) {
            e = en.getInner();
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> l = eval(b.getLeft(), strings, constant);
            Optional<String> r = eval(b.getRight(), strings, constant);
            return l.isPresent() && r.isPresent() ? Optional.of(l.get() + r.get()) : Optional.empty();
        }
        if (!e.isStringLiteralExpr()) {
            constant[0] = true;
        }
        return strings.apply(e);
    }

    /** 매퍼 인터페이스 메서드 호출이면 SqlRef (아니면 null) */
    public static SqlRef mapperRef(AnalysisResult r, CallSiteInfo s) {
        if (s.targetMethodId == null) {
            return null;
        }
        MethodInfo m = r.method(s.targetMethodId);
        ClassInfo c = r.classOf(m);
        if (m.hasBody || !"INTERFACE".equals(c.kind) || c.role != ClassRole.MAPPER || c.qualifiedName == null) {
            return null;
        }
        SqlRef ref = new SqlRef();
        ref.statementId = c.qualifiedName + "." + m.name;
        ref.basis = "MAPPER_INTERFACE";
        if (m.annotations.stream().anyMatch(SQL_ANNOTATIONS::contains)) {
            ref.xmlStatus = "ANNOTATION";
            ref.notes.add("SQL 이 메서드 어노테이션(@" + String.join(", @", m.annotations.stream()
                    .filter(SQL_ANNOTATIONS::contains).toList()) + ")에 있음 — XML 없음");
        }
        return ref;
    }

    /** 매퍼 XML 을 찾을 폴더: 설정값, 없으면 source root 와 그 옆 resources (src/main/java → src/main/resources) */
    public static List<Path> xmlRoots(List<String> configured, List<Path> sourceRoots) {
        List<Path> out = new ArrayList<>();
        if (configured != null && !configured.isEmpty()) {
            configured.forEach(p -> out.add(Path.of(p)));
            return out;
        }
        for (Path root : sourceRoots) {
            out.add(root);
            Path parent = root.getParent();
            if (root.getFileName() != null && root.getFileName().toString().equals("java") && parent != null) {
                Path res = parent.resolve("resources");
                if (Files.isDirectory(res)) {
                    out.add(res);
                }
            }
        }
        return out;
    }

    /** XML 에서 statement 를 찾아 SqlRef 를 채운다 (파일·namespace·id·종류·테이블). 쿼리 본문은 남기지 않는다 */
    public static void match(SqlRef ref, MapperXmlIndex idx, Function<Path, String> relative) {
        if ("ANNOTATION".equals(ref.xmlStatus)) {
            return;
        }
        if ("UNRESOLVED".equals(ref.basis)) {
            ref.xmlStatus = "SKIPPED";
            ref.notes.add("statement id 가 식이라 확정 불가: " + ref.statementId);
            return;
        }
        List<MapperXmlIndex.Statement> found = idx.find(ref.statementId);
        if (found.isEmpty()) {
            ref.xmlStatus = "NOT_FOUND";
            return;
        }
        if (found.size() > 1) {
            ref.xmlStatus = "AMBIGUOUS";
            found.forEach(f -> ref.candidates.add(relative.apply(f.file()) + "#" + f.fullId()));
            return;
        }
        MapperXmlIndex.Statement st = found.get(0);
        ref.xmlStatus = "FOUND";
        ref.xmlFile = relative.apply(st.file());
        ref.namespace = st.namespace().isEmpty() ? null : st.namespace();
        ref.id = st.id();
        ref.statementType = st.type();
        String sql = idx.sqlText(st, ref.notes);
        ref.tables.addAll(MapperXmlIndex.tables(sql));
        if (ref.tables.stream().anyMatch(t -> t.contains("${"))) {
            ref.notes.add("동적 테이블 이름(${…}) — 실행 시 값으로 정해짐");
        }
    }
}
