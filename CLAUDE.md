# java-flow-analyzer — Claude 작업 가이드

지정한 Action/Controller **하나**의 진입 메서드에서 시작해 Java 소스를 정적 분석하고,
**하나의 공통 모델**로 실행 흐름 + **데이터 바인딩 명세**(어떤 값이 어느 객체 필드로 가는지)를 만드는 마이그레이션 분석 도구.
출력: `analysis.yaml`(Source of Truth) · `flowchart.mmd` · `mapping.md` · `analysis.md` · `call-tree.txt` · `phase-flowchart.mmd` · `phase-summary.md`.
요구사항: [docs/SPEC-v2.md](docs/SPEC-v2.md)(현행, 바인딩·YAML·비교) ⊃ [docs/SPEC.md](docs/SPEC.md)(v1 흐름 분석). 애매하면 v2 → v1 순으로 기준.

## 절대 규칙 (SPEC §1–2)

- **추측 금지.** 소스에 없는 메서드·예외 경로·호출 관계를 만들지 않는다. 확정 못 하면 `UNRESOLVED` / `PARTIALLY_RESOLVED` / `AMBIGUOUS` 로 기록하고 사유(`resolution-reason`)를 남긴다.
- 인터페이스/추상 호출은 **선언 대상**을 `to` 로 기록하고, 런타임 구현체(Spring DI 등)를 확정하지 않는다. 예외: `link-single-implementation: true` 이면 source-root 안 구체 구현 클래스가 **정확히 1개**일 때만 `implementation-method-id`(구현 후보)로 연결한다(0개·2개 이상·익명 구현·계층 판정 불가 → 미연결, 사유 기록).
- 호출 대상 메서드가 던지는 예외 타입은 알 수 없으므로 **특정 호출을 catch 로 연결하지 않는다.** (`throw` 문만 catch 매칭)
- 바인딩 값 출처를 확정 못 하면 `unresolved:` 로 남긴다. 필드가 할당되지 않았다는 사실만으로 누락이라 판정하지 않는다.
- 원본 프로젝트는 **읽기 전용**: 수정·실행·빌드 금지, DB/외부 API 연결 금지. output 이 source-root 내부면 거부한다(`Main.outputDir`).
- Git 사용 금지. 분석기 의존성은 원본 프로젝트와 분리(`ReflectionTypeSolver(jreOnly=true)`).
- 참조용으로 읽은 파일은 결과에 넣지 않는다. 결과에는 진입 메서드에서 **도달한** 클래스/메서드만.
- Mermaid/트리/보고서는 **모델에서만** 생성한다. 출력 단계에서 소스를 다시 파싱하지 않는다.
- 구현하지 않은 기능을 완료했다고 쓰지 않는다. 불필요한 기능을 임의로 추가하지 않는다.

## 명령

```bash
./gradlew test                                   # 전체 테스트 (JUnit 5)
./gradlew run                                    # 내장 기본 조건 + config/analyzer.yml(덮어쓰기) → output/<Class>/
./gradlew run --args="--config config/analyzer.yml --class CaseAction --method throwCases"
#   옵션: --config --source-root(반복 가능) --class --method --output
```

- 설치된 JDK 는 26 뿐이라 `options.release = 21` 로 Java 21 타깃 컴파일한다(toolchain 미사용).
- 샘플 원본 프로젝트(분석 대상, 컴파일하지 않음): `src/test/resources/sample-project/src/main/java`
  - `ScpAppListAction` — 실전형 시나리오, `CaseAction` — 테스트 케이스별 진입 메서드, `AppController` — Spring 어노테이션
