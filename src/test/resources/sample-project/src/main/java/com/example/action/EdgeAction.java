package com.example.action;

import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.model.SearchVO;
import com.example.support.Cache;
import com.example.support.LogService;
import com.example.support.Registry;

/** 흐름도 규칙의 경계 사례 */
public class EdgeAction {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeAction.class);
    private final Cache cache = new Cache();
    private LogService logService;

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
}
