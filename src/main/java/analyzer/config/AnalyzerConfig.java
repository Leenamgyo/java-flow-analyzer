package analyzer.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** config/analyzer.yml 바인딩 (kebab-case). */
public class AnalyzerConfig {
    public Target target = new Target();
    public Analysis analysis = new Analysis();
    public Output output = new Output();
    public List<RequestParameterRule> requestParameterRules = new ArrayList<>();
    public Phases phases = new Phases();

    /**
     * 단계 보기(바인딩 › 체크 › 로직 › 후처리) 판정 규칙. 결과는 phase-flowchart.mmd / phase-summary.md.
     * 판정은 분석 모델만 사용한다(요청 읽기·바인딩·역할·분기 종료 형태).
     */
    public static class Phases {
        public boolean enabled = true;
        /** 이 역할의 클래스 호출 = 로직 */
        public List<String> logicRoles = new ArrayList<>(List.of("Service", "Biz", "DAO", "Repository", "Mapper"));
        /** 이 역할의 클래스 호출 = 체크 */
        public List<String> checkRoles = new ArrayList<>(List.of("Validator"));
        /** 이 이름(* 와일드카드)의 source 메서드 호출 = 체크 (본문이 로직·바인딩을 포함하면 본문 기준) */
        public List<String> checkMethodNames = new ArrayList<>(List.of("validate*", "check*"));
    }

    public static class Target {
        /** 단일 소스 루트 (source-roots 와 함께 쓰면 맨 앞에 합쳐진다) */
        public String sourceRoot;
        /** 멀티 모듈: 여러 소스 루트 (예: actions/src/main/java, lib/src/main/java). 모두 읽기 전용 */
        public List<String> sourceRoots = new ArrayList<>();

        /** 실제 사용할 소스 루트 목록: source-root + source-roots (순서 유지, 중복 제거) */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public List<String> effectiveSourceRoots() {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            if (sourceRoot != null && !sourceRoot.isBlank()) {
                all.add(sourceRoot);
            }
            sourceRoots.stream().filter(x -> x != null && !x.isBlank()).forEach(all::add);
            return List.copyOf(all);
        }
        @JsonProperty("class")
        public String className;
        public String entryMethod;
        /** 오버로딩된 진입 메서드 식별용 파라미터 타입(단순명). null 이면 미지정 */
        public List<String> entryMethodParameterTypes;
    }

    public static class Analysis {
        public int maxDepth = 1;
        public boolean expandSameClassMethods = true;
        public boolean includeConditions = true;
        public boolean includeExceptions = true;
        public boolean includeRequestParameters = true;
        public boolean includeExternalLibraryCalls = false;
        /** JavaSymbolSolver 에 추가할 의존 jar (선택, 읽기 전용) */
        public List<String> classpathJars = new ArrayList<>();
        /** max-depth 를 넘어도 본문을 분석할 클래스 역할 (예: Service, Biz, DAO, Mapper). 비어 있으면 미적용 */
        public List<String> expandRoles = new ArrayList<>();
        /**
         * 본문 없는 인터페이스/추상 메서드 호출에서, source-root 내 구현 클래스가 정확히 1개면
         * 그 구현 메서드를 '구현 후보'로 연결한다(런타임 DI 구현체 확정이 아님).
         */
        public boolean linkSingleImplementation = false;
        /** VO/DTO/Command 및 Map 바인딩(setter·builder·생성자·필드 대입·put·복사)을 수집하고 값의 출처를 추적한다 */
        public boolean includeBindings = true;
        /** 선언 뒤 값이 바뀌는 지역 변수·파라미터의 변경 이력(=, +=, ++ …)을 수집한다 */
        public boolean includeVariableChanges = true;
        /**
         * 상속(extends) 정보를 분석 내용에 넣는다. false 면
         * ① VO 필드 커버리지(선언·미할당·초기값만·확인 불가)에 상위 클래스 필드를 넣지 않고
         * ② 클래스의 extends 목록을 비우고 ③ 상위 클래스 없이도 같은 역할이면 상위 클래스 역할 근거(SUPERTYPE)를 뺀다.
         * 상속 필드에 실제로 값을 넣는 바인딩·상위 클래스 메서드 호출은 어느 쪽이든 그대로 기록한다.
         */
        public boolean includeInheritance = false;
    }

    public static class Output {
        public String directory = "output";
        public boolean yaml = true;
        public boolean tree = true;
        public boolean treeIncludeControlBlocks = true;
        public boolean mermaid = true;
        public String direction = "TD";
        /** Mermaid 레이아웃: dagre(Mermaid 기본) | elk(직각 간선, 뷰어가 ELK 지원 시) */
        public String layout = "dagre";
        /** 흐름도 색 테마: light(기본 — 흰 바탕) | dark(어두운 노드 바탕·밝은 글자, Mermaid dark 테마) */
        public String colorTheme = "light";
        /** 흐름도 .mmd 를 flowchart-svg/ 에 SVG 로도 출력 (Mermaid CLI mmdc 필요, 없으면 경고 후 생략) */
        public boolean flowchartSvg = true;
        /** mmdc 실행 파일 경로 (비우면 PATH 의 mmdc) */
        public String mermaidCli = "";
        /** 흐름도 노드 한 줄 최대 길이 — 넘으면 쉼표·연산자·체인 앞에서 끊고 다음 줄은 "> " 로 시작. 0 = 끊지 않음 */
        public int maxLineLength = 100;
        /** flowchart.mmd 와 함께 메서드별 파일(flowchart/) + 전체 구조 + 합본(flowchart.md) 출력 */
        public boolean splitFlowchart = true;
        /**
         * 흐름도에서 뺄 호출·메서드 (사용자 관리). 기본은 비어 있고 흐름도는 분석한 코드를 빠짐없이 그린다.
         * 패턴(* 와일드카드)은 호출 대상 "클래스.메서드"(단순명·FQN), 호출 원문 "수신객체.메서드" 와 비교한다.
         * 예: "*Util.*", "LOG.debug", "com.example.common.AuditHelper.*". 맞는 메서드는 본문 상자도 그리지 않는다.
         */
        public List<String> flowchartExclude = new ArrayList<>();
        /** call-tree.txt 에서 뺄 호출 (flowchart-exclude 와 같은 패턴). 맞는 호출은 줄도, 그 아래 전개도 없다 */
        public List<String> callTreeExclude = new ArrayList<>();
    }

    /**
     * 사용자 정의 Request 읽기 규칙 (바인딩으로 볼 프로젝트 고유 메서드).
     * class 를 생략하면 메서드 이름만으로 매칭한다 (상속받아 바로 부르는 getXxxParameter(request, "x") 등).
     */
    public static class RequestParameterRule {
        @JsonProperty("class")
        public String className;
        public List<MethodRule> methods = new ArrayList<>();
    }

    public static class MethodRule {
        public String name;
        /** 파라미터명 인자 위치 (0부터). -1 이면 전체 파라미터 조회. parameter 와 함께 쓸 수 없음 */
        public Integer nameArgIndex;
        /** 이름 인자가 없는 메서드(getSystemId(request))의 고정 파라미터명. name-arg-index·parameter 둘 다 없으면 메서드명에서 (getSystemId → systemId) */
        public String parameter;
        /** 기본값 래퍼: 이 위치의 인자가 요청 읽기면 그 값을 감싸 기본값을 붙이는 메서드 (defVal(getParam(req,"x"), "0") → wraps: 0) */
        public Integer wraps;
        public Integer defaultArgIndex;
        public String type;
    }
}
