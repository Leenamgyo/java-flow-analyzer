# java-flow-analyzer — Claude 작업 가이드

지정한 Action/Controller **하나**의 진입 메서드에서 시작해 Java 소스를 정적 분석하고,
**하나의 공통 모델**로 실행 흐름 + **데이터 바인딩 명세**(어떤 값이 어느 객체 필드로 가는지)를 만드는 마이그레이션 분석 도구.
출력: `analysis.yaml`(Source of Truth) · `catalog/<Util>.yml`(Util 기능 목록, `--all-methods`, 계속 관리) · `spec.md`(공개용 간결한 명세: 1. Request Param 정의 · 2. Response 정의) · `vo.md`(내부용 VO 목록) · `response.md`(JSP 지정 시) · `flowchart.html`(뷰어) · `flowchart.mmd`(+ `flowchart-sections/`·`flowchart-skeleton/`·`flowchart-svg/`) · `mapping.md` · `analysis.md` · `call-tree.txt` · `phase-flowchart.mmd` · `phase-summary.md`.
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
./gradlew runClass --args="--class ContentUtil"   # 전체 클래스 흐름도 → output/class/<클래스>/flowchart.html (Action 분석과 별도)
#   옵션: --config --source-root(반복 가능) --class --method --output --jsp(반복 가능, Response JSP 파일명) --all-methods(Util 기능 목록 → catalog/)
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
  sql/                   SqlCalls(호출 → statement id), MapperXmlIndex(매퍼 XML 색인·테이블 추출, 읽기 전용)
  response/              JspLocator(파일명 → 웹 루트에서 찾기), JspParser(JSP → 태그 트리), ResponseAnalyzer(XML 값 → 출처), ResponseReportWriter(response.md)
  phase/                 PhaseAnalyzer(진입 메서드 요소 → 바인딩·체크·로직·후처리), PhaseModel, PhaseText(표시), PhaseReportWriter(phase-summary.md)
```

## 전체 클래스 흐름도 (`classflow/ClassFlowMain`, Gradle `./gradlew runClass`)

사용자: 핵심 기능은 합치고 인터페이스(실행 명령)·소스·결과는 나눠서, 전체 클래스 방식은 새로 — 기존(Action 분석·Util 기능 목록)은 결과·기능 유지, 새 것은 VO·spec 필요 없이 flowchart.html 에 집중. 핵심 공유: `FlowAnalyzer.analyzeClass()` = 클래스에 선언된 본문 있는 메서드 전부를 D0 로 스케줄(같은 메서드·Service·DAO 는 한 번만, SHARED), `AnalysisResult.entryMethodIds`(Action 은 하나, `entryMethodId` = 첫 번째, YAML 에는 안 씀) + `isEntry(id)` — 흐름도·FlowView 의 "진입 메서드" 판정(entry 색, 상자 생략·바인딩 함수/메서드 제외)은 `isEntry` (단계 보기는 Action 전용이라 `entryMethodId` 그대로). 결과는 `<output>/class/<클래스>/flowchart.html` 하나(`HtmlFlowchart` — 데이터 `entries`, 뷰어가 진입 상자들을 가운데 열에 소스 순서로 세로로, 부른 쪽은 그 진입 상자 기준 좌우, 작은 세부 생략에서 진입 제외). 옵션 --config --source-root --class --output. `ClassFlowTest`.

**회귀 검증(사용자: 기존 기능은 테스트 빡세게)**: `GoldenDumpTest`(GOLDEN_DIR 있을 때만) — 모든 fixture Action public 메서드 × 설정 3종의 출력 전부를 덤프해 바꾸기 전 커밋(git worktree)과 diff. 이 작업에서 Action 출력은 HTML 외 전부 동일(절대 경로만 차이), HTML 데이터 동일(`entries` 추가), 브라우저 배치 좌표 동일 확인. 그 과정에서 기존 비결정성 2건을 찾아 고침: `BindingCollector.selfAssigns`(IdentityHashMap 순회 → 셋업 클래스 처음 나온 순서 `selfAssignOrder`; AppDetailAction.execute 바인딩 ID 가 실행마다 바뀌었음), `HtmlFlowchart` textColors(Map.of → TreeMap). `DeterminismTest`(같은 입력 4회 → 출력 바이트 동일).

## Util 기능 목록 (`catalog/UtilCatalog`, CLI `--all-methods`, 설정 `target.all-methods`, 폴더 `output.catalog-dir` 기본 `catalog`)

사용자: ContentUtil 은 특정 메서드에서 내려가는 계층이 아니라 **유틸 전체의 기능 분석**(기능은 같고 데이터 구조만 다름), 결과 파일은 빌드를 계속해도 남아 **계속 관리**, **void 목록**도 관리. (처음엔 메서드별 출력 폴더 + dependencies.md 로 만들었다가 이 요구로 다시 만듦.) 클래스에 선언된 본문 있는 메서드마다 `cfg.target.entryMethod`(+ 파라미터 타입, className 은 FQN) 를 바꿔 `Main.analyze`(분석 규칙 그대로) → 메서드 하나 = 기능 하나: signature·visibility·static·returns·services(선언 → 구현)·daos·sql(id·xml·tables)·uses-utils(다른 Util)·internal-calls(같은 클래스)·beans(빈 이름 근거)·reflection·unresolved·error. `catalog/<단순명>.yml` 에 쓰되 **갱신**: signature 로 맞춰 분석 칸(`GENERATED`)만 새로, 그 밖의 키(note 기본 "", 사람이 추가한 키) 보존, 이전에만 있던 메서드는 `removed: true` 로 끝에 유지, `void-methods` = 반환 void 인 현재 메서드. 시각·실행 정보 없음(소스가 같으면 파일도 같음 → git diff 가 변경만). catalog/ 는 git 관리(빌드·clean 무관), source-root 안이면 거부. 메서드마다 기존 출력도 `<output.directory>/<클래스>/<메서드>/`(오버로딩 `이름_2`)에 같은 분석 결과로 씀(`output.catalog-outputs` 기본 true — 사용자: 전체 메서드 output 도 나와도 됨). 저장소 설정 `config/class-survey.yml`. `UtilCatalogTest`.

## 핵심 설계 결정 (코드만 봐서는 이유가 안 보이는 것)

**Depth / 전개**
- 본문 분석 조건: `대상 Depth < max-depth` **또는** (`expand-same-class-methods` && 호출자와 같은 클래스) **또는** 대상 클래스 역할 ∈ `expand-roles`. 진입 메서드 D0 는 항상 분석.
  - **Action/Controller 안에 선언된 클래스**(중첩·내부, 이름 무관 — 사용자: `ParamMgr` 하드코딩 말고 Action 안 public class 를 탐지, `FlowAnalyzer.insideAction`, 최상위 클래스 역할로 판정, 로컬·익명 제외)는 어느 Depth 든 메서드 본문 분석 + **생성자 호출 `new X(request)` 를 호출 위치(CALL, resolutionBasis CONSTRUCTOR, 대상 MethodInfo = 생성자 `X(…)`, 키 `#<init>(…)`)로 기록하고 생성자 본문 분석**(`CallSink.resolveNew`, 인자 수 같은 생성자가 정확히 1개일 때만). 그 밖의 new(VO·라이브러리)는 호출로 만들지 않는다. 표시: 중첩 클래스 `ClassInfo.name` = 바깥 클래스 포함(`AppDetailAction.ParamMgr`), 메서드 `Labels.methodName/methodSignature` = `AppDetailAction.Paging.setup(…)`, 생성자는 클래스 이름만 `AppDetailAction.ParamMgr(…)`(사용자 요구; exclude·expand-classes 패턴은 중첩 클래스 단순명도 비교). 그래서 `declarations`·`Work` 는 `CallableDeclaration<?>`(본문은 `Code.body`), `callExprs` 는 `Expression`(생성자면 ObjectCreationExpr).
  - `expand-classes`(* 패턴, 클래스 단순명·FQN·바깥 클래스 포함 이름, 내장 기본 `["CountryUtil", "ODAction", "ContentUtil"]` — 로직 많은 Util·사용자 프로젝트의 공통 Action)도 역할 전개처럼 어느 Depth 든 본문 분석(생성자 포함). fixture `vas/AppDetailAction`(ParamMgr 생성자·Paging.setup)·`util/CountryUtil`, `RequestSetupTest`.
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
- 빈 이름 조회(사용자: Util 이 `Wcommon.getBean("productInfoBiz")` 처럼 빈 이름으로 Service·Biz·DAO 를 꺼내 의존 관계가 안 보임, `resolver/BeanIndex`, 설정 `analysis.bean-lookup-methods` 기본 `["getBean"]` — 첫 인자 리터럴·상수가 빈 이름): 빈 이름 → 클래스 = ① 스테레오타입 어노테이션 값 `@Service("x")` 등 ② XML `<bean id|name class>`(mapper-xml-roots 와 같은 루트 + `src/main/webapp`) ③ 값 없는 스테레오타입의 기본 이름(단순명 첫 글자 소문자) — 후보 2개 이상·없음이면 잇지 않고 사유. 수신 객체가 빈 조회 결과(직접·캐스팅·그 값으로만 초기화/대입한 지역 변수, `FlowAnalyzer.beanName`)인 인터페이스·추상 메서드 호출은 빈 클래스의 같은 이름·인자 수 메서드를 구현으로(`implementationBasis: BEAN_NAME`, link-single-implementation 과 무관, 구현이 여럿이어도). 리플렉션 `X.getClass().getMethod("m", …).invoke(X, …)`(또는 `Method m = …; m.invoke`)은 X 가 빈 조회 결과이고 메서드 이름이 리터럴·상수면 그 빈 메서드 호출로 기록(`resolutionBasis: REFLECTION`), 아니면 지금처럼 외부 호출. 본문에 빈 조회가 있는 메서드는 Depth·역할과 무관하게 본문 분석(`looksUpBeans` — Util 이 max-depth 밖이어도). fixture `util/ProductUtil`·`common/Wcommon`·`biz/ProductInfoBiz(Impl|Cache)`·`resources/spring/context-biz.xml`, `UtilBeanTest`.
- `include-external-library-calls: false` 면 origin=LIBRARY 호출은 모델에 넣지 않고 개수만 센다. 단 Request Parameter 추출은 필터 이전에 수행. 예외: **문장의 가장 바깥 호출**(`extraInfo.add(X)`, `int n = Integer.parseInt(…)`, 삼항 `a.isEmpty() ? … : a.get(0)` — 다른 호출의 인자·수신 객체가 아니고 조건식·return·for 헤더·식 람다 본문 밖, `FlowAnalyzer.outermostInStatement`)은 빼면 그 문장이 흐름도에서 사라지므로 남긴다(사용자 요구: 하나도 빼먹지 말 것, `if` 본문이 JDK 호출뿐이라 비었던 문제).

