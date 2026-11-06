# java-flow-analyzer — Claude 작업 가이드

지정한 Action/Controller **하나**의 진입 메서드에서 시작해 Java 소스를 정적 분석하고,
**하나의 공통 모델**로 YAML · 텍스트 트리 · Mermaid · analysis.md 를 생성하는 독립 도구.
원본 요구사항 전문은 [docs/SPEC.md](docs/SPEC.md) — 규칙이 애매하면 그 문서를 기준으로 한다.

## 절대 규칙 (SPEC §1–2)

- **추측 금지.** 소스에 없는 메서드·예외 경로·호출 관계를 만들지 않는다. 확정 못 하면 `UNRESOLVED` / `PARTIALLY_RESOLVED` / `AMBIGUOUS` 로 기록하고 사유(`resolution-reason`)를 남긴다.
- 인터페이스/추상 호출은 **선언 대상까지만**. 런타임 구현체(Spring DI 등)를 연결하지 않는다.
- 호출 대상 메서드가 던지는 예외 타입은 알 수 없으므로 **특정 호출을 catch 로 연결하지 않는다.** (`throw` 문만 catch 매칭)
- 원본 프로젝트는 **읽기 전용**: 수정·실행·빌드 금지, DB/외부 API 연결 금지. output 이 source-root 내부면 거부한다(`Main.outputDir`).
- Git 사용 금지. 분석기 의존성은 원본 프로젝트와 분리(`ReflectionTypeSolver(jreOnly=true)`).
- 참조용으로 읽은 파일은 결과에 넣지 않는다. 결과에는 진입 메서드에서 **도달한** 클래스/메서드만.
- Mermaid/트리/보고서는 **모델에서만** 생성한다. 출력 단계에서 소스를 다시 파싱하지 않는다.
- 구현하지 않은 기능을 완료했다고 쓰지 않는다. 불필요한 기능을 임의로 추가하지 않는다.

## 명령

```bash
./gradlew test                                   # 전체 테스트 (JUnit 5)
./gradlew run                                    # config/analyzer.yml 로 실행 → output/<Class>/
./gradlew run --args="--config config/analyzer.yml --class CaseAction --method throwCases"
#   옵션: --config --source-root --class --method --output
```

- 설치된 JDK 는 26 뿐이라 `options.release = 21` 로 Java 21 타깃 컴파일한다(toolchain 미사용).
- 샘플 원본 프로젝트(분석 대상, 컴파일하지 않음): `src/test/resources/sample-project/src/main/java`
  - `ScpAppListAction` — 실전형 시나리오, `CaseAction` — 테스트 케이스별 진입 메서드, `AppController` — Spring 어노테이션
- 예제 프로젝트: `example/legacy-shop` (Struts Action + Service/Mapper/DAO/Util + Spring Controller), 설정 `example/analyzer.yml`
  - `./gradlew run --args="--config example/analyzer.yml"` → `example/output/OrderListAction/`
  - `./gradlew run --args="--config example/analyzer.yml --class OrderApiController --method list"`
- Mermaid CLI(`mmdc`)가 PATH 에 있으면 렌더링 검증, 없으면 생략하고 analysis.md 에 기록.

## 구조

```
analyzer/
  Main.java              CLI, 모델 무결성 검증 후 출력
  FlowAnalyzer.java      오케스트레이터: BFS 로 메서드 본문 분석, Depth/Expansion/cycle 결정
  config/                analyzer.yml 바인딩(kebab-case, 알 수 없는 키는 오류)
  parser/                ProjectSources(파일 인덱스+지연 파싱+심볼솔버), EntryPointLocator, TypeNames
  classifier/            ClassRoleClassifier
  resolver/              CallResolver(심볼솔버 → 선언타입 폴백), TypeLookup(import/변수/필드 타입)
  controlflow/           MethodBodyWalker(블록+호출 순서), WalkContext, ExceptionTypeMatcher
  parameter/             RequestParameterExtractor(Legacy + 사용자 규칙 + Spring 어노테이션)
  model/                 공통 모델 + ModelValidator
  serializer/            YamlWriter, CallTreeWriter, AnalysisReportWriter, Labels(트리·Mermaid 공용 표시), OutputWriter
  mermaid/               MermaidGraph(중간표현+검증), FlowchartGenerator, MermaidCli
```

## 핵심 설계 결정 (코드만 봐서는 이유가 안 보이는 것)

**Depth / 전개**
- 본문 분석 조건: `대상 Depth < max-depth` **또는** (`expand-same-class-methods` && 호출자와 같은 클래스). 진입 메서드 D0 는 항상 분석.
- BFS 이므로 메서드 Depth = 최단 경로 Depth. 동일 클래스 확장으로 max-depth 를 넘어도 **실제 Depth 를 그대로** 기록(D2, D3…).
- 메서드 본문은 한 번만 분석(MethodInfo 1개). 호출 위치별 `expansion`:
  `EXPANDED`(여기서 전개) · `SHARED`(`expanded-at` 참조) · `CYCLE`(전개 트리상 조상/자기 자신) · `DEPTH_LIMIT` · `NO_BODY`(abstract/interface) · `EXTERNAL` · `NOT_APPLICABLE`(미해결).
  **해석 상태(`resolution`)와 전개 상태(`expansion`)는 별개 축** — depth-limit 을 unresolved 와 섞지 않는다.

