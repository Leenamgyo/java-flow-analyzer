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
- `include-external-library-calls: false` 면 origin=LIBRARY 호출은 모델에 넣지 않고 개수만 센다. 단 Request Parameter 추출은 필터 이전에 수행. 예외: **문장의 가장 바깥 호출**(`extraInfo.add(X)`, `int n = Integer.parseInt(…)`, 삼항 `a.isEmpty() ? … : a.get(0)` — 다른 호출의 인자·수신 객체가 아니고 조건식·return·for 헤더·식 람다 본문 밖, `FlowAnalyzer.outermostInStatement`)은 빼면 그 문장이 흐름도에서 사라지므로 남긴다(사용자 요구: 하나도 빼먹지 말 것, `if` 본문이 JDK 호출뿐이라 비었던 문제).

**요청 읽기 규칙 (`request-parameter-rules`, `RequestParameterExtractor`)**
- 바인딩으로 볼 프로젝트 고유 메서드는 YAML 로 관리. `class` 생략 = 메서드명만으로 매칭(상속 메서드 등). `name-arg-index` = 이름 인자 위치, `parameter` = 고정 이름, 둘 다 없으면 메서드명에서(`getSystemId` → `systemId`, nameResolution `CONFIG`). 둘 다 쓰면 설정 오류. `wraps: n` = 기본값 래퍼(인자 n 이 요청 읽기면 그 P* 를 래퍼 호출로 옮기고 default·받는 변수·extraction `A → B` 갱신, 안쪽 읽기는 인자 안 호출이 되어 흐름도에서 빠짐; commons StringUtils.defaultIfEmpty/Blank/defaultString 내장). fixture `com.example.action.vas.GetVasAppList`, `RequestRuleTest`.
- 표시 문자열은 줄이지 않는다(`Labels.truncate`·`ConditionText.text` 는 원문 그대로, 목록 상한 없음) — 사용자 요구.

**Request 스펙**
- Request 읽기 호출(getParameter*·`request-parameter-rules`)은 `include-external-library-calls: false` 여도 모델에 남긴다(요청 스펙 근거).
- `requestSpec`(RQ*) = 이름이 확정된 P* 의 이름별 집계. UNKNOWN 이름·전체 조회·body·`@ModelAttribute`(객체 바인딩)는 `unknownNameReads` / `bulkReads` 로 (`RequestParameterInfo.named()`).

**바인딩 (SPEC-v2 §3.3·§4)**
- 대상 객체: 역할 `VO`(Suffix VO/Vo/DTO/Dto/Form/Command) source 클래스 + Map 계열 변수. 수집: setter · builder(`X.builder()…build()`) · 생성자(소스 생성자 본문의 `this.f = 파라미터`로 필드 매핑) · 직접 필드 대입 · `Map.put`(리터럴/상수 키) · `BeanUtils/PropertyUtils.copyProperties`(import 로 인자 순서 판별, 필드 `*`).
- 분석 종료 후 **본문을 분석한 메서드의 AST** 를 다시 훑어 수집(라이브러리 호출 필터와 무관). 같은 필드 여러 할당 = 별도 Binding, 순서·조건 보존.
- 조건(`ConditionText`): if then → 조건식, else → `!(조건식)`, switch → `selector == label`, 반복문 → `loop: …`, catch, 람다 `deferred`, `&&` 우변. 없으면 `always`.
- 출처(`SourceTracer`): Request 읽기 → `request.x` / 리터럴 / 상수 / 지역변수 → 초기화식·대입식 전부(2개 이상이면 `reassigned`) / 메서드 파라미터 → 분석된 호출 위치 인자(`via`), 없으면 `param:` / 반복 변수 `loop:` / getter·서비스 호출은 반환값 내부를 추적하지 않고 `call:` 로 끝냄.
  변환: JDK 변환(Integer.parseInt…)·`*Util(s)` static·문자열 메서드(.trim()…)는 항상 변환+원본 추적, 그 밖의 source 메서드는 **인자가 요청 값일 때만** 변환(예: `normalizePhone(request…)`), 아니면 `call:`. 연산식은 `expr: …`, 삼항은 `조건식: …`(분기 리터럴은 출처).
  Default 는 출처가 요청 파라미터 하나일 때만 그 기본값.
