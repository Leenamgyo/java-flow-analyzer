# java-flow-analyzer — Claude 작업 가이드

지정한 Action/Controller **하나**의 진입 메서드에서 시작해 Java 소스를 정적 분석하고,
**하나의 공통 모델**로 실행 흐름 + **데이터 바인딩 명세**(어떤 값이 어느 객체 필드로 가는지)를 만드는 마이그레이션 분석 도구.
출력: `analysis.yaml`(Source of Truth) · `flowchart.mmd` · `mapping.md` · `analysis.md` · `call-tree.txt`.
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
./gradlew run                                    # config/analyzer.yml 로 실행 → output/<Class>/
./gradlew run --args="--config config/analyzer.yml --class CaseAction --method throwCases"
#   옵션: --config --source-root --class --method --output
```

- 설치된 JDK 는 26 뿐이라 `options.release = 21` 로 Java 21 타깃 컴파일한다(toolchain 미사용).
- 샘플 원본 프로젝트(분석 대상, 컴파일하지 않음): `src/test/resources/sample-project/src/main/java`
  - `ScpAppListAction` — 실전형 시나리오, `CaseAction` — 테스트 케이스별 진입 메서드, `AppController` — Spring 어노테이션
- 예제 프로젝트: `example/legacy-shop` (Struts Action + VO/DTO + Service/Biz/DAO/Mapper/Util), 설정 `example/analyzer.yml`
  - `./gradlew run --args="--config example/analyzer.yml"` → `example/output/OrderRegisterAction/` (요청 파라미터 25개 → VO/DTO/Command 6종 + builder·생성자)
  - `--class OrderListAction` → `example/output/OrderListAction/`
- Mermaid CLI(`mmdc`)가 PATH 에 있으면 렌더링 검증, 없으면 생략하고 analysis.md 에 기록.

## 구조

```
analyzer/
  Main.java              CLI, 모델 무결성 검증 후 출력
  FlowAnalyzer.java      오케스트레이터: BFS 로 메서드 본문 분석, Depth/Expansion/cycle 결정
  config/                analyzer.yml 바인딩(kebab-case, 알 수 없는 키는 오류)
  parser/                ProjectSources(파일 인덱스+지연 파싱+심볼솔버), EntryPointLocator, TypeNames
  classifier/            ClassRoleClassifier
  resolver/              CallResolver(심볼솔버 → 선언타입 폴백), TypeLookup(import/변수/필드 타입), ImplementationFinder(source 내 유일 구현)
  controlflow/           MethodBodyWalker(블록+호출 순서), WalkContext, ExceptionTypeMatcher
  parameter/             RequestParameterExtractor(Legacy + 사용자 규칙 + Spring 어노테이션)
  binding/               BindingCollector(객체 O* · 바인딩 B*), SourceTracer(값 → 최초 출처+변환), ConditionText, RequestSpecBuilder(RQ*)
  model/                 공통 모델 + ModelValidator
  serializer/            AnalysisYamlWriter + AnalysisSchema(내장 스키마 검증), MappingReportWriter(mapping.md),
                         AnalysisReportWriter(analysis.md), CallTreeWriter, FlowView(흐름 보기 규칙), Labels(표시 문자열), OutputWriter
  mermaid/               MermaidGraph(중간표현+검증), FlowchartGenerator, MermaidCli
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
- 1순위 JavaSymbolSolver. 실패 시 선언부에 명시된 타입(파라미터/지역변수/필드/import/같은 패키지/java.lang)으로 **이름+인자 수** 매칭. 후보 2개 이상이면 `AMBIGUOUS`. 근거는 `resolution-basis` 에 남긴다.
- source-root 밖 타입(예: `HttpServletRequest`)은 시그니처를 모르므로 `PARTIALLY_RESOLVED` + `signature-resolved: false`, 시그니처는 `getParameter(?)`. 파라미터 타입을 지어내지 않는다.
- `var`, 체인 호출 결과, 선언 없는 이름 → `UNRESOLVED`.
- 솔버가 반환한 AST 는 다른 파서 인스턴스 소유이므로 `ProjectSources.mapToOwnAst` 로 같은 위치의 자체 AST 로 매핑해서 쓴다.
- `include-external-library-calls: false` 면 origin=LIBRARY 호출은 모델에 넣지 않고 개수만 센다. 단 Request Parameter 추출은 필터 이전에 수행.

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

