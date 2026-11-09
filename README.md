# java-flow-analyzer

지정한 Action/Controller의 진입 메서드 하나에서 시작해 Java 소스를 정적 분석하고,
실행 흐름과 데이터 바인딩(요청 값 → VO/DTO 필드)을 정리해 주는 마이그레이션 분석 도구입니다.
분석 대상 프로젝트는 읽기만 하며 컴파일·실행·수정하지 않습니다.

## 요구 사항

- JDK 21 이상 (빌드는 `release = 21` 로 컴파일)
- Gradle Wrapper 포함 — 별도 설치 불필요
- (선택) Mermaid CLI `mmdc` — PATH 에 있으면 flowchart 렌더링 검증, 없으면 생략

## 빠른 실행

```bash
# 샘플 프로젝트 (config/analyzer.yml) — ScpAppListAction
./gradlew run
# → output/ScpAppListAction/

# 예제 멀티 모듈 프로젝트 — 이 저장소 옆 디렉터리에 둔다 (분석 대상은 읽기만 함)
#   Projects/
#     java-flow-analyzer/   ← 분석기 (여기서 실행)
#     java-flow-example/    ← 분석 대상: action/ + lib/
./gradlew run --args="--config config/java-flow-example.yml"                          # → output/OrderRegisterAction/
./gradlew run --args="--config config/java-flow-example.yml --class OrderListAction"  # → output/OrderListAction/
```

## 설정: 기본 조건 + 프로젝트 설정 덮어쓰기

설정은 세 단계로 겹쳐집니다. 뒤의 것이 앞의 것을 덮어씁니다.

1. **기본 조건 (내장)** — [src/main/resources/analyzer-defaults.yml](src/main/resources/analyzer-defaults.yml)
   (분석 깊이, 전개 역할, 바인딩·단계 보기 규칙, 출력 형식 등)
2. **프로젝트 설정 파일** (`--config`) — 이 프로젝트에서 **바꿀 키만** 씁니다
3. **CLI 옵션** — `--source-root`, `--class`, `--method`, `--output`

병합 규칙: `analysis`, `phases`, `output` 같은 맵은 키 단위로 합쳐지고, 목록(`expand-roles`, `request-parameter-rules` …)과 값은 통째로 교체됩니다.
키는 kebab-case 이며 알 수 없는 키(오타)는 오류로 중단합니다.
`--config` 를 주지 않으면: `--source-root` 를 준 경우 기본 조건만, 아니면 `./config/analyzer.yml`(샘플 프로젝트)을 씁니다.

```yaml
# 예: 기본보다 한 단계 더 깊게, 출력은 왼쪽→오른쪽
analysis:
  max-depth: 2
output:
  direction: LR
```

## 다른 프로젝트에서 특정 Action 분석하기

1. 프로젝트마다 작은 설정 파일을 하나 만듭니다. 보통 **소스 경로와 요청 유틸 규칙**만 있으면 됩니다.

   ```yaml
   # my-project.yml
   target:
     source-roots:                       # 모듈이 하나면 source-root: <path>
       - /path/to/project/action/src/main/java
       - /path/to/project/lib/src/main/java

   # 프로젝트 고유 요청 읽기 메서드 = 바인딩 (HttpServletRequest.getParameter* 는 기본 내장)
   request-parameter-rules:
     - class: ParamUtil                     # 클래스를 알면 지정
       methods:
         - { name: getString, name-arg-index: 1, default-arg-index: 2, type: String }
         - { name: getInt,    name-arg-index: 1, default-arg-index: 2, type: int }
     - methods:                             # class 생략 = 메서드 이름만으로 (상속받아 바로 부르는 메서드 등)
         - { name: getVasDeviceIdParameter, name-arg-index: 1 }   # getVasDeviceIdParameter(request, "deviceId")
         - { name: getSystemId }                                  # 이름 인자 없음 → 메서드명에서: systemId
         - { name: getDeviceAbiType, parameter: abiType }         # 이름 인자 없음 → 이름 고정
     - class: StringUtil
       methods:
         - { name: getParam, name-arg-index: 1 }
         - { name: defVal, wraps: 0, default-arg-index: 1 }       # 기본값 래퍼: defVal(getParam(req, "x"), "0")
   ```

   | 키 | 의미 |
   |---|---|
   | `class` | 수신/선언 클래스 단순명 또는 FQN. 생략하면 클래스 무관(같은 이름의 다른 메서드도 잡히니 이름이 고유할 때만) |
   | `name-arg-index` | 파라미터 이름이 들어 있는 인자 위치(0부터). `-1` 이면 전체 조회 |
   | `parameter` | 이름 인자가 없는 메서드의 고정 이름. `name-arg-index` 와 함께 쓸 수 없음 |
   | (둘 다 없음) | 메서드명에서 이름: `getSystemId` → `systemId` |
   | `default-arg-index` | 기본값 인자 위치 (선택) |
   | `wraps` | 기본값 래퍼: 이 위치의 인자가 요청 읽기면 그 값에 기본값을 붙임 → `String x = request.x ?: "0"` 한 줄 (`name-arg-index`·`parameter` 와 함께 쓸 수 없음). commons-lang `StringUtils.defaultIfEmpty/defaultIfBlank/defaultString` 은 기본 내장 |