- 객체 정체성: 지역변수 = (메서드, 변수). 메서드 파라미터는 분석된 호출 위치가 **정확히 1개**이고 인자가 변수일 때만 호출자 객체로 잇는다. 반환값으로 넘어간 객체는 잇지 않는다.
- 필드 커버리지: 선언(소스 인스턴스 필드) / 할당 / 조건부로만 할당 / 초기값만 / 미할당 / 확인 불가(copyProperties 대상). 상위 클래스 필드는 `analysis.include-inherited-fields`(기본 false — 사용자 요구로 분석 내용에서 뺌) 일 때만 선언 목록에 넣는다. 상속 필드에 값을 넣는 바인딩(`vo.setRegId(…)`)은 설정과 무관하게 기록·할당 필드에 포함(fixture `EdgeAction.inheritedFields`, `NoticeVO extends BaseVO`).
- Lombok 등 생성 코드는 builder/생성자 resolution 을 `PARTIALLY_RESOLVED`/`UNRESOLVED` 로 낮추고 사유 기록.
- JavaParser `Node.equals` 는 구조 비교라 AST 키 맵은 `IdentityHashMap`.

**변수 상태 변화 (`binding/VariableStateCollector`, V* / VC*)**
- 대상: 분석한 메서드의 지역 변수·파라미터 중 선언 뒤 `=`·복합 대입·`++/--` 가 있는 것. 제외: 필드(선언 없는 이름), for 헤더(init/update) 변수, 향상된 for 변수. 바인딩 수집 뒤 같은 AST 재순회.
- 이력 첫 항목은 INIT(선언, 값 없을 수 있음) / PARAM, 이후 ASSIGN / COMPOUND / INCREMENT / DECREMENT. 조건(`ConditionText`)·소속 블록·식 안 호출(`callSiteIds`)·출처(값 식의 피연산자를 `SourceTracer` 로, 변수 자기 자신 제외)를 남긴다.
- 표시(이력 전체를 흐름 위에서 추적): flowchart `값 변경` 노드(`VAR_<VC>`, 코드 그대로, 식 안 호출이 끝난 뒤 = 변경 끝 위치 이후 첫 요소 앞; 나머지 VC 는 alias). 선언(INIT, 값 있음)도 그린다 — 값 식 == 호출식이면(선언·대입 모두) 호출 노드에 `→ x · V1 1/3` + alias, 값 식 == 요청 읽기 호출이면 요청 파라미터 줄 `· V1 1/n`. 트리 `≔` 줄. mapping.md `## 변수 상태 변화`. VO 값 설정 줄의 값이 이런 변수면 `⟳n (V*)`. `Labels.historyTag`: 선언·파라미터 = `V1`(값 변경 아님 — 사용자 지적: 자바에서 타입 있는 선언은 변경이 아님), 실제 변경 = `V1 k/n`(변경만 센 순번). 흐름도의 `값 변경` 노드는 실제 변경(타입 없는 대입·복합 대입·++/--)만, 호출 노드의 타입은 선언(`resultType`)일 때만.
- 단계 보기: 요청 값에서 온 변수(초기값 출처 REQUEST)의 변경 = 바인딩(요청 값 보정). 그 밖의 최상위 변경은 근거 없음 → 이웃 단계. 변경 ID 도 `suppressedCalls` 로 요약 대상 관리.