**흐름 보기 (`FlowView`, 트리·Mermaid 공용)**
- 흐름 중심은 호출·분기·예외. 바인딩 코드(setter/builder/put 호출과 그 인자 안의 전개되지 않은 호출)는 흐름에서 빼고, **(객체, 메서드)당 요약 1개**(`BIND_<O>_<M>`: `N fields assigned · N conditional · N type conversion`)를 둔다. **요약은 call-tree 에만** — flowchart 에는 바인딩 노드를 전혀 그리지 않는다(노드 수 폭증 방지, 상세는 mapping.md).
- 요약 위치 = 그 메서드에서 해당 객체 바인딩을 모두 감싸는 가장 가까운 공통 컨테이너(then/else 에 흩어지면 그 if 앞).
- 바인딩 메서드(본문이 바인딩 + 최상위 return 뿐, 예 `toSearchVO`)는 그리지 않고 호출 위치에 요약(`BIND_<O>_<CALL>`).

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
- if/else-if/switch/반복문 = 조건 노드(◇) + 분기 간선(`Yes`/`No`, case 라벨, `loop`/`next`/`done`). else/case 는 노드 없이 간선으로. try → catch 는 `-.->|예외타입|`, finally 로 합류.
- 흐름 연결은 "열린 끝" 목록으로 계산(분기 끝들이 다음 요소로 합류). return/throw 뒤는 끊김.
- `-->` 정상 흐름, `==>` 본문을 전개한 호출(EXPANDED/SHARED/CYCLE → 메서드 시작 노드), `-.->` 예외 흐름.
- 라벨 이스케이프는 `#` 먼저 → `#35;` `#quot;` `#lt;` `#gt;` `#124;`. 렌더 전 `MermaidGraph.validate()` + `FlowchartGenerator.checkMapping()` 실패 시 예외.

## 작업 규칙

- 단계마다 `./gradlew test` 통과 후 다음 단계로. 새 기능은 `sample-project` 에 fixture 를 추가하고 테스트로 고정한다.
- 출력 필드를 바꾸면 AnalysisYamlWriter · AnalysisSchema · MappingReportWriter · AnalysisReportWriter · CallTreeWriter · FlowchartGenerator · ModelValidator 를 함께 맞춘다(같은 모델, 같은 ID).
- 표시 문자열 규칙은 `serializer/Labels` 한 곳에서 관리(트리와 Mermaid 공용).
- 분석 결과에는 항상 근거 위치(`location`: 파일·라인·컬럼)를 남긴다.

## 구현 현황

완료(테스트 통과):
- v1 흐름 분석 — 대상/진입 메서드 식별(오버로딩·모호성 거부·매핑 어노테이션 추론), 접근 제어자 4종+인터페이스 암묵 제어자, 역할 분류(어노테이션>상위타입>Suffix, 근거·충돌 기록), Depth/동일 클래스 확장/중복/재귀·상호재귀, if·switch·loop·try/catch/finally·try-with-resources·return·throw, Legacy·사용자 규칙·Spring 파라미터, 역할 기반 전개(`expand-roles`)·유일 구현 후보 연결(`link-single-implementation`).
- v2 바인딩 — setter·builder·생성자·필드 대입·Map.put·copyProperties 수집, 출처/변환/조건/기본값/재대입 추적, 필드 커버리지, 다중 할당 최종 값(mapping.md), catch 처리 방식(로그·오류 응답·재발생·반환), 호출 인자·반환값 사용.
- 출력 — analysis.yaml(+스키마 검증)·flowchart.mmd(TD, 메서드별 subgraph, 바인딩 노드 없음)·mapping.md·analysis.md(SPEC-v2 §8 구성)·call-tree.txt. 생성 flowchart 는 Mermaid 11.4.1 파서로 문법 확인(임시 검증, 프로젝트에는 mmdc 연동만 존재).

미구현(후속): **마이그레이션 비교(SPEC-v2 §7, comparison.md)**, `class-diagram.mmd`, 고급 예외 전파(호출 대상 throws 기반), Spring 동적 호출/DI 구현체 해석, 반환값을 거친 객체 정체성·값 추적, 메서드 참조(`::`)·익명/로컬 클래스 본문, switch fall-through 흐름, `case ... when` 가드.