2. 분석할 Action 만 CLI 로 골라 실행합니다.

   ```bash
   ./gradlew run --args="--config /path/to/my-project.yml --class OrderCancelAction"
   # 진입 메서드는 생략 가능: 매핑 어노테이션(@RequestMapping 등) → public execute() 순으로 찾음
   # 후보가 여러 개면 추측하지 않고 오류 → --method 로 지정
   ./gradlew run --args="--config /path/to/my-project.yml --class SampleController --method defaultResponse"
   ```

   요청 유틸 규칙이 필요 없으면 설정 파일 없이 경로만 줘도 됩니다 (`--config` 없이 `--source-root` 를 주면 기본 조건만 사용).

   ```bash
   ./gradlew run --args="--source-root /path/to/project/src/main/java --class OrderCancelAction"
   ```

- `./gradlew run` 의 상대 경로는 **이 저장소 루트 기준**입니다. 다른 프로젝트는 절대 경로를 쓰세요.
- 결과는 이 저장소의 `output/<클래스명>/` 에 생깁니다(`--output` 으로 변경). 출력 위치가 분석 대상 소스 루트 안이면 실행을 거부합니다.
- 분석 대상 프로젝트는 읽기만 합니다(수정·빌드·실행 없음).

## Response(JSP) 등록하기

Action 이 응답으로 쓰는 JSP(XML 출력)를 **JSP 파일 경로(또는 파일명)로 지정**하면, 그 JSP 의 XML 항목과 값 출처를 분석해
`spec.md` 의 `## 2. Response 정의` 와 `response.md` 를 만듭니다. 어느 JSP 인지는 분석기가 추측하지 않으므로 Action 마다 직접 정합니다.

1. **CLI 로 (Action 마다 다르므로 보통 이쪽)**

   ```bash
   # JSP 파일 경로를 그대로 (절대 경로, 또는 이 저장소 루트 기준 상대 경로)
   ./gradlew run --args="--config /path/to/my-project.yml --class AppDetailAction --jsp /path/to/project/WebContent/WEB-INF/jsp/vas/appDetail.jsp"
   # 파일명만 줘도 됨 — 웹 루트(WEB-INF 가 있는 폴더)에서 찾음
   ./gradlew run --args="--config /path/to/my-project.yml --class AppDetailAction --jsp appDetail.jsp"
   # JSP 가 여러 개면 --jsp 를 반복
   ./gradlew run --args="--config my-project.yml --class AppDetailAction --jsp appDetail.jsp --jsp error.jsp"
   ```

2. **설정 파일로**

   ```yaml
   response:
     jsp:
       - /path/to/project/WebContent/WEB-INF/jsp/vas/appDetail.jsp   # JSP 파일 경로
       # - appDetail.jsp                 # 또는 파일명·끝부분 경로 (vas/appDetail.jsp) → 웹 루트에서 찾음
     web-roots:                          # 선택: WEB-INF 가 있는 폴더. 비우면 프로젝트 폴더에서 자동으로 찾음
       - /path/to/project/WebContent
   ```