**흐름 보기 (`FlowView`, 트리·Mermaid 공용)**
- 흐름 중심은 호출·분기·예외. 바인딩 코드(setter/builder/put 호출과 그 인자 안의 전개되지 않은 호출)는 흐름에서 빼고, **(객체, 메서드)당 요약 1개**(`BIND_<O>_<M>`: `N fields assigned · N conditional · N type conversion`)를 둔다(call-tree). flowchart 는 이 요약 대신 흐름 위치마다 `VO 값 설정` 노드(아래 Mermaid 참고).
- 요약 위치 = 그 메서드에서 해당 객체 바인딩을 모두 감싸는 가장 가까운 공통 컨테이너(then/else 에 흩어지면 그 if 앞).
- 바인딩 메서드(본문이 바인딩 + 최상위 return 뿐, 예 `toSearchVO`)는 그리지 않고 호출 위치에 요약(`BIND_<O>_<CALL>`). 흐름도는 이를 넓힌 `FlowchartGenerator.bindingFunctions` 를 바인딩 블록 안에 중첩.

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
- analysis.md 에는 메서드 목록·Unresolved 목록·호출 트리를 넣지 않는다(사용자 요구: 분석서에 중요하지 않음). 각각 `analysis.yaml`(methods, calls 의 resolution)·`call-tree.txt` 에 있고, 분석서 머리말에 위치를 안내.
- 호출 트리 제외 `output.call-tree-exclude`(사용자 관리, 기본 없음): `flowchart-exclude` 와 같은 패턴·매처(`serializer.Excludes`, 흐름도와 공용). 맞는 호출은 줄도 그 아래 전개도 없고, 트리 끝에 `※ call-tree-exclude (… N개)` 를 남긴다. 표시에서만 빼며 모델·YAML·mapping.md 는 그대로.