- 예제 프로젝트: 이 저장소 **옆 디렉터리** `../java-flow-example` (git 밖, 별도 프로젝트처럼) — 멀티 모듈 `action/`(Struts Action) + `lib/`(VO/DTO + Service/Biz/DAO/Mapper/Util/common), 각각 `src/main/java`. 설정 `config/java-flow-example.yml`(`source-roots` 2개, 상대 경로 `../java-flow-example/...`)
  - `./gradlew run --args="--config config/java-flow-example.yml"` → `output/OrderRegisterAction/` (요청 파라미터 25개 → VO/DTO/Command 6종 + builder·생성자)
  - `--class OrderListAction` → `output/OrderListAction/`
  - 이 예제를 쓰는 테스트(PhaseTest·VariableStateTest 일부)는 `../java-flow-example` 이 없으면 Assumptions 로 건너뛴다.
- Mermaid CLI(`mmdc`)가 PATH 에 있으면 렌더링 검증, 없으면 생략하고 analysis.md 에 기록.

## 구조

```
analyzer/
  Main.java              CLI, 모델 무결성 검증 후 출력
  FlowAnalyzer.java      오케스트레이터: BFS 로 메서드 본문 분석, Depth/Expansion/cycle 결정
  config/                analyzer.yml 바인딩(kebab-case, 알 수 없는 키는 오류). 내장 기본 조건 src/main/resources/analyzer-defaults.yml < --config 파일(맵 키 단위 병합, 목록·값 교체) < CLI
  parser/                ProjectSources(파일 인덱스+지연 파싱+심볼솔버), EntryPointLocator, TypeNames
  classifier/            ClassRoleClassifier
  resolver/              CallResolver(심볼솔버 → 선언타입 폴백), TypeLookup(import/변수/필드 타입), ImplementationFinder(source 내 유일 구현)
  controlflow/           MethodBodyWalker(블록+호출 순서), WalkContext, ExceptionTypeMatcher
  parameter/             RequestParameterExtractor(Legacy + 사용자 규칙 + Spring 어노테이션)
  binding/               BindingCollector(객체 O* · 바인딩 B*), VariableStateCollector(변수 V* · 변경 VC*), SourceTracer(값 → 최초 출처+변환), ConditionText, RequestSpecBuilder(RQ*)
  model/                 공통 모델 + ModelValidator
  serializer/            AnalysisYamlWriter + AnalysisSchema(내장 스키마 검증), MappingReportWriter(mapping.md),
                         AnalysisReportWriter(analysis.md), CallTreeWriter, FlowView(흐름 보기 규칙), Labels(표시 문자열), OutputWriter
  mermaid/               MermaidGraph(중간표현+검증), FlowchartGenerator(일반 / 단계 보기 모드), MermaidCli
  phase/                 PhaseAnalyzer(진입 메서드 요소 → 바인딩·체크·로직·후처리), PhaseModel, PhaseText(표시), PhaseReportWriter(phase-summary.md)
```

## 핵심 설계 결정 (코드만 봐서는 이유가 안 보이는 것)

**Depth / 전개**
- 본문 분석 조건: `대상 Depth < max-depth` **또는** (`expand-same-class-methods` && 호출자와 같은 클래스) **또는** 대상 클래스 역할 ∈ `expand-roles`. 진입 메서드 D0 는 항상 분석.
  - `expand-roles`(예: Service·Biz·DAO·Repository·Mapper): Action→Service→Mapper 같은 데이터 접근 흐름은 끝까지, Util 등은 max-depth 에서 depth-limit. 역할은 분류 결과 기준(`@Service` 가 붙은 `*BizImpl` 은 Service).
  - 구현 후보 연결 시 `target-method-id` 는 선언 대상 그대로, `expansion` 은 구현 메서드 기준(`CallSiteInfo.expansionTargetId()`). relations 는 선언 대상 기준 집계. Mermaid 는 `declared` / `impl 후보` 호출 엣지 2개.
