package com.example.action;

import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.model.NoticeVO;
import com.example.dao.OrderDAO;
import com.example.mapper.UserMapper;
import com.example.model.SearchVO;
import com.example.biz.NoticeWriteBiz;
import com.example.support.Cache;
import com.example.support.LogService;
import com.example.support.Registry;
import com.example.util.StringUtil;
import com.example.util.ProductUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** 흐름도 규칙의 경계 사례 */
public class EdgeAction {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeAction.class);
    private final Cache cache = new Cache();
    private LogService logService;
    private NoticeWriteBiz noticeWriteBiz;

    // ---- 중첩 반복문 + 반복문 안 상태 변화(버전이 back edge 에서 무한히 늘지 않아야)
    public void nestedLoops(List<String> rows, int n) {
        for (String row : rows) {
            touch();
            for (int i = 0; i < n; i++) {
                if (i % 2 == 0) {
                    touch();
                } else {
                    cache.clear();
                }
                while (n > 10) {
                    n = n - 1;
                    touch();
                }
            }
        }
        touch();
    }

    // ---- 반복문을 빠져나가는 return / throw / break / continue
    public int loopExits(List<String> rows) {
        int count = 0;
        for (String row : rows) {
            if (row == null) {
                continue;
            }
            if (row.isEmpty()) {
                break;
            }
            if (row.equals("x")) {
                return -1;
            }
            if (row.equals("y")) {
                throw new IllegalStateException(row);
            }
            count++;
            touch();
        }
        return count;
    }

    // ---- 바인딩 함수 사슬 A → B → C, 두 번 호출(SHARED), 재귀 바인딩 함수
    public void bindingChain(HttpServletRequest request) {
        String mode = request.getParameter("mode");
        SearchVO a = outer(request);
        SearchVO b = outer(request);
        SearchVO c = selfRef(request, 3);
        touch();
    }

    private SearchVO outer(HttpServletRequest request) {
        SearchVO vo = new SearchVO();
        vo.setKeyword(middle(request.getParameter("q")));
        vo.setSort(request.getParameter("sort"));
        return vo;
    }

    private String middle(String v) {
        return inner(v);
    }

    private String inner(String v) {
        return v == null ? "" : v.trim();
    }

    private SearchVO selfRef(HttpServletRequest request, int depth) {
        SearchVO vo = new SearchVO();
        vo.setMode(request.getParameter("m" + depth));
        return depth > 0 ? selfRef(request, depth - 1) : vo;
    }

    // ---- 상태 변화 대상: this / 필드 / static 다른 클래스 / 체인 수신 객체
    public void stateTargets(String key) {
        touch();
        cache.clear();
        Registry.register(key);
        cache.clear();
        getCache().put(key, key);
        this.touch();
        Registry.register(key + "2");
    }

    private Cache getCache() {
        return cache;
    }

    // ---- 로그 변형: 레벨·System.err·printStackTrace·요청 읽기를 품은 로그·로그 같은 source 메서드
    public void logVariants(HttpServletRequest request) {
        LOG.info("id=" + request.getParameter("id"));
        LOG.error("fail", new RuntimeException());
        System.err.println("err");
        logService.info("not a logger");
        if (LOG.isDebugEnabled()) {
            LOG.debug("debug");
        }
        try {
            touch();
        } catch (RuntimeException e) {
            e.printStackTrace();
            LOG.warn("caught", e);
        }
    }

    // ---- 반복문 안 try/catch/finally
    public void tryInLoop(List<String> rows) {
        for (String row : rows) {
            try {
                touch();
                if (row.isEmpty()) {
                    throw new IllegalArgumentException(row);
                }
            } catch (IllegalArgumentException e) {
                LOG.warn("skip " + row);
            } finally {
                cache.clear();
            }
        }
    }

    // ---- 한 문장에 요청 읽기 여러 개
    public void multiReadStatement(HttpServletRequest request) {
        String k = request.getParameter("a") + request.getParameter("b");
        int n = Integer.parseInt(request.getParameter("n")) + Integer.parseInt(request.getParameter("m"));
        SearchVO vo = new SearchVO();
        vo.setKeyword(k + n);
    }

    // ---- 빈 반복문 / 헤더 호출만 있는 반복문 / 무한 루프에서 return
    public void emptyLoops(List<String> rows) {
        while (rows.isEmpty()) {
        }
        do {
        } while (check());
        for (;;) {
            if (check()) {
                return;
            }
        }
    }

    // ---- 조건식 안 호출을 노드로 그리는 while (반복할 때 조건 호출부터 다시)
    /** Action 안 클래스 변형: new 를 변수에 받지 않음 · 생성자 두 곳 · 생성자 안에서 자기 메서드 load(request) */
    public String holderVariants(HttpServletRequest request) {
        String a = describe(new Holder(request));
        Holder h = new Holder(request);
        return a + h.id;
    }

    String describe(Holder holder) {
        return holder.id;
    }

    public static class Holder {
        String id;
        String name;

        public Holder(HttpServletRequest request) {
            load(request);
        }

        void load(HttpServletRequest request) {
            id = request.getParameter("id");
            this.name = request.getParameter("name");
        }
    }

    /** for-each 안: 첫 문장이 list.add(…) 이고 그 뒤 if / else if (사용자 보고: 흐름도가 이상해짐) */
    public List<String> loopAddThenIfElse(List<NoticeVO> resultList) {
        List<String> list = new ArrayList<>();
        for (NoticeVO vo : resultList) {
            list.add(vo.getTitle());
            if (vo.getTitle() == null) {
                touch();
            } else if (vo.getTitle().isEmpty()) {
                log.info("empty");
            }
        }
        return list;
    }

    public void whileWithCall() {
        while (check()) {
            touch();
        }
    }

    private boolean check() {
        return true;
    }

    void touch() {
    }

    // ---- 문장 전체가 JDK 호출뿐인 if 본문 (include-external-library-calls: false 에서도 흐름도에 남아야)
    public List<String> libraryOnlyStatements(String callerId, String versionCode) {
        List<String> extraInfo = new ArrayList<>();
        if (StringUtil.isNotEmpty(callerId) && StringUtil.isNotEmpty(versionCode)) { // 주석
            extraInfo.add("DELTA");
        }
        String first = extraInfo.isEmpty() ? null : extraInfo.get(0);
        touch();
        return extraInfo;
    }

    // ---- 매퍼 SQL: 매퍼 인터페이스 호출·SqlSession 호출 → XML 파일·id·테이블 (쿼리 본문은 안 씀)
    private UserMapper userMapper;
    private OrderDAO orderDAO;

    public String sqlCalls(String userId) {
        String name = userMapper.selectUser(userId);
        userMapper.updateUserName(userId);
        int n = userMapper.countUsers();
        orderDAO.selectOrders(userId);
        orderDAO.insertOrder(name);
        orderDAO.deleteOrder(userId);
        orderDAO.dynamic(userId);
        orderDAO.missing();
        return name + n;
    }

    // ---- return 식 자체가 세부 메서드 호출: 호출 노드를 따로 만들지 않고 return 노드에서 ==> (중복 노드·간선 없음)
    public String returnsDetailCall(String code) {
        if (code == null) {
            return errorView("empty", code);
        }
        return code;
    }

    private String errorView(String key, String code) {
        String msg = key + ":" + code;
        return msg;
    }

    // ---- Map 키가 리터럴·상수가 아니면(식) 이름 미확정 — 흐름도는 ?(…) 대신 코드 그대로 [식]
    public Map<String, Object> mapDynamicKey(HttpServletRequest request, SearchVO keyVO) {
        Map<String, Object> m = new HashMap<>();
        m.put(keyVO.getKeyword(), request.getParameter("v"));
        String w = request.getParameter(keyVO.getKeyword());
        m.put("w", w);
        return m;
    }

    // ---- 소스 밖 타입(HttpServletRequest)의 호출: 시그니처를 몰라 PARTIALLY_RESOLVED 지만 미해결 색이 아니라 외부 호출 색
    public String externalRequestCall(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri;
    }

    // ---- 이어지는 일반 호출은 노드 1개 (기본 블록처럼), 상태 변화가 끼면 나뉜다
    public String plainCallRun(List<String> extraInfo, String v) {
        extraInfo.add("A");
        extraInfo.add("B");
        String t = v.trim();
        long n = Long.parseLong(v);
        touch();
        extraInfo.add("C");
        return t;
    }

    // ---- 상위 클래스(BaseVO) 필드: include-inheritance 에 따라 선언 필드 목록에 포함/제외
    public void inheritedFields(HttpServletRequest request) {
        NoticeVO vo = new NoticeVO();
        vo.setTitle(request.getParameter("title"));
        vo.setRegId("admin");
    }

    // ---- 상위 클래스(BaseVO)를 쓰지 않음: BaseVO 는 출력에 나오지 않아야
    public void inheritedUnused(HttpServletRequest request) {
        NoticeVO vo = new NoticeVO();
        vo.setTitle(request.getParameter("title"));
    }

    // ---- 초기값만 있는 선언 (호출 없음): 흐름도에 선언 코드가 보여야
    public String initialValues(HttpServletRequest request, List<String> rows) {
        boolean data = false;
        int count = 0;
        String mode = "LIST";
        String label;
        long limit = MAX_ROWS * 2L;
        List<String> names = new ArrayList<>();
        if (rows.isEmpty()) {
            data = true;
        }
        for (String row : rows) {
            count++;
            names.add(row);
        }
        String result = mode + count + data + limit;
        return result;
    }

    private static final int MAX_ROWS = 100;

    // ---- 함수가 내재된(펼쳐지는) 바인딩 함수의 중복 펼침 경우들 (DupInlineTest) ----

    /** 같은 함수 안에서 같은 도우미를 여러 번 + 다른 바인딩 함수도 같은 도우미 */
    public void inlineRepeats(HttpServletRequest request) {
        SearchVO a = twice(request);
        SearchVO b = withMode(request);
        String k = middle(request.getParameter("k"));
        touch();
    }

    private SearchVO twice(HttpServletRequest request) {
        SearchVO vo = new SearchVO();
        vo.setKeyword(middle(request.getParameter("q")));
        vo.setSort(middle(request.getParameter("sort")));
        vo.setMode(inner(request.getParameter("mode")));
        return vo;
    }

    private SearchVO withMode(HttpServletRequest request) {
        SearchVO vo = twice(request);
        vo.setMode(middle(request.getParameter("m2")));
        return vo;
    }

    /** 인자 안에 바인딩 함수, 반복 안에서 같은 함수, 분기마다 같은 함수 */
    public void inlineNested(HttpServletRequest request, List<String> keys) {
        String v = middle(inner(request.getParameter("x")));
        for (String key : keys) {
            String w = middle(request.getParameter(key));
            touch();
        }
        if (v.isEmpty()) {
            SearchVO a = outer(request);
        } else {
            SearchVO b = outer(request);
            SearchVO c = outer(request);
        }
        touch();
    }

    // ---- 상태 변화(다시 대입) + 메서드 호출이 엮인 경우 (StateCallTest) ----
    public String stateWithCalls(HttpServletRequest request) {
        String userId = request.getParameter("userId");
        userId = normalizeId(userId);
        userId = normalizeId(userId) + "_x";
        userId = userId.trim();
        userId = pickId(request);
        int count = 0;
        count += normalizeId(userId).length();
        if (userId.isEmpty()) {
            userId = normalizeId("guest");
        }
        return userId + count;
    }

    private String normalizeId(String v) {
        if (v == null) {
            return "";
        }
        return v.toLowerCase();
    }

    private String pickId(HttpServletRequest request) {
        return request.getParameter("uid");
    }

    // ---- Biz 로 넘긴 VO 를 Biz 안에서도 추적 (BizVoTest) ----
    public void bizVo(HttpServletRequest request) {
        NoticeVO vo = new NoticeVO();
        vo.setTitle(request.getParameter("title"));
        noticeWriteBiz.write(vo, request.getParameter("body"));
    }

    // ---- VO 연결의 실행 순서: 호출 전 헬퍼의 set 은 넣고, 호출 뒤 헬퍼의 set 은 빼기 (BizVoTest) ----
    public void bizVoOrder(HttpServletRequest request) {
        NoticeVO vo = new NoticeVO();
        fillTitle(vo, request);
        noticeWriteBiz.write(vo, "b");
        fillBodyLater(vo);
    }

    private void fillTitle(NoticeVO vo, HttpServletRequest request) {
        vo.setTitle(request.getParameter("t"));
    }

    private void fillBodyLater(NoticeVO vo) {
        vo.setBody("late");
    }

    // ---- Util 이 빈 이름(Wcommon.getBean("…"))으로 Biz 를 부르는 경우 (UtilBeanTest) ----
    public void utilBeans(HttpServletRequest request) throws Exception {
        String id = request.getParameter("id");
        String a = ProductUtil.info(id);
        String b = ProductUtil.infoInline(id);
        Object c = ProductUtil.infoReflect(id);
        String d = ProductUtil.legacy(id);
        String e = ProductUtil.unknown(id);
    }
}
