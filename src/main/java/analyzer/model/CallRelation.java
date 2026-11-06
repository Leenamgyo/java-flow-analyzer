package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/** 메서드 → 메서드 집계 관계. 호출 위치별 상세는 callSiteIds 로 연결된다. */
public final class CallRelation {
    public String id;
    public String fromMethodId;
    public String toMethodId;
    public final List<String> callSiteIds = new ArrayList<>();
    /** 호출 위치 Depth 의 최소값 */
    public int depth;
    public boolean cycle;
}
