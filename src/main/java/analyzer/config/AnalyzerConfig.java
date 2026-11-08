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
        public Integer defaultArgIndex;
        public String type;
    }
}
