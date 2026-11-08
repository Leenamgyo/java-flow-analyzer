package analyzer.phase;

import analyzer.model.Binding;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.RequestParameterInfo;
import analyzer.model.SourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 진입 메서드를 단계(바인딩 › 체크 › 로직 › 후처리)로 나눈 보기. 공통 분석 모델에서만 계산한다.
 * <ul>
 *   <li>step = 진입 메서드 최상위 문장 단위 요소(try 본문은 펼침): 호출 · 제어 블록 · 호출 없는 바인딩(생성자 등)</li>
 *   <li>영역: PRE(첫 로직 전) · LOGIC(첫 로직 ~ 마지막 로직) · POST(마지막 로직 뒤)</li>
 * </ul>
 */
public final class PhaseModel {

    public enum Region { PRE, LOGIC, POST }

    /** 진입 메서드 최상위 요소 1개 */
    public static final class Step {
        public int index;
        /** CallSiteInfo | ControlBlock | Binding(호출 없는 바인딩) | VariableChange(호출 없는 변수 값 변경) */
        public Object element;
        public Phase phase;
        /** 판정 근거 */
        public String reason;
        public SourceLocation location;
        public Region region;
        /** 호출 요소: 같은 문장에 묶인 진입 메서드 호출들 (안쪽 → 바깥, element 가 가장 바깥) */
        public final List<CallSiteInfo> group = new ArrayList<>();
        /** 이 요소에서(전개된 메서드 포함) 실행되는 호출·블록·바인딩·요청 읽기 */
        public final List<CallSiteInfo> calls = new ArrayList<>();
        public final List<ControlBlock> blocks = new ArrayList<>();
        public final List<Binding> bindings = new ArrayList<>();
        /** 이 요소 안의 진입 메서드 변수 값 변경 (선언 제외) */
        public final List<analyzer.model.VariableChange> changes = new ArrayList<>();
        public final List<RequestParameterInfo> parameters = new ArrayList<>();
        /** 이 요소가 본문을 전개한 메서드 (진입 메서드 제외) */
        public final Set<String> reachedMethods = new java.util.LinkedHashSet<>();
        /** 이 요소 안의 체크(분기 종료형 조건) */
        public final List<CheckItem> checks = new ArrayList<>();
        /** 값 조회: 결과를 받은 변수 (바인딩 출처로 쓰임) */
        public String fetchVariable;
    }

    /**
     * 체크 1건: 조건 → 결과.
     *
     * @param condition 조건(상위 조건 › 조건) 또는 체크 호출식
     * @param outcome   throw/return 원문 (+ 잡는 catch) 또는 "본문 미분석"
     * @param context   진입 메서드 / 바인딩 중: … / 체크: Class.method …
     */
    public record CheckItem(String condition, String outcome, String context, String methodId, String blockId,
                            SourceLocation location) {
    }

    public final List<Step> steps = new ArrayList<>();
    public int firstLogic = -1;
    public int lastLogic = -1;
    /** 단계 흐름도에서 일반 노드로 그리지 않는 진입 메서드 호출·블록 (요약 노드로 대체) */
    public final Set<String> suppressedCalls = new HashSet<>();
    public final Set<String> suppressedBlocks = new HashSet<>();
    /** 로직 역할 (보고서의 로직 호출 트리용) */
    public final Set<analyzer.model.ClassRole> logicRoles = java.util.EnumSet.noneOf(analyzer.model.ClassRole.class);
    /** 판정 규칙 표시용 */
    public final List<String> rules = new ArrayList<>();

    public List<Step> region(Region r) {
        return steps.stream().filter(s -> s.region == r).toList();
    }

    /**
     * PRE 영역의 체크 (바인딩 중 발견된 체크 포함), 소스 순서.
     * 값 조회(member = checkLogin(request))는 바인딩이므로 그 안의 실패 조건은 여기 넣지 않고 ① 바인딩에 붙인다.
     */
    public List<CheckItem> preChecks() {
        List<CheckItem> out = new ArrayList<>();
        for (Step s : region(Region.PRE)) {
            if (s.fetchVariable == null) {
                out.addAll(s.checks);
            }
        }
        return out;
    }
}