**매퍼 SQL (`sql/`, `CallSiteInfo.sql` = `SqlRef`, 설정 `analysis.include-sql-ids`·`mapper-xml-roots`)**
- 사용자 요구: 쿼리는 손으로 옮기므로 **어떤 XML 의 어떤 id 인지 + 그 문장이 쓰는 테이블 이름만**. 쿼리 본문은 모델·출력 어디에도 넣지 않는다.
- 판정은 두 가지뿐(추측 금지, `SqlCalls`): ① SqlSession/SqlMapClient 호출 — 수신 타입 단순명이 `SESSION_TYPES` 이거나 수신 객체가 `getSqlSession()` 등이고 메서드가 selectList·insert·queryForList … 이면 첫 인자(리터럴·상수·그 `+` 연결 → basis LITERAL/CONSTANT, 식이면 UNRESOLVED·SKIPPED)가 statement id. 라이브러리 호출이어도 모델에 남긴다(`FlowAnalyzer` 필터 예외, 요청 읽기·로그와 같은 이유). ② 역할 Mapper 인 source 인터페이스의 본문 없는 메서드 — `FQN.메서드명`(MAPPER_INTERFACE), `@Select` 류 어노테이션이면 ANNOTATION(XML 없음). ③ Mapper 역할이 아닌 source 인터페이스의 본문 없는 메서드(`SqlCalls.interfaceRef` — *DAO 이름의 MyBatis 매퍼): `@Mapper` 면 그대로, 아니면 `SqlRef.requiresXmlMatch` 로 XML namespace = 인터페이스 FQN 인 statement 가 있을 때만(없으면 sql 지움). DAO 클래스 쪽 문자열 검색(상속 selectList·공통 DAO 빈 등)은 넣었다가 사용자 요청으로 뺐다 — 사용자는 인터페이스 호출로 직접 찾는다. fixture `DaoPatternAction`·`dao/MemberDAO`·`mapper/MemberDAO.xml`, `MapperSqlTest.daoNamedMapperInterface`.xml`.
- XML(`MapperXmlIndex`, 읽기 전용, 외부 DTD 미로드, UTF-8 아니면 MS949(`decode`), BOM·선두 공백 제거, 파서 오류는 화면(stderr)에 찍지 않고 `errors` → notes 만 — 사용자: `[Fatal Error] :1:1: Content is not allowed in prolog` 가 떴음): `mapper-xml-roots` 비우면 각 source root + 옆 `src/main/resources`. `<mapper namespace>`·`<sqlMap>` 의 select/insert/update/delete/statement/procedure. 매칭: `ns.id` 정확히 → 없으면 단순명 namespace(`OrderMapper.insertOrder`)·namespace 없는 id(iBatis) → 0개 NOT_FOUND, 2개 이상 AMBIGUOUS(후보). 파일 경로는 프로젝트 폴더(…/src 의 부모) 기준.
- 테이블(`MapperXmlIndex.tables`): `<include refid>` 펼침(`<sql>` 조각), 동적 태그(<if> 등)·selectKey 내용 포함. 토큰 단위로 FROM 목록(쉼표)·JOIN·INTO(뒤 괄호 = 컬럼 목록)·UPDATE(FOR/KEY/THEN 뒤 제외)·MERGE USING 뒤 이름. 괄호는 SELECT/WITH 로 시작할 때만 SQL 문맥(EXTRACT(x FROM y)·TRIM 제외), 문자열·주석 제외, DUAL·WITH 이름 제외, `${…}` 는 그대로 + notes. 정규식 수준 추출이라는 한계를 분석서에 적는다.
- 출력: analysis.md `## 2. Mapper SQL` (2.1 매퍼(namespace)별 XML·테이블, 2.2 호출별 id·종류·XML·테이블·비고), 흐름도 호출 노드·return 노드(합쳐진 호출) 아래 `SQL id · 파일 <종류>` / `테이블 …` 줄(`Labels.sqlLines`), call-tree `⛁ …`, analysis.yaml calls[].sql. fixture `EdgeAction.sqlCalls`·`UserMapper`·`OrderDAO`·`src/main/resources/{mapper,sqlmap}/*.xml`, `MapperSqlTest`.