**Mermaid (SPEC-v2 §5)**
- 기본 `TD`(위→아래). subgraph 는 분석한 메서드당 1개 + 그 안의 try 블록(`sg_T*`)·Loop 영역(`sg_FOR*`/`sg_FOREACH*`/`sg_WHILE*`/`sg_DO*`). 본문 미분석 메서드는 노드를 만들지 않고 호출 노드 라벨로만 표시.
- **빠진 코드 없음 (일반 흐름도)**: 분석한 메서드·호출·블록은 모두 노드 또는 블록 줄로 그린다(catch/finally 본문, 그 안에서만 호출되는 메서드, Service 로 안 가는 보조 메서드 포함 — 일반 흐름도에서는 `collapsedHelpers` 로 접지 않는다). 빼는 것은 사용자 설정 `output.flowchart-exclude`(* 패턴: 대상 `클래스.메서드` 단순명·FQN, 원문 `수신객체.메서드`)뿐 — 맞는 호출은 노드 없음, 맞는 메서드는 상자 없음, 흐름도 머리에 `%% flowchart-exclude …` 주석. 모델 밖 코드(`include-external-library-calls: false` 로 빠진 JDK 호출 — 바깥 호출·조건식·return 코드에 보이는 것뿐, 호출 없는 문장, `continue`/`break`)는 흐름도도 모른다. 규칙 객체 `FlowchartGenerator.Rules`(normal / phase — 단계 보기는 catch/finally 본문 생략 유지).
- **흐름도 블록 5종** (연속된 같은 종류는 노드 1개로 합침, 노드 ID = 첫 요소 ID, 나머지 모델 ID 는 alias):
  ① **바인딩** `바인딩 · 클래스들` — 요청 파라미터(글자색 `req`)·VO 값 설정·값 조회·로직 없는 선언문·바인딩 함수(글자색 `fn`, `▸ 호출` 아래 `│` 로 본문 바인딩·요청 읽기·안쪽 바인딩 함수·return 중첩). 바인딩 함수 = `bindingFunctions` 2단계 고정점: ⓐ 순수 후보(최상위 return 뿐, 호출은 바인딩 코드·요청 읽기·후보 호출 — 최대 고정점) ⓑ 그중 바인딩이 있거나·바인딩 함수를 부르거나·들어오는 호출이 전부 바인딩 값 안(할당 호출 자체 제외 — 깊게 분석한 setter 는 바인딩 줄)이나 다른 바인딩 함수 안(최소 고정점: 값 함수 사슬 middle→inner, 재귀는 `↺ cycle`) — 상자를 그리지 않는다. builder 끝 `.build()` 처럼 바인딩을 품은 호출도 바인딩, 그 문장 줄은 안의 바인딩 줄 앞.
  ② **상태 변화** `상태 변화` — 반환 타입 `void` 호출(`#n call · 대상 vN`, 대상 = 수신 객체/`this`/static 클래스; 버전은 간선을 따라 전해지고 갈래가 합쳐지면 max — `stateVersions`, 그래서 if/else 갈래는 같은 버전) + 변수 값 변경(`V1 k/n`). 합친 블록에서도 호출마다 `==>`(여럿이면 `#n` 라벨, `checkMapping` 은 `modelIdsOf` 로 확인).
  ③ **로그** `로그`(class `log`, 배경색) — `model.LogCalls`(레벨 메서드 + 수신 객체 이름에 log, System.out/err.print*, printStackTrace), `#n 레벨 인자` 로 간략히. 로깅 호출은 `include-external-library-calls: false` 여도 모델에 남긴다(요청 읽기와 같은 예외).
  ④ **Loop** — for/foreach/while/do-while 은 `Loop` subgraph(조건 노드 + 본문, 흐름은 그대로). do-while 은 본문 → 조건 → `loop` 로 본문 첫 노드. 되돌아가는 곳(`loopReentry`)은 조건식 안 호출이 노드로 그려졌으면 그 첫 노드(매번 평가), for 초기화식·for-each 대상은 제외. 반복문 `done` 의 상태 버전 = 진입과 본문 끝 중 큰 값. `continue`/`break` 는 모델에 없어 다음 문장으로 이어진다(알려진 한계, `FlowchartEdgeCaseTest` 에 고정).
  ⑤ **일반 호출** — 위 어디에도 안 드는 호출 중 세부 상자로 가지 않는(`==>` 없음)·접힌 보조 메서드·값 조회가 아닌 것(`plainCall`)은 기본 블록처럼 이어서 노드 1개(제목 없이 `#n` 코드 줄들, `Pending` kind `code:<css>`). 색 구분(`callCss`: codeline·external·unresolved·depth·req·cycle)이 같은 것끼리만 — 색 의미 유지. 하나뿐이면 예전 호출 노드 그대로. 세부 상자로 가는 호출은 흐름의 요점이라 따로 노드(사용자 요구: 모든 줄을 따로 그리지 말고 묶기).