- BFS 이므로 메서드 Depth = 최단 경로 Depth. 동일 클래스 확장으로 max-depth 를 넘어도 **실제 Depth 를 그대로** 기록(D2, D3…).
- 메서드 본문은 한 번만 분석(MethodInfo 1개). 호출 위치별 `expansion`:
  `EXPANDED`(여기서 전개) · `SHARED`(`expanded-at` 참조) · `CYCLE`(전개 트리상 조상/자기 자신) · `DEPTH_LIMIT` · `NO_BODY`(abstract/interface) · `EXTERNAL` · `NOT_APPLICABLE`(미해결).
  **해석 상태(`resolution`)와 전개 상태(`expansion`)는 별개 축** — depth-limit 을 unresolved 와 섞지 않는다.

**해석**
- 멀티 모듈: `source-roots`(목록, `source-root` 와 합쳐짐) / CLI `--source-root` 반복. 루트마다 `JavaParserTypeSolver`, FQN 조회는 모든 루트. 루트끼리 겹치면 설정 오류, output 이 **어느** 루트 안이어도 거부.
  같은 FQN 이 2개 이상 루트에 있으면 솔버가 하나를 골라도 `AMBIGUOUS`(`+DUPLICATE_FQN`, 후보=파일들) + notes. `location.file` 은 루트 1개면 그 루트 기준, 여러 개면 공통 상위 기준(`lib/src/main/java/...`). YAML `analysis.sourceRoot`(기준) + `sourceRoots`.
- 1순위 JavaSymbolSolver. 실패 시 선언부에 명시된 타입(파라미터/지역변수/필드/import/같은 패키지/java.lang)으로 **이름+인자 수** 매칭. 후보 2개 이상이면 `AMBIGUOUS`. 근거는 `resolution-basis` 에 남긴다.
- source-root 밖 타입(예: `HttpServletRequest`)은 시그니처를 모르므로 `PARTIALLY_RESOLVED` + `signature-resolved: false`, 시그니처는 `getParameter(?)`. 파라미터 타입을 지어내지 않는다.
- `var`, 체인 호출 결과, 선언 없는 이름 → `UNRESOLVED`.
- 솔버가 반환한 AST 는 다른 파서 인스턴스 소유이므로 `ProjectSources.mapToOwnAst` 로 같은 위치의 자체 AST 로 매핑해서 쓴다.
- `include-external-library-calls: false` 면 origin=LIBRARY 호출은 모델에 넣지 않고 개수만 센다. 단 Request Parameter 추출은 필터 이전에 수행.

**요청 읽기 규칙 (`request-parameter-rules`, `RequestParameterExtractor`)**
- 바인딩으로 볼 프로젝트 고유 메서드는 YAML 로 관리. `class` 생략 = 메서드명만으로 매칭(상속 메서드 등). `name-arg-index` = 이름 인자 위치, `parameter` = 고정 이름, 둘 다 없으면 메서드명에서(`getSystemId` → `systemId`, nameResolution `CONFIG`). 둘 다 쓰면 설정 오류. fixture `com.example.action.vas.GetVasAppList`, `RequestRuleTest`.
- 표시 문자열은 줄이지 않는다(`Labels.truncate`·`ConditionText.text` 는 원문 그대로, 목록 상한 없음) — 사용자 요구.

**Request 스펙**
- Request 읽기 호출(getParameter*·`request-parameter-rules`)은 `include-external-library-calls: false` 여도 모델에 남긴다(요청 스펙 근거). analysis.md 의 Unresolved 표에서는 건수만 표시.
- `requestSpec`(RQ*) = 이름이 확정된 P* 의 이름별 집계. UNKNOWN 이름·전체 조회·body·`@ModelAttribute`(객체 바인딩)는 `unknownNameReads` / `bulkReads` 로 (`RequestParameterInfo.named()`).