**요청 읽기 규칙 (`request-parameter-rules`, `RequestParameterExtractor`)**
- 바인딩으로 볼 프로젝트 고유 메서드는 YAML 로 관리. `class` 생략 = 메서드명만으로 매칭(상속 메서드 등). `name-arg-index` = 이름 인자 위치, `parameter` = 고정 이름, 둘 다 없으면 메서드명에서(`getSystemId` → `systemId`, nameResolution `CONFIG`). 둘 다 쓰면 설정 오류. `default-arg-index` 는 그 인자가 있는 호출에서만 읽고, 이름 인자가 없는 호출엔 규칙 미적용 — 인자 수가 다른 오버로드(`getParameter(req, n)` / `(req, n, def)`)를 규칙 하나로, 따로 적으면 인자 수에 맞는(기본값까지 쓰는) 규칙 우선(`match`, 사용자 요구: 오류 나면 안 됨). `wraps: n` = 기본값 래퍼(인자 n 이 요청 읽기면 그 P* 를 래퍼 호출로 옮기고 default·받는 변수·extraction `A → B` 갱신, 안쪽 읽기는 인자 안 호출이 되어 흐름도에서 빠짐; commons StringUtils.defaultIfEmpty/Blank/defaultString 내장). fixture `com.example.action.vas.GetVasAppList`, `RequestRuleTest`.
- 로직 있는 읽기 함수(`RequestParameterInfo.readerLogic`, YAML `readerLogic`): 규칙에 맞은 source 메서드 본문에 반복문·try·throw·switch·new·필드 대입·요청 읽기(`READ_CALLS`)/값 고르기(`TRIVIAL_CALLS`: isEmpty·trim·defaultString …) 밖의 호출이 있으면 근거 문자열(`RequestParameterExtractor.readerLogic`). 본문 모르면 null(추측 금지). 사용자 요구: StringUtil.getParam 같은 단순 래퍼는 `request.x` 로 줄여도 되지만 로직 있는 읽기 함수는 표현 — `Labels.requestLine` 이 호출 코드 그대로 + `  ⇒ request.이름`, `codeValue` 도 줄이지 않음, analysis.md Source 열 `— 읽기 함수에 로직 있음: …`. fixture `ScVersionAction`(기본값이 함수 호출 `VasUtil.getExceptionScVersion(appId)`), `InitialValueAndReaderTest`.
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
- 필드 커버리지: 선언(소스 인스턴스 필드) / 할당 / 조건부로만 할당 / 초기값만 / 미할당 / 확인 불가(copyProperties 대상). 상속 정보는 `analysis.include-inheritance`(기본 false — 사용자 요구로 분석 내용에서 뺌)일 때만: 상위 클래스 필드(선언 목록)·`ClassInfo.extendsTypes`·extends 에서 온 역할 근거 SUPERTYPE(`FlowAnalyzer.shownClassification` — extends 없이 분류해도 역할이 같을 때만 근거에서 뺌, 상위 클래스로만 역할이 정해지면 유지). 역할 판정 자체(`classify`)는 항상 extends 포함. 쓰지 않는 상위 VO 는 출력 어디에도 안 나온다(`EdgeAction.inheritedUnused`). 상속 필드에 값을 넣는 바인딩(`vo.setRegId(…)`)은 설정과 무관하게 기록·할당 필드에 포함(fixture `EdgeAction.inheritedFields`, `NoticeVO extends BaseVO`).
- 요청 셋업 클래스(`BindingCollector.selfAssign`): VO 가 아닌 클래스의 인스턴스 메서드 안 자기 필드 대입(`appId = request.getParameter(…)`·`this.mcc = …`, 람다·익명 클래스 안 제외)은 그 클래스 대입 중 하나라도 출처가 요청 값이면 **그 클래스 자기 필드 대입 전부**를 바인딩(FIELD)으로 — 요청 값 아닌 대입도 넣어야 커버리지가 '미할당'으로 잘못 나오지 않음. VO 는 setter 본문과 겹쳐 제외. 객체 = 메서드의 분석된 호출 위치가 정확히 1개이고 수신 객체가 변수면 호출자 쪽 그 변수(`paging`), 생성자면 new 를 받는 변수(`ParamMgr pm = new ParamMgr(request)` → `pm`), 아니면 (클래스, this). 호출 위치가 하나가 아니거나 `new` 를 변수에 받지 않으면 객체는 (클래스, this) — `methodId` 없음(소유 메서드 없는 객체, 출력은 "(필드)"; 예전엔 분석서·단계 요약이 r.method(null) 로 NPE "Cannot read field classId", fixture `EdgeAction.holderVariants`). 생성자 안에서 자기 메서드(`load(request)`·`this.x()`)를 부르면 부른 메서드의 this 를 따라간다.
- Lombok 등 생성 코드는 builder/생성자 resolution 을 `PARTIALLY_RESOLVED`/`UNRESOLVED` 로 낮추고 사유 기록.
- JavaParser `Node.equals` 는 구조 비교라 AST 키 맵은 `IdentityHashMap`.

**변수 상태 변화 (`binding/VariableStateCollector`, V* / VC*)**
- 대상: 분석한 메서드의 지역 변수·파라미터 중 선언 뒤 `=`·복합 대입·`++/--` 가 있는 것 + **값이 바뀌지 않는 지역 변수 선언 전부**(INIT 이력 하나 — `boolean data = false`·`String label;` 처럼 호출 없는 선언이 흐름도에서 빠졌던 문제, 사용자 지적). 제외: 필드(선언 없는 이름), for 헤더(init/update) 변수, 향상된 for 변수, 람다·익명/로컬 클래스 안 선언(`ownLocal`). 바인딩 수집 뒤 같은 AST 재순회. INIT 코드는 `타입 이름 = 값`(선언자마다). mapping.md `## 변수 상태 변화`·VO `⟳` 는 실제 변경이 있는 변수만.
- 호출 없는 선언(`AnalysisResult.isPlainDeclaration`/`declarationsIn` — INIT 이고 식 안 모델 호출 없음): 흐름도 바인딩 블록 한 줄(`Pending.declare`, 노드 ID `VAR_<VC>`, 호출 없는 바인딩과 위치 순 `preItems` — 같은 자리면 선언 먼저), 트리 `≔`. `check()` 가 누락 검사(단계 보기 진입 메서드 제외). 호출이 있는 선언은 호출 노드·문장 줄이 코드를 보인다.
- 이력 첫 항목은 INIT(선언, 값 없을 수 있음) / PARAM, 이후 ASSIGN / COMPOUND / INCREMENT / DECREMENT. 조건(`ConditionText`)·소속 블록·식 안 호출(`callSiteIds`)·출처(값 식의 피연산자를 `SourceTracer` 로, 변수 자기 자신 제외)를 남긴다.
- 표시(이력 전체를 흐름 위에서 추적): flowchart `값 변경` 노드(`VAR_<VC>`, 코드 그대로, 식 안 호출이 끝난 뒤 = 변경 끝 위치 이후 첫 요소 앞; 나머지 VC 는 alias). 선언(INIT, 값 있음)도 그린다 — 값 식 == 호출식이면(선언·대입 모두) 호출 노드에 `→ x · x v2`(변경일 때만) + alias, 값 식 == 요청 읽기 호출이면 요청 파라미터 줄(대입일 때만 `· x vN`). 트리 `≔` 줄. mapping.md `## 변수 상태 변화`. VO 값 설정 줄의 값이 이런 변수면 `⟳ 변경 n회`. `Labels.historyTag`: **변수 이름 기준 버전** `· total v3`(타입 무관 — 사용자 요구: 전역 ID `V1`·`V1 k/n` 이 버전처럼 읽혀 혼란) — 선언 값·파라미터 = v1(꼬리표 없음: 선언은 상태 변화가 아님, 요청 읽기 줄 `String deviceId = request.deviceId` 에도 안 붙음), 실제 변경마다 +1, 값 없는 선언 뒤 첫 대입 = v1. VO 줄은 `⟳ 변경 n회`. V*/VC* ID 는 YAML·mapping.md 에만. 흐름도의 `값 변경` 노드는 실제 변경(타입 없는 대입·복합 대입·++/--)만, 호출 노드의 타입은 선언(`resultType`)일 때만.
- 상태 변화 + 호출이 엮인 문장(사용자: 노드가 하나 더 생김 — 노드 말고 글자로): 값 식이 호출을 품은 변경(`userId = normalizeId(userId) + "_x"`, `count += normalizeId(userId).length()`)의 문장에 세부·로직 호출이 있으면, 문장 전체를 보이는 첫 호출(`showsStatement`)을 `assignedBy` 에 넣어 그 호출 줄 끝에 `· userId v3`(주황 글자)만 붙이고 `값 변경` 노드(VAR_)는 만들지 않는다(문장 줄이 이미 대상 변수를 보이므로 `→ x` 없음). 같은 문장을 이미 보인 노드가 있으면(`stmtShown`) 뒤의 일반 호출은 그 노드에 alias, 세부·로직 호출은 따로 그리되 호출식만(`exprOnly`). 요청 읽기 줄이 문장 전체를 보였으면(변수에 바로 받지 않는 읽기 — `String k = middle(request.getParameter("k"))`) 그것도 `stmtShown` 에 기록 → 뒤의 세부 호출은 `#4 middle(request.k)` 호출식만(예전엔 같은 문장 두 줄, 사용자 보고). 복합 대입의 호출 결과 변수는 `=` 로 바꿔 쓰지 않고 문장 그대로(`assignedVar` — 예전엔 `#7 count = normalizeId(userId).length()` 로 틀리게). 꼬리표를 붙일 호출은 반드시 호출 줄(callCode)로 그려지는 것만 고른다(flowchart-exclude·setter·바인딩 함수·바인딩을 품은 호출·조건식 등 머리 위치 제외) + 같은 문장 alias 때 그 호출의 변경도 alias + build 끝 안전망(빠진 변경은 그 호출이 든 노드에 alias) — 아니면 '변수 변경 노드 누락'. fixture `EdgeAction.stateWithCalls`, `StateCallTest`. 내장 기본 조건 전수 검사 `DefaultConfigSweepTest`(다른 테스트는 최소 설정이라 기본 조건에서만 나는 오류를 놓침).
- 단계 보기: 요청 값에서 온 변수(초기값 출처 REQUEST)의 변경 = 바인딩(요청 값 보정). 그 밖의 최상위 변경은 근거 없음 → 이웃 단계. 변경 ID 도 `suppressedCalls` 로 요약 대상 관리.