- 실제 있는 파일 경로면 그 파일을 그대로 씁니다(`/WEB-INF/…` include 는 위쪽 `WEB-INF` 의 부모 폴더 기준).
- 파일명이면 웹 루트(`WEB-INF` 가 있는 폴더) 아래에서 이름 끝이 일치하는 것을 찾습니다.
  - 같은 이름이 여러 개면 고르지 않고 후보를 보여 줍니다 → `vas/appDetail.jsp` 처럼 앞 폴더까지 적으세요.
  - 못 찾으면(`NOT_FOUND`) 이름을 확인하거나 `web-roots` 를 지정하세요.
- `--jsp` 를 주면 설정 파일의 `response.jsp` 를 대체합니다. 둘 다 없으면 Response 분석을 하지 않습니다(2장·`response.md` 없음).
- 값 출처: `${x}` 는 Action 이 넘긴 `request.setAttribute("x", …)`(·`model.addAttribute`·`mav.addObject`), `${param.x}` 는 요청 파라미터,
  `c:forEach` 변수는 `items` 의 원소, `c:set` 은 그 값. 넣는 곳을 못 찾으면 "Action 에서 넣는 곳 없음".
  `<%@ include %>`·`jsp:include`·`c:import` 는 그 자리에 펼쳐 읽습니다. 스크립틀릿은 `response.md` 에 확인 항목으로 남습니다.

`spec.md` 예:

```markdown
## 2. Response 정의

| 항목 | 필수 | 반복 | 값 |
|---|---|---|---|
| response/country | Y |  | country |
| response/lang | Y |  | request.lang |
| response/apps/app@id | N | Y | appList[] |
| response/version | Y |  | 고정 1.0 |
```

필수 = `c:if`·`c:choose` 안이면 N, 반복 = `c:forEach` 안이면 Y.

## CLI 옵션

| 옵션 | 설명 |
|---|---|
| `--config <path>` | 프로젝트 설정 파일 (기본 조건 위에 덮어씀) |
| `--source-root <path>` | 분석 대상 소스 루트. 여러 번 지정 가능(멀티 모듈), 지정하면 설정 파일의 루트를 대체 |
| `--class <name>` | 분석할 Action/Controller (단순명 또는 FQN — 같은 이름이 여러 개면 FQN) |
| `--method <name>` | 진입 메서드 (생략 시 추론) |
| `--output <dir>` | 출력 디렉터리. 결과는 `<dir>/<클래스명>/` |
| `--jsp <path>` | Response 로 분석할 JSP 파일 경로 또는 파일명 (여러 번 지정 가능, 설정 `response.jsp` 대체) |

## 출력 파일

`<output>/<클래스명>/` 아래에 생성됩니다.