**바인딩 (SPEC-v2 §3.3·§4)**
- 대상 객체: 역할 `VO`(Suffix VO/Vo/DTO/Dto/Form/Command) source 클래스 + Map 계열 변수. 수집: setter · builder(`X.builder()…build()`) · 생성자(소스 생성자 본문의 `this.f = 파라미터`로 필드 매핑) · 직접 필드 대입 · `Map.put`(리터럴/상수 키) · `BeanUtils/PropertyUtils.copyProperties`(import 로 인자 순서 판별, 필드 `*`).
- 분석 종료 후 **본문을 분석한 메서드의 AST** 를 다시 훑어 수집(라이브러리 호출 필터와 무관). 같은 필드 여러 할당 = 별도 Binding, 순서·조건 보존.
- 조건(`ConditionText`): if then → 조건식, else → `!(조건식)`, switch → `selector == label`, 반복문 → `loop: …`, catch, 람다 `deferred`, `&&` 우변. 없으면 `always`.
- 출처(`SourceTracer`): Request 읽기 → `request.x` / 리터럴 / 상수 / 지역변수 → 초기화식·대입식 전부(2개 이상이면 `reassigned`) / 메서드 파라미터 → 분석된 호출 위치 인자(`via`), 없으면 `param:` / 반복 변수 `loop:` / getter·서비스 호출은 반환값 내부를 추적하지 않고 `call:` 로 끝냄.
  변환: JDK 변환(Integer.parseInt…)·`*Util(s)` static·문자열 메서드(.trim()…)는 항상 변환+원본 추적, 그 밖의 source 메서드는 **인자가 요청 값일 때만** 변환(예: `normalizePhone(request…)`), 아니면 `call:`. 연산식은 `expr: …`, 삼항은 `조건식: …`(분기 리터럴은 출처).
  Default 는 출처가 요청 파라미터 하나일 때만 그 기본값.
- 객체 정체성: 지역변수 = (메서드, 변수). 메서드 파라미터는 분석된 호출 위치가 **정확히 1개**이고 인자가 변수일 때만 호출자 객체로 잇는다. 반환값으로 넘어간 객체는 잇지 않는다.
- 필드 커버리지: 선언(소스 인스턴스 필드, 상위 포함) / 할당 / 조건부로만 할당 / 초기값만 / 미할당 / 확인 불가(copyProperties 대상).
- Lombok 등 생성 코드는 builder/생성자 resolution 을 `PARTIALLY_RESOLVED`/`UNRESOLVED` 로 낮추고 사유 기록.
- JavaParser `Node.equals` 는 구조 비교라 AST 키 맵은 `IdentityHashMap`.

**변수 상태 변화 (`binding/VariableStateCollector`, V* / VC*)**
- 대상: 분석한 메서드의 지역 변수·파라미터 중 선언 뒤 `=`·복합 대입·`++/--` 가 있는 것. 제외: 필드(선언 없는 이름), for 헤더(init/update) 변수, 향상된 for 변수. 바인딩 수집 뒤 같은 AST 재순회.
- 이력 첫 항목은 INIT(선언, 값 없을 수 있음) / PARAM, 이후 ASSIGN / COMPOUND / INCREMENT / DECREMENT. 조건(`ConditionText`)·소속 블록·식 안 호출(`callSiteIds`)·출처(값 식의 피연산자를 `SourceTracer` 로, 변수 자기 자신 제외)를 남긴다.
- 표시(이력 전체를 흐름 위에서 추적): flowchart `값 변경` 노드(`VAR_<VC>`, 코드 그대로, 식 안 호출이 끝난 뒤 = 변경 끝 위치 이후 첫 요소 앞; 나머지 VC 는 alias). 선언(INIT, 값 있음)도 그린다 — 값 식 == 호출식이면(선언·대입 모두) 호출 노드에 `→ x · V1 1/3` + alias, 값 식 == 요청 읽기 호출이면 요청 파라미터 줄 `· V1 1/n`. 트리 `≔` 줄. mapping.md `## 변수 상태 변화`. VO 값 설정 줄의 값이 이런 변수면 `⟳n (V*)`. `Labels.changeLine` 의 `V1 3/3` = 이력 중 순번(1 = 선언).
- 단계 보기: 요청 값에서 온 변수(초기값 출처 REQUEST)의 변경 = 바인딩(요청 값 보정). 그 밖의 최상위 변경은 근거 없음 → 이웃 단계. 변경 ID 도 `suppressedCalls` 로 요약 대상 관리.