**흐름 보기 (`FlowView`, 트리·Mermaid 공용)**
- 흐름 중심은 호출·분기·예외. 바인딩 코드(setter/builder/put 호출과 그 인자 안의 전개되지 않은 호출)는 흐름에서 빼고, **(객체, 메서드)당 요약 1개**(`BIND_<O>_<M>`: `N fields assigned · N conditional · N type conversion`)를 둔다(call-tree). flowchart 는 이 요약 대신 흐름 위치마다 `VO 값 설정` 노드(아래 Mermaid 참고).
- 요약 위치 = 그 메서드에서 해당 객체 바인딩을 모두 감싸는 가장 가까운 공통 컨테이너(then/else 에 흩어지면 그 if 앞).
- 바인딩 메서드(본문이 바인딩 + 최상위 return 뿐, 예 `toSearchVO`)는 그리지 않고 호출 위치에 요약(`BIND_<O>_<CALL>`). 단 로직 역할(Service·Biz·DAO·Repository·Mapper, `FlowView.LOGIC_ROLES`) 메서드는 바인딩 메서드·바인딩 함수(`bindingFunctions` 순수 후보)로 접지 않는다 — 사용자: Biz 도 VO 를 추적해야(`NoticeWriteBiz.write(vo)` 가 set + 빈 DAO 호출뿐이라 바인딩 함수로 접혀 Biz·DAO 상자가 사라졌음). 흐름도는 이를 넓힌 `FlowchartGenerator.bindingFunctions` 를 바인딩 블록 안에 중첩.

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
- `spec.md`(`SpecWriter`, 사용자 요구: 분석이 끝난 것을 명세 파일 하나에 장 단위로, **간결하게** — 분석 근거는 넣지 않음): `## 1. Request Param 정의` = 일반 API 명세 Request 표 하나 `파라미터 | 타입 | 필수 | 기본값 | 설명`(필수는 어노테이션에 적힌 경우만 Y/N, 아니면 전부 `N` — 사용자 요구: StringUtil.is… 검사라 알 수 없으니 N 으로; 설명 = 값 변환만(검증 조건은 빼달라는 사용자 요구 — analysis.md 1장에만), 없으면 빈칸). 이름 미확정·전체 조회는 건수 한 줄로 analysis.md 안내. 다음 장은 이 파일에 이어 붙인다.
- `vo.md`(`VoListWriter`, **내부용** — spec.md 는 공개용이라 넣지 말 것, 사용자 요구): `VO 클래스 | 변수명 | 사용 위치 클래스` 표 + `## 프로퍼티` VO 마다 `프로퍼티 | 타입 | 사용(set/get)`(사용자: 사용하는 프로퍼티 명·타입도). 대상 = 값을 채우는 역할 VO 객체(셋업 클래스·Map 제외) + VO getter 읽기(`AnalysisResult.voReads` — `BindingCollector.getter`: 인자 없는 get*/is* 의 수신 변수 선언 타입이 VO 소스 클래스면, getter 선언이 없어도(Lombok); 읽기만 하는 resultVO·for-each 변수 포함). 타입 = VO 필드 선언(상위 포함, `ObjectInfo.fieldTypes`), 없으면 getter 반환 타입, 모르면 -. 둘 다 YAML 에는 넣지 않는 표시용. 사용 위치 = 객체 변수가 있는 메서드의 클래스(없으면 값을 넣는 메서드들의 클래스), VO 클래스·변수명 순.
- Response(JSP, `response/`, 설정 `response.jsp`·`response.web-roots`, CLI `--jsp`): 어느 JSP 인지는 **사용자가 JSP 파일 경로로 정한다**(Action 코드의 forward 이름으로 추측하지 않음, 사용자 결정). 실제 있는 파일 경로(절대·실행 위치 기준 상대)면 그대로(`JspLocator.directFile`, include 기준 웹 루트 = 위쪽 WEB-INF 의 부모), 파일명이면 `JspLocator` 가 웹 루트(설정 없으면 source root 의 프로젝트 폴더에서 WEB-INF 의 부모)에서 `파일명`/`앞폴더/파일명` 끝 일치로 찾음 — 0개 NOT_FOUND, 2개 이상 AMBIGUOUS(후보, 고르지 않음). `JspParser`(읽기 전용): 출력 XML 태그·텍스트·`${}`·`<%= %>`, JSTL c:forEach/if/choose/set/out/import(`c:` 는 taglib 선언 없어도 core — 실전 JSP 에 선언이 없었음), EL 함수 `vf:f()` → 값 `함수 vf:f()`, `c:set x = ${x}` 는 바깥 x(재귀 방지), `<%@ include %>`·jsp:include 펼침, 그 밖 taglib = Custom, 스크립틀릿은 notes. `ResponseAnalyzer`: 값 출처 = 반복 변수 → items, c:set → 값, param./header. → 요청, 나머지 이름 → 분석한 코드의 `setAttribute/addAttribute/addObject("이름", v)`(없으면 "넣는 곳 없음"). 출력: `response.md`(XML 구조·값 출처 사슬·위치·Action→JSP attribute 표), `spec.md` `## 2. Response 정의` = JSP 경로 · Content-Type(`<%@ page contentType %>`, 없으면 확인 불가) + 응답 XML 코드 블록(`ResponseAnalyzer.xml` — 사용자 요구: 표 대신 코드 구문으로. 출력 태그·속성·값 원문 그대로, CDATA 유지, forEach/if/when/otherwise 는 `<!-- 반복: … -->`…`<!-- /반복 -->` 주석 범위, include 펼침, c:set 생략, 스크립틀릿 주석). 값 출처는 response.md 에만. fixture `sample-project/src/main/webapp/WEB-INF/jsp/vas/appDetail.jsp`(+common/footer.jsp)·`appList.jsp`(실전형), `ResponseTest`.
- analysis.md 순서(사용자 요구): 1 Request Parameter → 2 Mapper SQL(XML id·테이블) → 3 분석 개요(관련 클래스 표: 역할별 `#### 역할 (n)` 표 — `CLASS_GROUPS` 순서 Action·Controller·Service·Biz·DAO / Mapper / Repository(한 묶음)·VO·Validator·Util·Other (Service·Biz 는 따로 — 사용자 요구), 역할 안에서 인터페이스 먼저·그다음 클래스, `종류`·`사용 위치` 열 — 호출자 → 메서드 · CALL ID :줄, 구현 후보 연결, VO 객체) → 4 실행 흐름도(다이어그램은 너무 커서 넣지 않고 flowchart-skeleton·flowchart-sections·flowchart.mmd·phase-flowchart.mmd 를 직접 열라는 안내만) → 5 바인딩 → 6 예외 → 7 비교 → 8 부록.
- analysis.md 에는 메서드 호출 관계·메서드 목록·Unresolved 목록·호출 트리를 넣지 않는다(사용자 요구: 분석서에 중요하지 않음). 각각 `analysis.yaml`(methods, calls 의 resolution)·`call-tree.txt` 에 있고, 분석서 머리말에 위치를 안내.
- 호출 트리 제외 `output.call-tree-exclude`(사용자 관리, 기본 없음): `flowchart-exclude` 와 같은 패턴·매처(`serializer.Excludes`, 흐름도와 공용). 맞는 호출은 줄도 그 아래 전개도 없고, 트리 끝에 `※ call-tree-exclude (… N개)` 를 남긴다. 표시에서만 빼며 모델·YAML·mapping.md 는 그대로.