| 파일 | 내용 |
|---|---|
| `analysis.yaml` | 분석 결과 원본(Source of Truth). 나머지 파일은 모두 이 모델에서 생성 |
| `spec.md` | 간결한 명세: 1. Request Param 정의(파라미터·타입·필수·기본값·설명), 2. Response 정의(JSP 지정 시) |
| `response.md` | JSP 지정 시: XML 구조, 값마다 출처 사슬·JSP 위치, Action → JSP attribute 표 |
| `call-tree.txt` | 진입 메서드부터의 호출 트리 (분기·예외·요청 파라미터·바인딩 요약 포함) |
| `flowchart.mmd` | Mermaid 흐름도 (메서드별 subgraph). try 는 블록 1개로 묶고 예외는 블록에서 catch 노드로 한 번에(`try -.->|ValidationException| catch …`), finally 는 그 뒤 노드 1개. 레이아웃은 Mermaid 기본(dagre), 직각 간선을 원하면 `output.layout: elk`(뷰어가 ELK 를 지원해야 함). 세부(박스)는 Service 쪽으로 넘어가는 흐름만 그림(Action → Service → Service·Biz·DAO·Mapper). Action 의 내부 함수(`readItems`, `preparePayment`, `calculateTotal` …)와 `checkLogin` 같은 값 조회는 코드 한 줄(`#15 items = readItems(request)` / `VO 값 설정 · OrderItemVO`) — 안에서 읽는 요청 값·VO 매핑은 `mapping.md` 에 그대로. 연속된 요청 값 읽기 → `요청 파라미터` 노드, 연속된 setter·builder·생성자 → `VO 값 설정` 노드 (아래 예시) |
| `flowchart/` | 큰 흐름도 나눠 보기: `00-overview.mmd`(메서드 박스 + 호출 관계) + 메서드마다 한 파일(`01-M1-Xxx.execute.mmd` …). 다른 메서드로 넘어가는 호출은 `▶ 대상 — 파일명` 노드로 끝남 (`output.split-flowchart`, 기본 켜짐) |
| `flowchart.md` | 위 파일들을 합친 문서: 목차 + 전체 구조 + 메서드별 다이어그램(```` ```mermaid ```` 블록마다 따로 렌더링) |
| `mapping.md` | 요청 파라미터 → 객체 필드 바인딩 명세, 필드 커버리지, **변수 상태 변화** 이력 |
| `phase-flowchart.mmd` | **단계 보기** 흐름도: ① 바인딩(요약 1개) › ② 체크(조건 → 결과) › ③ 로직(상세) › ④ 후처리(요약) |
| `phase-summary.md` | 단계별 요약: 실제 실행 순서, 요청 파라미터·객체, 체크 목록, 로직 호출 트리, 요소별 판정 근거 |
| `analysis.md` | 요약 보고서 (대상, 통계, 미해결 항목 등) |

흐름도·보고서의 코드와 식은 길어도 줄이지(`…`) 않고 전부 씁니다. 바인딩·값 변경은 **코드 형식 `대상 = 값`** 으로 한 줄에 하나씩 씁니다
(`#n` = 메서드 안 호출 순번, call-tree·analysis.yaml 과 같은 번호. 요청 읽기 호출은 `request.이름 ?: 기본값` 으로 줄임).

```
요청 파라미터                                VO 값 설정 · OrderVO
#2 String mode = request.mode ?: "NEW"       #6 memberId = member.getMemberId()
#3 String orderNo = request.orderNo          #7 orderType = mode
#4 boolean agree = request.agreeTerms ?: false   #27 totalAmount = total  ⟳V1

#1 MemberVO member = checkLogin(request)     #21 long total = calculateTotal(items) · V1 1/3

if (couponCode != null                       for (OrderItemVO item
&& !couponCode.isEmpty())                    : items)
```

- 다른 호출의 인자 안에 있는 호출(`LOG.error("…" + VasUtil.getFullUrl(request))` 의 `getFullUrl`)은 흐름도에 그리지 않음 — Service·DAO 세부로 이어지는 호출과 요청 읽기는 예외. 조건식 안의 호출도 조건 노드에만 표시.
- 로직(Service·DAO…) 호출이 없는 변수 선언·대입 문장은 호출마다 나누지 않고 **문장 통째로 바인딩 한 줄** (`#4–5 boolean f = ODSUtil.isQAStore(shopID) && StringUtil.isNotEmpty(sdkVer) && "1".equals(pd)`), 단계 보기에서도 바인딩. 소스 주석은 표시하지 않음.
- 모든 줄은 `타입 대상 = 값` 코드 한 줄 (타입 = 받는 변수의 선언 타입 / VO 필드 타입 / 요청 규칙의 type). 역할·접근제어자·Depth·depth-limit 같은 꼬리표는 쓰지 않고 색으로만 구분.
- VO 값 설정은 변수명·타입 없이 `프로퍼티 = 값` (클래스는 노드 제목). 타입은 값을 받아 오는 줄(요청 파라미터·값 조회·호출 결과)에만. 한 노드에 객체가 둘 이상이면 `· 클래스` 줄로 구분.
- 조건식은 최상위 `&&`/`||` 앞에서 줄바꿈(연산자가 줄 맨 앞), for-each 는 `:` 앞뒤 두 줄.
- 한 줄이 `output.max-line-length`(기본 100자)를 넘으면 쉼표 뒤·`+`/`-` 앞·`).` 체인 앞(없으면 `(` 뒤)에서 끊고 이어지는 줄은 `> ` 로 시작. 문자열 리터럴 안은 끊지 않음. `0` 이면 끊지 않음.
- 코드 줄 노드는 왼쪽 정렬.

### 변수 상태 변화

선언 뒤에 값이 바뀌는 지역 변수·파라미터(`total -= usePoint`, `size = MAX_PAGE_SIZE`, `retry++` …)를 따로 추적합니다
(`analysis.yaml` 의 `variables`, 설정 `analysis.include-variable-changes`). 필드, for 헤더 변수(`i++`), 향상된 for 변수,
한 번만 대입되는 변수는 대상이 아닙니다.

| 어디에 | 표현 |
|---|---|
| `flowchart.mmd` | 값 변경 = 자바에서 타입 없이 다시 대입하는 줄(`total -= usePoint`, `size = 100`, `retry++`). 바뀌는 위치(조건 분기 안)에 `값 변경` 노드 `total -= usePoint · V1 2/2` — 변경만 센 순번/전체 변경 수, 식 안 호출 뒤에 놓임. 선언(`long total = calculateTotal(items)`)은 값 변경이 아니라 원래 코드 줄에 `· V1` 표시만. 호출 결과를 그대로 대입하면(`orders = service.find…()`) 호출 노드에 `· V2 2/3`. 타입은 선언 줄에만 |
| VO 값 설정 노드 | 넣는 값이 바뀌는 변수면 `order.totalAmount = total  ⟳V1` — 실행 경로에 따라 값이 다를 수 있다는 표시 |
| `call-tree.txt` | `≔ total -= usePoint … (VC3)` 줄 |
| `mapping.md` | 변수별 전체 이력 표: 선언 → 변경 …, 출처·조건·위치, 그 변수를 쓰는 바인딩 |
| 단계 보기 | 첫 로직 전, 요청 값에서 온 변수를 바꾸는 코드는 **바인딩(요청 값 보정)** 으로 판정하고 ① 노드에 `값 변경` 으로 표시 |

### 단계 보기 (바인딩 › 체크 › 로직 › 후처리)

`flowchart.mmd` 는 문장 순서대로 모든 흐름을 그립니다. `phase-flowchart.mmd` 는 진입 메서드의 최상위 요소를 단계로 판정해 바인딩·체크를 요약하고 로직만 상세히 그립니다.

레이어로 묶어 그립니다: **Action 레이어**(진입 메서드의 ①②③④) → **Service · Biz 레이어** → **DAO · Mapper 레이어**.
세부(subgraph)는 Service·Biz·DAO·Mapper 메서드만 그리고, Action 쪽 내부 함수 중 Service·DAO 호출에 닿지 않는 것
(예: `calculateTotal`)은 코드 한 줄로만 표시합니다.

| 단계 | 판정 근거 |
|---|---|
| 바인딩 | 요청 값 읽기, VO/Map 바인딩 코드·바인딩 메서드, 값 조회 — 결과가 뒤 바인딩의 출처로 쓰이는 호출이나 요청 객체를 넘기는 조회(`member = checkLogin(request)`, `userId = getLoginUserId(request)`). 값 조회는 바인딩 한 줄로 끝(내부·체크로 들어가지 않음) |
| 체크 | `check-roles` 클래스(Validator) 호출, 분기 끝이 throw(진입 메서드는 return 포함)인 if/switch, `check-method-names` 메서드 |
| 로직 | `logic-roles` 클래스(Service·Biz·DAO·Mapper…) 호출과 이를 포함한 분기, 로직 역할 호출 없는 계산 메서드 |
| 후처리 | 마지막 로직 뒤의 요소 (결과 VO 작성, 알림 등) |

실제 코드는 단계가 섞여 있으므로(예: 결제 정보 바인딩이 쿠폰 계산 뒤) 첫 로직 전은 ①②로 모으고,
로직 사이에 끼어 있는 바인딩·체크는 ③ 안에 요약 노드로 남깁니다. 판정 규칙은 설정 `phases` 로 바꿀 수 있고,
요소별 판정 근거는 `phase-summary.md` 맨 아래 표에 있습니다.

실행 후 콘솔에 클래스·메서드·호출·바인딩 수와 미해결 호출 수가 출력됩니다.
해석하지 못한 호출은 추측하지 않고 `UNRESOLVED` / `PARTIALLY_RESOLVED` / `AMBIGUOUS` 로 사유와 함께 남습니다.

## 테스트

```bash
./gradlew test
```