**흐름 보기 (`FlowView`, 트리·Mermaid 공용)**
- 흐름 중심은 호출·분기·예외. 바인딩 코드(setter/builder/put 호출과 그 인자 안의 전개되지 않은 호출)는 흐름에서 빼고, **(객체, 메서드)당 요약 1개**(`BIND_<O>_<M>`: `N fields assigned · N conditional · N type conversion`)를 둔다(call-tree). flowchart 는 이 요약 대신 흐름 위치마다 `VO 값 설정` 노드(아래 Mermaid 참고).
- 요약 위치 = 그 메서드에서 해당 객체 바인딩을 모두 감싸는 가장 가까운 공통 컨테이너(then/else 에 흩어지면 그 if 앞).
- 바인딩 메서드(본문이 바인딩 + 최상위 return 뿐, 예 `toSearchVO`)는 그리지 않고 호출 위치에 요약(`BIND_<O>_<CALL>`).

**단계 보기 (`phase/`, 설정 `phases`)**
- 사용자 앱 공통 구조 = 바인딩(getParameter·Util·VO 채우기·값 조회) › 체크 › 로직(Service·DAO…) 실행. 문장 단위 흐름도에서 요청 읽기 노드가 로직을 묻는 문제를 해결하려고 만든 **파생 보기**(모델에서만 계산, analysis.yaml 에는 넣지 않음).
- step = 진입 메서드 최상위 요소(try 본문 펼침). 한 문장의 중첩 호출(인자 속 호출·builder 체인)은 가장 바깥 호출 1개 step(`group`). 호출 없는 최상위 바인딩(생성자)도 step.
- 판정 우선순위: logic-roles 호출 → 로직, check-roles 호출 → 체크 > 요청 읽기·바인딩 코드(FlowView.hidden) → 바인딩 > 전개한 메서드 본문 집계(로직 > 바인딩 > 체크) > 종료형 분기(throw, 진입 메서드는 return 포함; 조건식 안 호출은 체크 일부) > check-method-names. catch/finally 안은 판정에서 제외. Validator 류 메서드의 `catch → throw` 도 체크.
- 최상위 보정: 근거 없는 호출에 진입 메서드의 요청 객체(…ServletRequest 등 파라미터)를 넘기면 = 요청 컨텍스트 값 조회(바인딩, `fetchVariable`) → 로직·바인딩 없는 source 메서드 = 로직(계산) → 결과 변수가 사이에 로직 없는 뒤 바인딩의 출처이고 그 뒤에 로직이 더 있으면 값 조회(바인딩) → 근거 없는 요소는 앞(없으면 뒤) 요소 단계. 근거는 `reason` 으로 남긴다.
- 값 조회 step(`fetchVariable`)은 바인딩으로 끝: ① 노드에 `조회: x = call(…)` 한 줄, 그 안의 체크는 `preChecks()`·흐름도에 넣지 않는다(사용자 모델: 로그인 사용자 조회 = 바인딩, checkLogin 이어도).
- 영역: PRE(첫 로직 전) / LOGIC(첫~마지막 로직; 순수 입력(요청·리터럴·상수) 없는 바인딩은 "로직 결과 값 설정"으로 로직) / POST(마지막 로직 뒤 = 후처리).
- 레이어(단계 보기만): 메서드 subgraph 를 `sg_LAYER_ACTION`(진입 클래스 역할) / `sg_LAYER_SERVICE`(Service·Biz) / `sg_LAYER_DAO`(DAO·Mapper·Repository) 안에 둔다. 로직 역할이 아니고 전이적으로 로직 역할 호출에 닿지 않는 보조 메서드(`collapsedHelpers`)는 subgraph 없이 호출 노드에 `세부 생략` — `skippedMethods(r, suppressed, seed)`·`checkMapping(…, collapsed)` 에 반영, 그리지 않는 메서드로의 `==>` 는 만들지 않음.
- 흐름도: PRE 는 ① 바인딩 요약 노드 1개 + ② 체크 노드(조건 → 결과, PRE 의 모든 체크: 진입 분기·바인딩 중·Validator 본문), LOGIC 은 기존 emit 로 상세(사이 바인딩·체크는 `PH_INL*` 요약), POST 는 `PH_POST`. 요약된 요소에서만 호출되는 메서드는 subgraph 생략(`skippedMethods(r, suppressedCalls)`), `checkMapping` 도 suppressed 제외.