**해석**
- 1순위 JavaSymbolSolver. 실패 시 선언부에 명시된 타입(파라미터/지역변수/필드/import/같은 패키지/java.lang)으로 **이름+인자 수** 매칭. 후보 2개 이상이면 `AMBIGUOUS`. 근거는 `resolution-basis` 에 남긴다.
- source-root 밖 타입(예: `HttpServletRequest`)은 시그니처를 모르므로 `PARTIALLY_RESOLVED` + `signature-resolved: false`, 시그니처는 `getParameter(?)`. 파라미터 타입을 지어내지 않는다.
- `var`, 체인 호출 결과, 선언 없는 이름 → `UNRESOLVED`.
- 솔버가 반환한 AST 는 다른 파서 인스턴스 소유이므로 `ProjectSources.mapToOwnAst` 로 같은 위치의 자체 AST 로 매핑해서 쓴다.
- `include-external-library-calls: false` 면 origin=LIBRARY 호출은 모델에 넣지 않고 개수만 센다. 단 Request Parameter 추출은 필터 이전에 수행.

**ID**
- 사람이 읽기 쉬운 순차 ID(C1, M1, CALL1, T1, CATCH1 …)를 쓰고, 정체성은 `key`(`FQN#name(erasure 타입들)`)로 판정해 중복 생성하지 않는다. 같은 입력이면 ID 순서가 결정적이다.
- Mermaid 노드 ID = YAML ID, subgraph ID = `sg_` + ID. 메서드 밖 throw 종점은 `M*_uncaught`.
- `CallSiteInfo`(위치별 1건, `calls:`) 와 `CallRelation`(메서드→메서드 집계, `relations:`)을 분리.

**제어 블록**
- 주 블록(if/switch/try)이 대체 분기(else-if/else/case/catch/finally)를 `relation: BRANCH` 자식으로 갖는다(SPEC 예시대로 catch 의 parent = try). BRANCH 는 주 블록과 같은 `nesting-depth`.
- 호출 순서는 Java 평가 순서: 수신 객체 → 인자 → 호출. for 는 init → 조건 → 본문 → update. `seq` 는 호출·블록 공통 순번.
- `conditional`/`conditions` 는 순회 컨텍스트에서 계산(블록 미생성 옵션이어도 유지). 삼항·`&&`/`||` 우변도 조건부. 람다 본문은 `deferred`.
- throw 매칭: 단순명 일치 / `Throwable` / 솔버 `isAssignableBy` → YES·NO, 판정 불가 → `UNRESOLVED`. catch·finally 안의 throw 는 같은 try 의 catch 에 잡히지 않는다. 못 잡히면 `propagates-to-caller: true`.
- `executes-finally`: return/throw 가 빠져나가며 지나는 finally(잡힌 경우 잡은 try 의 finally 는 미포함). finally 안 return/throw → `alters-completion: true`.
- try-with-resources: 리소스별 선언/close 순서(역순) 메타데이터. `close()` 는 소스에 없으므로 call 로 만들지 않는다.

**Mermaid**
- `-->` 정상 흐름, `==>` 메서드 호출, `-.->` 예외 흐름(try→catch, throw→catch/uncaught). 역할별 → 클래스별 → 메서드별 → 블록별 subgraph.
- 조건식 등 헤더 위치 호출은 블록 헤더 노드 앞에 연결. 블록 다음 요소는 subgraph ID 에서 연결.
- 라벨 이스케이프는 `#` 먼저 → `#35;` `#quot;` `#lt;` `#gt;` `#124;`. 렌더 전 `MermaidGraph.validate()` + `FlowchartGenerator.checkMapping()` 실패 시 예외.

## 작업 규칙

- 단계마다 `./gradlew test` 통과 후 다음 단계로. 새 기능은 `sample-project` 에 fixture 를 추가하고 테스트로 고정한다.
- 출력 필드를 바꾸면 YamlWriter · CallTreeWriter · FlowchartGenerator · AnalysisReportWriter · ModelValidator 를 함께 맞춘다(같은 모델, 같은 ID).
- 표시 문자열 규칙은 `serializer/Labels` 한 곳에서 관리(트리와 Mermaid 공용).
- 분석 결과에는 항상 근거 위치(`location`: 파일·라인·컬럼)를 남긴다.

## 구현 현황

완료(테스트 통과): SPEC 1–6단계 MVP — 대상/진입 메서드 식별(오버로딩·모호성 거부·매핑 어노테이션 추론), 접근 제어자 4종+인터페이스 암묵 제어자, 역할 분류(어노테이션>상위타입>Suffix, 근거·충돌 기록), Depth/동일 클래스 확장/중복/재귀·상호재귀, if·switch·loop·try/catch/finally·try-with-resources·return·throw, Legacy·사용자 규칙·Spring 파라미터, YAML 4종·트리·Mermaid flowchart·analysis.md, 모델/Mermaid 무결성 검증.
생성된 flowchart 는 Mermaid 11 파서로 문법 확인함(임시 검증, 프로젝트에는 mmdc 연동만 존재).

미구현(후속): `class-diagram.mmd`(Class Diagram), 고급 예외 전파(호출 대상 throws 기반), Spring 동적 호출/DI 구현체 해석, 생성자 호출(`new`)·메서드 참조(`::`)·익명/로컬 클래스 본문, switch fall-through 흐름, `case ... when` 가드.