**Mermaid (SPEC-v2 §5)**
- 기본 `TD`(위→아래). subgraph 는 분석한 메서드당 1개 + 그 안의 try 블록(`sg_T*`)·반복 영역(`sg_FOR*`/`sg_FOREACH*`/`sg_WHILE*`/`sg_DO*`). 본문 미분석 메서드는 노드를 만들지 않고 호출 노드 라벨로만 표시.
- **빠진 코드 없음 (일반 흐름도)**: 분석한 메서드·호출·블록은 모두 노드 또는 블록 줄로 그린다(catch/finally 본문, 그 안에서만 호출되는 메서드, Service 로 안 가는 보조 메서드 포함 — 일반 흐름도에서는 `collapsedHelpers` 로 접지 않는다). 빼는 것은 사용자 설정 `output.flowchart-exclude`(* 패턴: 대상 `클래스.메서드` 단순명·FQN, 원문 `수신객체.메서드`)뿐 — 맞는 호출은 노드 없음, 맞는 메서드는 상자 없음, 흐름도 머리에 `%% flowchart-exclude …` 주석. 모델 밖 코드(`include-external-library-calls: false` 로 빠진 JDK 호출 — 바깥 호출·조건식·return 코드에 보이는 것뿐, 호출 없는 문장, `continue`/`break`)는 흐름도도 모른다. 규칙 객체 `FlowchartGenerator.Rules`(normal / phase — 단계 보기는 catch/finally 본문 생략 유지).
- **코드 블록 하나로 계속 쌓기 (현행, 사용자 요구)**: 아래 5종의 **줄 종류는 그대로**지만 종류가 바뀐다고 노드를 끊지 않는다(`Pending.start` 는 종류 무시) — 끊는 것은 제어 블록(if·switch·반복·try·return/throw)과 따로 그리는 호출(세부 상자로 가는 `==>` 호출·로직 역할 호출·접힌 보조 메서드·값 조회 = `plainCall` 아닌 것)뿐. 노드에 `바인딩`/`상태 변화`/`로그` **제목 없음**(사용자: 제대로 나누지 못해 없앰), 로그 줄은 코드 그대로(`callCode`). VO 줄은 객체가 바뀔 때마다 `· 클래스 변수` 한 줄. 상태 변화 = 버전 꼬리표(`· x v2`) + **주황 글자**(`TEXT_COLORS` `state`) — 값 변경 줄뿐 아니라 호출 결과 대입(`x = call()`, 타입 없음)·변경을 품은 문장 줄도 같은 주황(`stateClass`, 호출 노드·블록 줄·문장 줄, 사용자 지적: `isScpRequest = VasUtil.isScpRequest(…)` 가 주황이 아니었음). 요청 읽기 줄은 요청 값 청록 유지. 코드 상자(네모)는 **색 구분 없음** — codeline·bind·req·var·state·log·external·depth·unresolved·cycle 모두 `FlowchartGenerator.BOX`(다크 `DARK_BOX`), 구분은 글자 색(요청 청록·바인딩 함수 보라·상태 변화 주황)만. 호출 하나뿐인 블록은 예전 호출 노드(`callNode`). 단계 보기의 요약 노드(PH_*)는 예전 제목 유지.
- (이전 설계 — 줄 종류 설명용) **흐름도 블록 5종** (연속된 같은 종류는 노드 1개로 합침, 노드 ID = 첫 요소 ID, 나머지 모델 ID 는 alias):
  ① **바인딩** `바인딩 · 클래스들` — 요청 파라미터(글자색 `req`)·VO 값 설정·값 조회·로직 없는 선언문·바인딩 함수(글자색 `fn`, `▸ 호출` 아래 `│` 로 본문 바인딩·요청 읽기·안쪽 바인딩 함수·return 중첩). 바인딩 함수 = `bindingFunctions` 2단계 고정점: ⓐ 순수 후보(최상위 return 뿐, 호출은 바인딩 코드·요청 읽기·후보 호출 — 최대 고정점) ⓑ 그중 바인딩이 있거나·바인딩 함수를 부르거나·들어오는 호출이 전부 바인딩 값 안(할당 호출 자체 제외 — 깊게 분석한 setter 는 바인딩 줄)이나 다른 바인딩 함수 안(최소 고정점: 값 함수 사슬 middle→inner, 재귀는 `↺ cycle`) — 상자를 그리지 않는다. builder 끝 `.build()` 처럼 바인딩을 품은 호출도 바인딩, 그 문장 줄은 안의 바인딩 줄 앞. 펼친 본문 중복 금지(사용자 보고: 함수가 내재된 경우 노드 안에 같은 선언이 여러 번): 펼친 본문 안에서 바인딩 줄로 이미 보이는 할당 호출(깊게 분석한 setter 등, `Binding.callSiteId`)은 `▸` 로 또 펼치지 않고(최상위와 같은 규칙), 같은 노드(`Pending.expandedFns`, flush 때 비움)에서 이미 펼친 함수를 다시 부르면 `▸ 호출` + `│ (위 #n 와 같은 본문)` 한 줄. 다른 노드(분기 등)에서는 다시 펼친다. `FlowchartSweepTest.repeatedExpansions` 가 전 fixture × 설정 4종에서 검사(fixture `EdgeAction.inlineRepeats`·`inlineNested`·`bindingChain`·`holderVariants`).
  ② **상태 변화** `상태 변화` — 소스에 있는 변수 값 변경(`=`·복합 대입·`++/--`, `· x vN`)만. 메서드 호출은 넣지 않는다: 예전에 반환 타입 `void` 호출을 상태 변화(`· this vN` 버전)로 그렸으나 void 가 상태를 바꾼다는 보장이 없어(검사·로그만 하는 void, 상태를 바꾸면서 값을 돌려주는 add 등) 추측이라 제거(사용자 지적). void 호출은 일반 호출(⑤).
  ③ **로그** `로그`(class `log`, 배경색) — `model.LogCalls`(레벨 메서드 + 수신 객체 이름에 log, System.out/err.print*, printStackTrace), `#n 레벨 인자` 로 간략히. 로깅 호출은 `include-external-library-calls: false` 여도 모델에 남긴다(요청 읽기와 같은 예외).
  ④ **반복** — for/foreach/while/do-while 은 `↻ for` 등 subgraph(조건 노드 + 본문, 흐름은 그대로). 조건 노드는 **육각형**(`{{…}}`, if/switch 는 마름모), 선 라벨·색·합류 노드는 아래 "반복 표시"(`FlowchartGenerator.LOOP_*`). 되돌아가는 곳(`loopReentry`)은 조건식 안 호출이 노드로 그려졌으면 그 첫 노드(매번 평가), for 초기화식·for-each 대상은 제외. `continue`/`break` 는 모델에 없어 다음 문장으로 이어진다(알려진 한계, `FlowchartEdgeCaseTest` 에 고정).
  ⑤ **일반 호출** — 위 어디에도 안 드는 호출 중 세부 상자로 가지 않는(`==>` 없음)·접힌 보조 메서드·값 조회가 아닌 것(`plainCall`)은 기본 블록처럼 이어서 노드 1개(제목 없이 `#n` 코드 줄들, `Pending` kind `code:<css>`). 색 구분(`callCss`: codeline·external·unresolved·depth·req·cycle)이 같은 것끼리만 — 색 의미 유지. 하나뿐이면 예전 호출 노드 그대로. 세부 상자로 가는 호출은 흐름의 요점이라 따로 노드(사용자 요구: 모든 줄을 따로 그리지 말고 묶기).