**ID**
- 사람이 읽기 쉬운 순차 ID(C1, M1, CALL1, T1, CATCH1, P1, RQ1, O1, B1 …)를 쓰고, 정체성은 `key`(`FQN#name(erasure 타입들)`)로 판정해 중복 생성하지 않는다. 같은 입력이면 ID 순서가 결정적이다.
- Mermaid 노드 ID = YAML ID, subgraph ID = `sg_` + ID. 메서드 밖 throw 종점은 `M*_uncaught`.
- `CallSiteInfo`(위치별 1건, `calls:`) 와 `CallRelation`(메서드→메서드 집계, `relations:`)을 분리.

**제어 블록**
- 주 블록(if/switch/try)이 대체 분기(else-if/else/case/catch/finally)를 `relation: BRANCH` 자식으로 갖는다(SPEC 예시대로 catch 의 parent = try). BRANCH 는 주 블록과 같은 `nesting-depth`.
- 호출 순서는 Java 평가 순서: 수신 객체 → 인자 → 호출. for 는 init → 조건 → 본문 → update. `seq` 는 호출·블록 공통 순번.
- `conditional`/`conditions` 는 순회 컨텍스트에서 계산(블록 미생성 옵션이어도 유지). 삼항·`&&`/`||` 우변도 조건부. 람다 본문은 `deferred`.
- throw 매칭: 단순명 일치 / `Throwable` / 솔버 `isAssignableBy` → YES·NO, 판정 불가 → `UNRESOLVED`. catch·finally 안의 throw 는 같은 try 의 catch 에 잡히지 않는다. 못 잡히면 `propagates-to-caller: true`.
- `executes-finally`: return/throw 가 빠져나가며 지나는 finally(잡힌 경우 잡은 try 의 finally 는 미포함). finally 안 return/throw → `alters-completion: true`.
- try-with-resources: 리소스별 선언/close 순서(역순) 메타데이터. `close()` 는 소스에 없으므로 call 로 만들지 않는다.

**출력 / analysis.yaml**
- `analysis.yaml` 단일 문서, camelCase, `schemaVersion: "1.0"`. 쓰고 다시 읽어 `AnalysisSchema` 로 필수 키·타입·열거값·ID 형식·참조 검증 → 실패 시 예외. 숫자처럼 보이는 문자열은 따옴표 강제(`ALWAYS_QUOTE_NUMBERS_AS_STRINGS`).
- 바인딩은 표시용 `source`/`transform`/`condition` 문자열과 구조화된 `sources`/`transforms`/`conditions` 를 함께 기록.
- `comparison.md`(SPEC-v2 §7)는 신규 프로젝트 데이터가 없으면 만들지 않는다(미구현).