- 검증: `FlowchartSweepTest` — 모든 fixture 진입 메서드 × 설정 4종(default/external/deep/flat) + exclude 로 일반·분할·단계 보기를 그리고, 렌더링 텍스트를 되돌려 호출·블록·바인딩·변수 변경 코드가 보이는지 독립 확인(텍스트 오라클). `FLOW_DUMP=<dir>` 이면 .mmd 를 떨궈 Mermaid 파서로 따로 검사. 경계 사례 fixture `EdgeAction`.
- 글자색 줄: `MermaidGraph.styled(cls, text)` → `<span style='color:rgb(…)'>` (`TEXT_COLORS`; `#rrggbb;` 는 Mermaid 엔티티로 해석되므로 rgb()).
- if/else-if/switch/반복문 = 조건 노드(◇) + 분기 간선(`Yes`/`No`, case 라벨, `loop`/`next`/`done`). else/case 는 노드 없이 간선으로.
- try = 블록(subgraph `sg_T*`) — 본문은 그 안에 정상 흐름, `sg_T1 -.->|예외 타입| CATCH*`. 일반 흐름도(`tryFull`): catch 노드(헤더) → catch 본문 전부, `FINALLY*` 노드 → finally 본문, 잡히는 throw `-.->|catch|` 그 catch, 빠져나가는 return/throw `-.->|finally|` (`executesFinally`). 단계 보기(`tryBlock`): 예전처럼 catch = 처리 방식 요약 노드, catch/finally 본문 생략, 진입 메서드 `⚠ catch` 요약.
- 흐름 연결은 "열린 끝" 목록으로 계산(분기 끝들이 다음 요소로 합류). return/throw 뒤는 끊김.
- 모든 노드 줄은 **`타입 대상 = 값` 코드 한 줄, 줄임 없음** — 호출 노드 `#n T x = expr`(T = `CallSiteInfo.resultType` 선언 타입, 대입이면 추적 변수 타입), 요청 `#n T x = request.name ?: d`(T = `RequestParameterInfo.variableType`/규칙 type, 변수 없으면 `(T) request.name`), VO `#n 프로퍼티 = 값`(타입·변수명 없음 — 사용자 요구, `Binding.fieldType` 은 YAML 에만; 클래스는 노드 제목, 객체 둘 이상이면 `· 클래스` 줄). 역할·접근제어자·Depth·depth-limit 꼬리표 없음(css 색으로만). 조건 노드는 `Labels.blockHeaderLines`: 최상위 `&&`/`||` 앞 줄바꿈(연산자 맨 앞), for-each 는 `:` 앞뒤 두 줄. 단계 보기 체크 노드도 `conditionLines`.
- 바인딩·요청 읽기·값 변경은 **코드 형식 `대상 = 값`** (`Labels.requestLine` / `bindingLine` / `changeLine`, 값 식 안 요청 읽기 호출은 `Labels.codeValue` 로 `request.이름 ?: 기본값`).
- 요청 값 읽기·바인딩 코드는 위 ① 바인딩 블록으로 (단계 보기만 예전 `요청 파라미터` 노드 `flushReads`·`VO:` 형식). 변수에 바로 받지 않는 읽기는 문장 그대로(`#n int size = Integer.parseInt(request.size)`), 결과를 바로 받지 않는 호출 노드도 문장 전체(`#5 int v = c ? compute() : 0`), 람다 안 호출은 `⟨deferred⟩`.
- 내부 함수 접기(`collapsedHelpers`: 로직 역할이 아니고 전이적으로 로직 역할 호출에 닿지 않는 메서드 → 코드 한 줄 + `VO 값 설정`)는 **단계 보기에서만**. 일반 흐름도는 그 메서드들도 상자로 그린다(빠진 코드 없음). 값 조회 — 결과를 변수에 받는데 본문을 전개하지 않은 source 메서드(로직 역할·VO·Util 제외, 예 상위 클래스 `checkLogin`) — 는 바인딩 블록의 한 줄(`FlowchartGenerator.fetch`). 상위 클래스 메서드를 일부러 전개하지 않는다(과거에 시도했다가 checkLogin 내부 if 가 로직처럼 보여 되돌림).
- return/throw 는 메서드 박스 안 흐름 끝(실선, 분기 라벨 Yes/No), 처리되지 않은 throw 만 `-.->` `M*_uncaught`. (박스 밖 '이탈' 배치를 시도했으나 Mermaid 는 노드 좌표·박스 크기를 지정할 수 없어 dagre 는 끝으로 밀어내고 ELK 도 원하는 '오른쪽 배치'가 안 돼 되돌림.) 레이아웃 `output.layout` 기본 dagre, 옵션 elk(`MermaidGraph.init(layout)`).
- 코드 줄(#n …)이 들어가는 노드(class `codeline`·req·bind·var·state·log·depth·external·unresolved·cycle·fin·exc·check·post)는 `themeCSS` 로 왼쪽 정렬, `wrappingWidth` 2400(자동 줄바꿈 거의 없음). class 이름에 Mermaid 예약어(`call`, `click`, `end` …) 금지 — `MermaidGraph.RESERVED_CLASS` 로 검증(`call` 을 썼다가 파서 오류 난 적 있음).
- 문장 단위 바인딩: `CallSiteInfo.statement/statementKind/statementLocation`(감싸는 변수 선언·대입 ExpressionStmt, 주석 제외 `parser.Code.text`). 흐름도 `statementGroup`: 같은 문장의 호출에 로직 역할·세부 메서드·바인딩 코드가 없고 요청 읽기뿐이 아니며 단순 `x = call()` 이 아니면 문장 노드 1개(`#a–b 문장`, class bind, 나머지 호출·그 문장의 변수 변경은 alias). 단계 보기도 문장 단위 step, 판정 3c: 유틸·조회만 쓰는 선언 = 바인딩(3a 요청 컨텍스트 조회·3b 계산 메서드 다음). 줄 나누기는 `&&`/`||` 앞도 강한 자리.
- return/throw 식 안의 호출은 리프 노드에만(별도 호출 노드 없음, `inLeaf` + alias) — 세부 박스로 가는 호출만 예외. try 본문에 그릴 노드가 없으면(외부 호출뿐) `ControlBlock.bodyText`(try 본문 원문)를 `BODY_T*` 노드 하나로 그려 흐름이 try 를 지나가게.
- 인자 안의 호출(`CallSiteInfo.inArgument`, 깊이 무관 — `log.error("…" + util.x(req))` 의 util.x, `svc.f(vo.getX())` 의 getX)은 바깥 호출 노드의 코드에 보이므로 따로 그리지 않음(`argumentOnly`). 예외: 세부 박스로 이어지는 호출(==>), 요청 읽기, **바깥 호출이 모델에 없을 때**(라이브러리 제외·생성자 인자 — 그리지 않으면 코드가 사라짐). 모델·트리·YAML(`inArgument`)에는 그대로.
- 조건 노드(`decision`): 조건식·반복 헤더·switch selector 안의 호출은 조건 텍스트에 이미 보이므로 노드로 그리지 않고 조건 노드에 alias(중복 방지, 사용자 지적). 세부 박스로 가는 호출만 ==> 를 위해 노드로 남김. 조건식 안 요청 읽기는 `Labels.blockHeaderLines(r, b)` 가 `request.이름` 으로.
- 긴 줄: `MermaidGraph.wrap(line, output.max-line-length=100)` — 강한 자리(쉼표 뒤·` + `/` - ` 앞·`).` 체인 앞) 우선, 없으면 `(` 뒤, 한도 안에 없으면 넘는 첫 자리, 문자열 리터럴 안 제외, 이어지는 줄 `> `. 렌더 단계에서만(모델·YAML 은 원문).
- 분할 출력(`output.split-flowchart`, 기본 true): `FlowchartGenerator.renderSplit` — 전체 그래프를 한 번 만들어 검증한 뒤 `MermaidGraph.extract(sg_<M>)` 로 메서드별로 떼어 냄(안쪽 간선 유지, 바깥으로 나가는 간선은 `go_<대상>` ▶ 안내 노드 class golink, 들어오는 간선 제외). `00-overview.mmd` = 메서드 노드 + CALL 간선 집계. 합본 `flowchart.md` = 목차(GitHub 식 앵커) + 파트별 ```mermaid. 이전 실행의 `flowchart/*.mmd` 는 지우고 다시 씀. 전체 `flowchart.mmd` 도 그대로 출력.
- 렌더링 첫 줄은 `MermaidGraph.INIT`(`%%{init: …}%%` 노드 padding·wrappingWidth) — 여러 줄 라벨이 잘리지 않게.
- `-->` 정상 흐름, `==>` 본문을 전개한 호출(EXPANDED/SHARED/CYCLE → 메서드 시작 노드), `-.->` 예외(try 블록 → catch, 잡히는 throw → catch, return/throw → finally, 처리되지 않은 throw → `M*_uncaught`).
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