- 검증: `FlowchartSweepTest` — 모든 fixture 진입 메서드 × 설정 4종(default/external/deep/flat) + exclude 로 일반·분할·단계 보기를 그리고, 렌더링 텍스트를 되돌려 호출·블록·바인딩·변수 변경 코드가 보이는지 독립 확인(텍스트 오라클). `FLOW_DUMP=<dir>` 이면 .mmd 를 떨궈 Mermaid 파서로 따로 검사. 경계 사례 fixture `EdgeAction`.
- 호출 색 `callCss`: 외부 호출(EXTERNAL·NO_BODY)은 RESOLVED·PARTIALLY_RESOLVED 모두 `external`(회색) — `request.getRequestURI()` 처럼 소스 밖 타입은 시그니처만 몰라 PARTIALLY 인데 주황 점선(`unresolved`)으로 보여 미해결로 오해(사용자 지적). `unresolved` 는 AMBIGUOUS·UNRESOLVED·NOT_APPLICABLE 만.
- 글자색 줄: `MermaidGraph.styled(cls, text)` → `<font color='#rrggbb'>`(`fontOpen`). `<span style='color:…'>` 는 쓰지 않는다 — Mermaid 11 이 한 노드에 그 span 이 둘 이상 나온 뒤 줄의 엔티티 세미콜론을 잃어 `#7` → `#357`, `#quot;` → `#quot` 로 렌더링(SVG 로 확인). `req` 는 줄 전체가 아니라 요청 값 표기(`request.이름 ?: 기본값`, `request[식]`, `request.(전체)`)만 칠한다(`MermaidGraph.requestRanges` 스캐너 — 기본값은 리터럴 또는 이름·호출 체인(괄호 짝 맞춤; 정규식일 때 `?: VasUtil.f(appId)` 가 `(` 앞에서 색이 끊겨 사용자 지적), 색 범위는 줄을 끊기 전에 계산해 이어지는 줄도 칠함(`requestPieces`); `requestSpans` — 문장 줄의 `Integer.parseInt(…)` 등 요청 항목이 아닌 코드까지 칠해져 사용자 지적, `request.getRequestURI()` 같은 호출은 제외) (`TEXT_COLORS`, 색 뒤에 ';' 가 없어 엔티티로 해석되지 않음).
- if/else-if/switch/반복문 = 조건 노드(◇, 반복은 육각형) + 분기 간선(`Yes`/`No`, case 라벨, 반복은 라벨 없이 색·`↻`). else/case 는 노드 없이 간선으로.
- try = 블록(subgraph `sg_T*`) — 본문은 그 안에 정상 흐름, `sg_T1 -.->|예외| CATCH*`(예외 타입은 catch 노드에 — subgraph 에서 나가는 간선 라벨이 catch 노드에 가려져 잘려 보여 짧게). 일반 흐름도(`tryFull`): catch 노드(헤더) → catch 본문 전부, `FINALLY*` 노드 → finally 본문, 잡히는 throw `-.->|catch|` 그 catch, 빠져나가는 return/throw `-.->|finally|` (`executesFinally`). 단계 보기(`tryBlock`): 예전처럼 catch = 처리 방식 요약 노드, catch/finally 본문 생략, 진입 메서드 `⚠ catch` 요약.
- 흐름 연결은 "열린 끝" 목록으로 계산(분기 끝들이 다음 요소로 합류). return/throw 뒤는 끊김.
- DB 로직 표시(사용자: 내부에 DAO 가 있으면 DB 로직이라 표시 — 색·배치는 그대로, 글자 줄만): 호출 대상(또는 구현 후보) 안에서 본문을 분석한 메서드를 따라 (전이적으로) DAO·Mapper·Repository 메서드를 부르면 호출 노드(callNode·Pending.code)와 그 메서드 시작 노드에 `⛁ DB 로직: ScpAppDAO.selectApps, …`(부르는 순서) 한 줄(`dbReach`/`dbTargets`/`dbLine`). DAO 를 직접 부르는 호출·DB 역할 메서드 자신에는 없음. 태그(사용자: 태그 형식으로 티 나게): 그 호출 줄 끝과 메서드 상자 제목 끝에 `[⛁ DB]`(`MermaidGraph.DB_TAG`) — 렌더링(Mermaid `renderItem`·subgraph 제목, HTML `lines`·`titleLine`)에서 이 글자만 진한 빨강(`TEXT_COLORS` db `#b91c1c` / 다크 `#f87171`), 상자 색·배치 그대로.
- 메서드 시작 노드(STADIUM) 첫 줄은 파라미터 이름까지 `Labels.methodDeclaration` — `NoticeWriteBiz.write(NoticeVO vo, String body)` (사용자: 타입만 보여 인자가 지워진 것 같음; 이름 모르면 타입만인 `methodSignature`). 제목·호출 대상 표시는 그대로 `methodName`/`methodSignature`.
- 모든 노드 줄은 **`타입 대상 = 값` 코드 한 줄, 줄임 없음** — 호출 노드 `#n T x = expr`(T = `CallSiteInfo.resultType` 선언 타입, 대입이면 추적 변수 타입), 요청 `#n T x = request.name ?: d`(T = `RequestParameterInfo.variableType`/규칙 type, 변수 없으면 `(T) request.name`), VO `#n 프로퍼티 = 값`(타입·변수명 없음 — 사용자 요구, `Binding.fieldType` 은 YAML 에만; 클래스는 노드 제목, 객체 둘 이상이면 `· 클래스` 줄). 역할·접근제어자·Depth·depth-limit 꼬리표 없음(css 색으로만). 조건 노드는 `Labels.blockHeaderLines`: 최상위 `&&`/`||` 앞 줄바꿈(연산자 맨 앞), for-each 는 `:` 앞뒤 두 줄. 단계 보기 체크 노드도 `conditionLines`.
- 바인딩·요청 읽기·값 변경은 **코드 형식 `대상 = 값`** (`Labels.requestLine` / `bindingLine` / `changeLine`, 값 식 안 요청 읽기 호출은 `Labels.codeValue` 로 `request.이름 ?: 기본값`).
- 요청 값 읽기·바인딩 코드는 위 ① 바인딩 블록으로 (단계 보기만 예전 `요청 파라미터` 노드 `flushReads`·`VO:` 형식). 변수에 바로 받지 않는 읽기는 문장 그대로(`#n int size = Integer.parseInt(request.size)`), 결과를 바로 받지 않는 호출 노드도 문장 전체(`#5 int v = c ? compute() : 0`), 람다 안 호출은 `⟨deferred⟩`.
- 내부 함수 접기(`collapsedHelpers`: 로직 역할이 아니고 전이적으로 로직 역할 호출에 닿지 않는 메서드 → 코드 한 줄 + `VO 값 설정`)는 **단계 보기에서만**. 일반 흐름도는 그 메서드들도 상자로 그린다(빠진 코드 없음). 값 조회 — 결과를 변수에 받는데 본문을 전개하지 않은 source 메서드(로직 역할·VO·Util 제외, 예 상위 클래스 `checkLogin`) — 는 바인딩 블록의 한 줄(`FlowchartGenerator.fetch`). 상위 클래스 메서드를 일부러 전개하지 않는다(과거에 시도했다가 checkLogin 내부 if 가 로직처럼 보여 되돌림).
- return/throw 는 메서드 박스 안 흐름 끝(실선, 분기 라벨 Yes/No), 처리되지 않은 throw 만 `-.->` `M*_uncaught`. (박스 밖 '이탈' 배치를 시도했으나 Mermaid 는 노드 좌표·박스 크기를 지정할 수 없어 dagre 는 끝으로 밀어내고 ELK 도 원하는 '오른쪽 배치'가 안 돼 되돌림.) 레이아웃 `output.layout` 기본 dagre, 옵션 elk(`MermaidGraph.init(layout)`).
- 코드 줄(#n …)이 들어가는 노드(class `codeline`·req·bind·var·state·log·depth·external·unresolved·cycle·fin·exc·check·post)는 `themeCSS` 로 왼쪽 정렬, `wrappingWidth` 2400(자동 줄바꿈 거의 없음), 오른쪽 여유 `padding-right: 3em` + 모든 `foreignObject { overflow: visible }` — Mermaid 는 1배율에서 글자 폭을 재므로 확대·다른 글꼴로 보면 긴 줄 끝이 잘렸다(사용자 지적, mmdc `-s 3` 렌더링으로 확인). class 이름에 Mermaid 예약어(`call`, `click`, `end` …) 금지 — `MermaidGraph.RESERVED_CLASS` 로 검증(`call` 을 썼다가 파서 오류 난 적 있음).
- 문장 단위 바인딩: `CallSiteInfo.statement/statementKind/statementLocation`(감싸는 변수 선언·대입 ExpressionStmt, 주석 제외 `parser.Code.text`). 흐름도 `statementGroup`: 같은 문장의 호출에 로직 역할·세부 메서드·바인딩 코드가 없고 요청 읽기뿐이 아니며 단순 `x = call()` 이 아니면 문장 노드 1개(`#a–b 문장`, class bind, 나머지 호출·그 문장의 변수 변경은 alias). 단계 보기도 문장 단위 step, 판정 3c: 유틸·조회만 쓰는 선언 = 바인딩(3a 요청 컨텍스트 조회·3b 계산 메서드 다음). 줄 나누기는 `&&`/`||` 앞도 강한 자리.
- return/throw 식 안의 호출은 리프 노드에만(별도 호출 노드 없음, `inLeaf` + alias). 세부 박스로 가는 호출(`return errorView(…)`)도 노드를 따로 만들지 않고 리프 노드에서 `==>`(예전엔 같은 코드의 호출 노드 + 간선이 하나 더 생겨 사용자 지적). try 본문에 그릴 노드가 없으면(외부 호출뿐) `ControlBlock.bodyText`(try 본문 원문)를 `BODY_T*` 노드 하나로 그려 흐름이 try 를 지나가게.
- 인자 안의 호출(`CallSiteInfo.inArgument`, 깊이 무관 — `log.error("…" + util.x(req))` 의 util.x, `svc.f(vo.getX())` 의 getX)은 바깥 호출 노드의 코드에 보이므로 따로 그리지 않음(`argumentOnly`). 예외: 세부 박스로 이어지는 호출(==>), 요청 읽기, **바깥 호출이 모델에 없을 때**(라이브러리 제외·생성자 인자 — 그리지 않으면 코드가 사라짐). 모델·트리·YAML(`inArgument`)에는 그대로. 예외로 따로 그린 인자 안 호출의 노드 코드는 **호출식만**(`callCode` 의 `nested` — 바깥 호출이 모델에 있을 때); 문장 전체를 쓰면 `getParameter(req, "x", VasUtil.f(id))` 문장이 두 줄 연속 그려졌다(사용자 지적, `InitialValueAndReaderTest`).
- 조건 노드(`decision`): 조건식·반복 헤더·switch selector 안의 호출은 조건 텍스트에 이미 보이므로 노드로 그리지 않고 조건 노드에 alias(중복 방지, 사용자 지적). 세부 박스로 가는 호출만 ==> 를 위해 노드로 남김. 조건식 안 요청 읽기는 `Labels.blockHeaderLines(r, b)` 가 `request.이름` 으로.
- 노드 줄 수: `output.max-node-lines`(기본 30, 사용자: 100줄 넘는 노드는 안 보임) — 이어 쌓은 코드 블록(`Pending.flush`)은 넘으면 `<id>_p2`, `_p3` … 노드로 이어 나누고(FLOW 로 연결, alias 는 첫 노드), 골격의 합친 노드(`MermaidGraph.collapse(…, maxLines)`)도 한도마다 조각. 줄은 빠지지 않는다. 호출 노드 하나(코드 + SQL·impl 부가 줄)는 쪼개지 않음.
- 긴 줄: `MermaidGraph.wrap(line, output.max-line-length=100 — 사용자: 150·120 은 보기 힘듦, 100 이하)` — 길이는 화면 폭(`MermaidGraph.width`, 한글·CJK 전각 = 2). 강한 자리(쉼표 뒤·` + `/` - ` 앞·`).` 체인 앞) 우선, 없으면 `(` 뒤, 한도를 1/3 이내로 넘는 첫 자리, 리터럴 밖 공백, 앞쪽 리터럴 밖 자리, 리터럴 안 공백, 최후엔 강제 자르기 — 긴 문자열 리터럴·식별자 덩어리도 한 줄이 한도를 크게 넘지 않음(긴 글자로 라벨이 깨지는 문제 방어, `WrapTest`). subgraph 제목·간선 라벨도 같은 한도로 `<br/>`. 이어지는 줄에 표시 없음(예전 `> ` — 사용자: 괜히 오류 나는 것 같아 뺌, 연산자 `>` 와도 헷갈림; HTML 은 이어지는 화면 줄 번호를 노드 `cont` 로 따로 넘김). 렌더 단계에서만(모델·YAML 은 원문).
- 예전 메서드별 분할(`flowchart/`·`flowchart.md`·`output.split-flowchart`·`renderSplit`)은 없앴다(사용자: 구간 분할·골격이 있으니 안 남겨도 됨). 이전 실행이 남긴 `flowchart/*.mmd`·`flowchart.md` 는 `OutputWriter` 가 지운다. 전체 한 장 `flowchart.mmd` 는 유지(검증 기준·SVG `flowchart.svg`).
- 이름이 식인 요청 읽기·Map 키(모델 `?(식)`)는 표시에서 코드 그대로 `request[식]` / `[식] = 값`(`Labels.parameterName`·`mapKey`, 사용자 지적: `?(resultVOMap.getAppId())` 가 깨져 보임). 모델·YAML 은 그대로.
- 메서드별 보기: `output.flowchart-sections`(구간 분할, 기본 true)·`output.flowchart-skeleton`(골격·00-overview, 기본 false — 사용자: 용량 줄이기) 따로 켜고 끔, 끈 보기는 이전 결과(.mmd·합본 .md)도 지움(`OutputWriter.removeParts`), 골격이 꺼지면 구간 파일의 ⌂ 골격 링크도 없음(`FlowchartGenerator.skeleton`). 예전 설명: (`output.flowchart-sections`, 기본 true — 사용자: 200줄 넘으면 가독성 최악): `FlowchartGenerator.renderSectioned` 가 일반 흐름도 그래프를 한 번 만들어 검증한 뒤 떼어 냄. (A) 구간 분할 `flowchart-sections/<메서드>-s<k>.mmd` — 메서드 subgraph 의 그린 순서대로 단위(노드 / `section-size`(라벨 줄 수, 기본 60) 이하 try·Loop 블록 통째 / 넘는 블록은 안쪽)를 묶음, 블록 상자는 구간마다 유지(`MermaidGraph.extractNodes`), 나가는 간선 `go_` ▶ 안내(다른 구간·메서드 파일), 같은 메서드 이전 구간에서 들어오는 간선 `from_` ◀ 안내. 구간 파일을 합치면 메서드의 노드가 정확히 한 번씩(`FlowchartSectionsTest`). (B) 골격 `flowchart-skeleton/<메서드>-skeleton.mmd` — 분기(RECT 아닌 모양)·ret/throw/fin/exc·`==>` 호출 노드만 남기고 FLOW 1:1 로 이어진 같은 subgraph 의 나머지 노드 사슬을 노드 하나로 합침(`MermaidGraph.collapse`) — 라벨 = 사슬의 코드 줄 **전부**(`skeletonLabel`; 예전 `⋯ 코드 n줄 · 상세 구간 k` 생략은 사용자 요구로 없앰). `flowchart-skeleton/00-overview.mmd` = 메서드 노드 + 메서드 간 `==>` 집계. 합본 `flowchart-sections.md`·`flowchart-skeleton.md`(`combinedMarkdown(title, folder, parts)`), SVG 는 `flowchart-svg/sections/`·`skeleton/`. **클릭 이동**(`MermaidGraph.link` → `click id href "…"`, SVG 를 브라우저로 열 때, Mermaid strict 에서도 href 링크는 유지됨): 구간의 ▶/◀ → 그 노드가 든 구간 SVG, `nav_skeleton` ⌂ → `../skeleton/<메서드>-skeleton.svg`; 골격의 합친 코드 노드 → `../sections/<구간>.svg`, ▶ 다른 메서드 → 그 골격, `nav_overview` ⌂ → `00-overview.svg`; overview 메서드 노드 → 골격.
- 색 테마 `output.color-theme`(기본 light — 흰 바탕. 다크를 기본으로 했다가 SVG 색이 깨져 사용자 요구로 되돌림. dark 는 선택): `FlowchartGenerator.DARK_CLASS_DEFS`/`DARK_PHASE_CLASS_DEFS`(어두운 바탕·밝은 테두리·글자, class 이름은 라이트와 같음) + `MermaidGraph.TEXT_COLORS_DARK` + init `"theme": "dark"`. light 는 예전 색. 잘못된 값은 오류(`MermaidGraph.checkTheme`).
- SVG `output.flowchart-svg`(기본 true): 쓴 .mmd 전부(flowchart.mmd·flowchart/*·phase-flowchart.mmd)를 `flowchart-svg/` 에 같은 이름 .svg 로 (`OutputWriter.svgs`, 이전 .svg 정리). Mermaid CLI 필요 — `output.mermaid-cli`(경로) 또는 PATH 의 mmdc, 없으면 경고 후 생략·분석서 부록에 사유. mmdc 설정 `-c` 로 maxTextSize·maxEdges 를 올림(이 둘은 Mermaid 보안 설정이라 %%{init}%% 로는 못 바꿈 — 사이트 렌더링 한계 회피). 배경 dark `#0f172a` / light white. SVG 렌더링이 flowchart.mmd 검증을 겸한다.
- **HTML 뷰어** (`mermaid/HtmlFlowchart`, 설정 `output.flowchart-html` 기본 true — Mermaid 출력 `output.mermaid` 와 따로 켜고 끔, 사용자: Mermaid 가 답답하고 SVG 가 깨져 무한 캔버스, 단 Mermaid 도 선택 가능하게): `FlowchartGenerator.checked(r)`(검증 통과한 `MermaidGraph` = 형식 무관 중간 표현, `render` 도 이걸 씀) → JSON(노드 줄은 `max-line-length` 로 끊은 `[글자, 색 키]` 조각 — 색 규칙 `requestRanges`·`textClass` 공용, class = `MermaidGraph.effectiveClass`(detail 포함), 세부 호출 `goto`, 모델 ID·`파일:라인`) → `flowchart.html` 한 파일(`src/main/resources/flowchart-html/` viewer.html·viewer.js + elkjs 0.11.1 `elk.bundled.js` 내장, EPL-2.0 — 오프라인·설치 불필요, 약 1.6MB). 브라우저가 **메서드 상자마다 따로** ELK layered(INCLUDE_CHILDREN, 좌표 ROOT, 직각 간선)로 배치한 뒤, 진입 메서드를 가운데 두고 호출 깊이(BFS)마다 좌우 바깥 열로 — 방향은 진입 메서드가 직접 부른 메서드면 부른 노드가 진입 상자의 왼쪽 절반이면 왼쪽·아니면 오른쪽(사용자: 좌우 가까운 쪽), 더 깊은 메서드는 처음 부른 메서드의 방향을 따름. 불린 메서드 상자는 부른 노드와 같은 높이(열 안에서는 겹치지 않게 아래로 밀림)에 붙이고 호출 노드 → 상자 점선(detail 색)으로 잇는다(사용자: 링크로 찾아가지 말고 Action·ServiceImpl 주변에 붙이기). 상자는 내용에 맞게 줄임(`trim`). 작은 세부 생략(`output.flowchart-html-hide-nodes`, 기본 10 — 사용자: 다이어그램 기준 10개 이하 세부는 빼기): 노드 수 ≤ 기준인 메서드 상자(진입 제외)는 그리지 않고, 그 메서드가 부르는 메서드는 그 메서드를 부른 노드에서 이어 줄기 유지; 뷰어 상단 체크박스·숫자로 즉석 변경(localStorage), 더블클릭·검색 이동 시 그 메서드만 다시 보임(`forceShown`). 연결 강조(사용자: Service·DAO 와 연결된 노드 하이라이팅, 뎁스끼리 여러 갈래): 노드 상세 패널 `위·아래 / 부른 쪽 ↑ / 부르는 쪽 ↓` — 모델의 CALL 간선만 따라감(위 = 이 메서드를 부른 호출 노드 → 그 메서드를 부른 노드 … 진입까지, 아래 = 이 호출(메서드 머리면 메서드 안 모든 호출)이 부르는 메서드 → 그 안 호출 …, 방문한 메서드는 다시 안 감 — 재귀·공용 DAO 안전), 경로의 호출 노드·메서드 머리·상자·점선만 진하게(나머지 `.dim`), 접힌 상자는 그 상자로·생략된 메서드는 부른 노드로 대신 표시, Esc·`강조 해제`. 연결 강조는 체크 토글(사용자: 라디오 말고 체크처럼) — 노드마다 `연결 강조`·`↑ 부른 쪽`·`↓ 부르는 쪽` 체크, 여러 노드를 켜면 경로 합집합(`hlStarts`). VO 연결(사용자: 어떤 VO 가 들어가고 어떻게 set 하는지만, VO 값을 거슬러 올라가는 과정은 필요 없음. 기존 흐름도 배치·노드·색은 바꾸지 말 것 — 사용자가 검증을 다시 해야 함): 로직 역할(phases.logic-roles) 메서드 호출 노드 ← 그 호출에 넘기는 VO 를 set 하는 **기존 노드** 로 점선을 배치가 끝난 뒤 덧그림(ELK 입력에 넣지 않음 → 위치 불변). **노드 전체가 아니라 set 하는 줄만**(사용자: 주변 줄까지 켜져 이상함 — 노드는 연속 코드를 쌓은 블록이라) 선은 덧그리지 않고 **실행 순서대로 실제 간선**을 강조(사용자: 이전 걸 타지 않고 주변 선을 탐 — 순서): `flowPath` = 호출 노드에서 흐름·예외·호출(==>) 간선을 거꾸로 따라 set 노드까지 가장 짧은 경로(상자에서 나가는 간선은 상자 안 노드에서 온 것으로), 그 간선·노드·메서드 상자를 강조(경로 중간 노드는 테두리 없음), 경로가 없을 때만 예전 덧그린 점선. set 고르기도 실행 순서(`voInputs`): 지나온 메서드는 이 흐름으로 들어오는 호출 줄 앞의 set, 다른 메서드는 지나온 메서드가 그 호출 줄보다 앞에서 (전이적으로) 부를 때만(fixture `EdgeAction.bizVoOrder` — 뒤에 부르는 헬퍼 set 제외). `voRows`: 출발 노드의 줄 중 머리(`#n `·`│ `·`▸ `)를 뗀 텍스트가 set 코드(`bindingLine`)와 같은 줄(+이어지는 화면 줄 `cont`)만 띠(`.rowhl`, 강조색 22%)로, 선은 그 줄 높이에서 출발, 그 노드는 흐리게 하지 않되 테두리 강조 없음(경로 강조로 켜진 노드만 테두리); 맞는 줄이 없으면 노드 전체. **평소엔 숨김, 강조할 때만**(사용자: 선으로 하이라이트만, 체크박스처럼): 호출 노드 상세 패널의 `VO 연결` 체크(`voStarts`, 연결 강조와 별개·여러 개 가능) → 호출 노드·VO 를 set 하는 노드·그 사이 선만 강조색, Esc·`강조 해제`로 끔. 툴바 토글 없음. 내용(`▣` 없이 VO 클래스 변수 + set 코드 그대로 `Labels.bindingLine`, 조건부면 `(조건부)`)은 호출 노드 상세 패널·검색에만. `HtmlFlowchart.voInputs`: 인자 이름 = 호출 메서드의 VO 객체 변수면 그 바인딩(같은 메서드면 호출 줄 이전만, 출발 노드 = 바인딩·setter 호출이 든 노드), 그 메서드의 파라미터면 분석된 호출 위치가 정확히 1개일 때 부른 쪽 인자 변수로 거슬러 감(`objectOf` — Biz 가 받은 vo → Action 의 vo, 지나온 호출 위치 이후의 set 은 제외), 이 호출이 부르는 쪽(`reachable`) 안의 set 은 제외(Action→Biz 호출에 Biz 안 set 이 섞이지 않게), 반환값으로 받은 VO(`cmd = createCommand(…)`)면 그 함수 안 같은 클래스 VO 의 바인딩 + `← createCommand(…) 안에서`(출발 노드 = 그 호출 노드). 단계 보기의 레이어 상자(sg_LAYER_*)는 HTML 에서 그리지 않음(열이 곧 호출 깊이). SVG `<text>` 로 그림(foreignObject 없음). 뒤에서 앞으로 가는 간선(반복 ↻ 등, 그린 순서 기준)은 배치할 때만 뒤집는다 — 안 그러면 반복 본문이 위로 거꾸로 쌓였다. phases 켜면 `phase-flowchart.html`. 끄면 이전 파일 삭제. `HtmlFlowchartTest`(노드 집합 = Mermaid 그래프, 형식 토글).
- 렌더링 첫 줄은 `MermaidGraph.INIT`(`%%{init: …}%%` 노드 padding·wrappingWidth) — 여러 줄 라벨이 잘리지 않게.
- **세부 호출은 선 대신 상자 색** (사용자: 세부 로직이 있으면 밑으로 선을 긋지 말고 박스 색만 다르게): `==>` CALL 간선은 모델·`checkMapping` 에만 두고 렌더링에서 뺀다(`MermaidGraph.showCallEdges`, 기본 false — `00-overview` 만 true). 그 호출 노드는 코드 상자 class 면 `detail`(호박색 상자) 로 칠한다(`MermaidGraph.detail`·`BOX_CLASSES`, 떼어 낸 구간·골격에서도 유지 — 밖으로 나가는 CALL 은 ▶ 안내 노드를 만들지 않음). SVG 에서는 그 노드를 클릭하면 대상 메서드의 구간(첫 구간)·골격으로 이동.
- **반복 표시**: "반복" 글자 없음(사용자 요구) — 영역 제목 `↻ for` / `↻ for-each` / `↻ while` / `↻ do-while`, 본문으로 들어가는 선·되돌아가는 선(`↻`)은 반복 색(`MermaidGraph.LOOP_MARK` 라벨 접두사 → 렌더링에서 떼고 `linkStyle` 파랑), 빠져나가는 선은 라벨 없는 일반 선. 본문 끝이 여럿(if/else if)이면 반복 영역 안 합류 노드 `NEXT_<블록>`(`↻`, class loopnext)로 모아 조건으로 한 번만 되돌아감(사용자 보고: for 안 list.add 다음 if/else if 가 이상해 보임, fixture `EdgeAction.loopAddThenIfElse`).
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