**Mermaid (SPEC-v2 §5)**
- 기본 `TD`(위→아래). subgraph 는 분석한 메서드당 1개(중첩 없음). 본문 미분석 메서드는 노드를 만들지 않고 호출 노드 라벨로만 표시.
- if/else-if/switch/반복문 = 조건 노드(◇) + 분기 간선(`Yes`/`No`, case 라벨, `loop`/`next`/`done`). else/case 는 노드 없이 간선으로.
- try = **블록(subgraph `sg_T*`) 1개** — 본문은 그 안에 정상 흐름, 예외는 블록 단위로 `sg_T1 -.->|예외 타입| CATCH*`(catch 노드 = 처리 방식 요약: 로그·오류 응답·다시 던짐·새 예외·catch 본문 최상위 return). 다시/새로 던지면 catch → `M*_uncaught`, return 이면 끝, 그 밖이면 try 뒤로 합류. finally 는 try·catch 뒤 노드 1개(`FINALLY*`, 바깥 호출식 최대 3개). 개별 throw 에 `→ catch` 표시 없음, 메서드 시작 노드 `⚠ catch` 요약은 단계 보기 진입 메서드(try 를 단계별로 펼침)에만. catch/finally 본문 노드와 그 안에서만 호출되는 메서드는 여전히 생략(`skippedMethods`). 메서드 subgraph 안에 중첩되는 subgraph 는 try 블록뿐.
- 흐름 연결은 "열린 끝" 목록으로 계산(분기 끝들이 다음 요소로 합류). return/throw 뒤는 끊김.
- 바인딩·요청 읽기·값 변경은 **코드 형식 `대상 = 값`** (`Labels.requestLine` / `bindingLine` / `changeLine`, 값 식 안 요청 읽기 호출은 `Labels.codeValue` 로 `request.이름 ?: 기본값`).
- 연속된 요청 값 읽기 호출(본문 미전개, 조건식 밖)은 `요청 파라미터` + 한 줄에 하나 `#n 변수 = request.이름 ?: 기본값` 요약 노드 1개(노드 ID = 첫 호출 ID, 나머지는 `MermaidGraph.alias` → `has()`/`nodeOf()`).
- 연속된 바인딩 코드(setter·builder·put·생성자·바인딩 메서드 호출)는 `VO 값 설정 · 클래스들` 노드 1개(`SET_<첫 요소 ID>`, `#n obj.field = 값`, Map 은 `m["key"] = 값`, 바뀌는 변수면 `⟳V*`, 바인딩 메서드 안 바인딩은 번호 없이 `메서드() 안:` 아래, 최대 10줄). 바인딩 호출 자체는 노드/alias 가 아니다. 단계 보기의 바인딩·후처리 노드는 `요청 파라미터` / `VO:` / `조회:` 형식.
- **세부(subgraph)는 Service 쪽으로 넘어가는 흐름만** (사용자 원칙: Action → Service, Service → 다른 Service·Biz·DAO·Mapper). 내부 함수 = 로직 역할이 아니고 전이적으로 로직 역할 호출에 닿지 않는 메서드(`collapsedHelpers`; readItems·readDelivery·preparePayment·calculateTotal 등)는 **일반 흐름도·단계 보기 모두** subgraph 없이 코드 한 줄 `#15 items = readItems(request)` + `VO 값 설정 · 클래스`(안에서 채우는 VO) + 값이 바뀌는 변수면 `· V1 1/3`. 본문 분석(요청 파라미터·바인딩·체크·mapping.md)은 그대로 한다. Service 클래스 안의 private 메서드(nextOrderNo)는 Service 세부의 일부로 그린다. 값 조회 — 결과를 변수에 받는데 본문을 전개하지 않은 source 메서드(로직 역할·VO·Util 제외, 예 상위 클래스 `checkLogin`) — 는 바인딩 한 줄 `#1 member = checkLogin(request)` (`FlowchartGenerator.fetch`, css bind). 상위 클래스 메서드를 일부러 전개하지 않는다(과거에 시도했다가 checkLogin 내부 if 가 로직처럼 보여 되돌림). 단계 보기의 접힌 보조 메서드는 `collapsedHelpers`.
- return/throw 는 메서드 박스 안 흐름 끝(실선, 분기 라벨 Yes/No), 처리되지 않은 throw 만 `-.->` `M*_uncaught`. (박스 밖 '이탈' 배치를 시도했으나 Mermaid 는 노드 좌표·박스 크기를 지정할 수 없어 dagre 는 끝으로 밀어내고 ELK 도 원하는 '오른쪽 배치'가 안 돼 되돌림.) 레이아웃 `output.layout` 기본 dagre, 옵션 elk(`MermaidGraph.init(layout)`).
- 렌더링 첫 줄은 `MermaidGraph.INIT`(`%%{init: …}%%` 노드 padding·wrappingWidth) — 여러 줄 라벨이 잘리지 않게.
- `-->` 정상 흐름, `==>` 본문을 전개한 호출(EXPANDED/SHARED/CYCLE → 메서드 시작 노드), `-.->` 처리되지 않은 throw(`M*_uncaught`).
- 라벨 이스케이프는 `#` 먼저 → `#35;` `#quot;` `#lt;` `#gt;` `#124;`. 렌더 전 `MermaidGraph.validate()` + `FlowchartGenerator.checkMapping()` 실패 시 예외.

