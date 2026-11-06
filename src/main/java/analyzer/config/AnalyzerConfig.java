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

    public static class Target {
        public String sourceRoot;
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
    }

    public static class Output {
        public String directory = "output";
        public boolean yaml = true;
        public boolean tree = true;
        public boolean treeIncludeControlBlocks = true;
        public boolean mermaid = true;
        public String direction = "LR";
    }

    /** 사용자 정의 Request 유틸리티 규칙 */
    public static class RequestParameterRule {
        @JsonProperty("class")
        public String className;
        public List<MethodRule> methods = new ArrayList<>();
    }

    public static class MethodRule {
        public String name;
        /** 파라미터명 인자 위치 (0부터). -1 이면 전체 파라미터 조회 */
        public int nameArgIndex = 0;
        public Integer defaultArgIndex;
        public String type;
    }
}