## 작업 규칙

- 단계마다 `./gradlew test` 통과 후 다음 단계로. 새 기능은 `sample-project` 에 fixture 를 추가하고 테스트로 고정한다.
- 출력 필드를 바꾸면 AnalysisYamlWriter · AnalysisSchema · MappingReportWriter · AnalysisReportWriter · CallTreeWriter · FlowchartGenerator · ModelValidator 를 함께 맞춘다(같은 모델, 같은 ID).
- 표시 문자열 규칙은 `serializer/Labels` 한 곳에서 관리(트리와 Mermaid 공용).
- 분석 결과에는 항상 근거 위치(`location`: 파일·라인·컬럼)를 남긴다.

## 구현 현황

완료(테스트 통과):
- 멀티 모듈 소스 루트(`source-roots`, fixture `src/test/resources/multi-module`).
- v1 흐름 분석 — 대상/진입 메서드 식별(오버로딩·모호성 거부·매핑 어노테이션 추론), 접근 제어자 4종+인터페이스 암묵 제어자, 역할 분류(어노테이션>상위타입>Suffix, 근거·충돌 기록), Depth/동일 클래스 확장/중복/재귀·상호재귀, if·switch·loop·try/catch/finally·try-with-resources·return·throw, Legacy·사용자 규칙·Spring 파라미터, 역할 기반 전개(`expand-roles`)·유일 구현 후보 연결(`link-single-implementation`).
- v2 바인딩 — setter·builder·생성자·필드 대입·Map.put·copyProperties 수집, 출처/변환/조건/기본값/재대입 추적, 필드 커버리지, 다중 할당 최종 값(mapping.md), catch 처리 방식(로그·오류 응답·재발생·반환), 호출 인자·반환값 사용.
- 변수 상태 변화 — V*/VC* 수집(analysis.yaml `variables` + 스키마·ModelValidator), flowchart `값 변경` 노드·`→ x`, 트리 `≔`, mapping.md 이력 표, VO 줄 `⟳`, 단계 보기 요청 값 보정(fixture `CaseAction.variableState`, `VariableStateTest`).
- 단계 보기 — `phase-flowchart.mmd`·`phase-summary.md`, 역할 `Validator`(Suffix), 설정 `phases`(fixture: ../java-flow-example, sample `ScpAppListAction`; `PhaseTest`). 생성 결과 Mermaid 11.4.1 파서 통과.
- 출력 — analysis.yaml(+스키마 검증)·flowchart.mmd(TD, 메서드별 subgraph, 요청 파라미터·VO 값 설정 요약 노드)·mapping.md·analysis.md(SPEC-v2 §8 구성)·call-tree.txt. 생성 flowchart 는 Mermaid 11.4.1 파서로 문법 확인(임시 검증, 프로젝트에는 mmdc 연동만 존재).

미구현(후속): **마이그레이션 비교(SPEC-v2 §7, comparison.md)**, `class-diagram.mmd`, 고급 예외 전파(호출 대상 throws 기반), Spring 동적 호출/DI 구현체 해석, 반환값을 거친 객체 정체성·값 추적, 메서드 참조(`::`)·익명/로컬 클래스 본문, switch fall-through 흐름, `case ... when` 가드.
