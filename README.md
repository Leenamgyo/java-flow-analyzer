# java-flow-analyzer

Java 소스를 **정적 분석**해서 실행 흐름, Service·DAO 의존, 데이터 바인딩(요청 값 → VO 필드)을 정리하는 마이그레이션 분석 도구입니다.
분석 대상 프로젝트는 **읽기만** 합니다(컴파일·실행·수정 없음). 확정할 수 없는 것은 추측하지 않고 `UNRESOLVED` 등으로 사유와 함께 남깁니다.

| 하고 싶은 것 | 명령 | 결과 |
|---|---|---|
| **Action 하나** 분석 (흐름·명세·바인딩) | `./gradlew run --args="… --class OrderAction"` | `output/OrderAction/` |
| **클래스 전체**를 흐름도 한 장으로 (Util 등) | `./gradlew runClass --args="… --class ContentUtil"` | `output/class/ContentUtil/flowchart.html` |
| **Util 기능 목록**을 계속 관리 | `./gradlew run --args="… --class ContentUtil --all-methods"` | `catalog/ContentUtil.yml` |
| **이 메서드를 어느 Action 이 쓰나** (공통화) | `./gradlew query --args="who-uses ContentUtil.getInfo"` | 콘솔 (`--md`·`--csv` 로 저장) |

**목차**

1. [시작하기](#시작하기)
2. [Action 분석](#1-action-분석--gradlew-run)
3. [전체 클래스 흐름도](#2-전체-클래스-흐름도--gradlew-runclass)
4. [Util 기능 목록 (카탈로그)](#3-util-기능-목록-카탈로그----all-methods)
5. [메서드 사용처 (공통화)](#4-메서드-사용처-공통화--gradlew-query)
6. [흐름도 뷰어 사용법](#흐름도-뷰어-flowcharthtml-사용법)
7. [흐름도 읽는 법](#흐름도-읽는-법)
8. [분석 규칙](#분석-규칙)
9. [설정](#설정)
10. [CLI 옵션](#cli-옵션)
11. [테스트](#테스트)

---

## 시작하기

**요구 사항**

- JDK 21 이상 (`release = 21` 로 컴파일). Gradle Wrapper 포함 — 따로 설치할 것 없음
- (선택) Mermaid CLI `mmdc` — 있으면 `.mmd` 를 SVG 로도 만듦, 없으면 경고 후 생략

**샘플로 바로 실행**

```bash
./gradlew run                                 # 샘플 Action → output/ScpAppListAction/
./gradlew runClass --args="--class ProductUtil"   # 샘플 Util 전체 → output/class/ProductUtil/flowchart.html
```

**내 프로젝트에 쓰기** — 설정 파일 하나에 소스 경로만 적으면 됩니다 ([설정](#설정)).

```yaml
# config/my-project.yml
target:
  source-roots:                     # 모듈이 하나면 source-root: <path>
    - /path/to/project/action/src/main/java
    - /path/to/project/lib/src/main/java
```

> 상대 경로는 **이 저장소 루트 기준**입니다. 다른 프로젝트는 절대 경로를 쓰세요. 출력 위치가 분석 대상 소스 안이면 실행을 거부합니다.

---

## 1. Action 분석 — `./gradlew run`

Action/Controller 의 진입 메서드 **하나**에서 출발해 Service·Biz·DAO 까지 따라갑니다.

```bash
./gradlew run --args="--config config/my-project.yml --class OrderCancelAction"
./gradlew run --args="--config config/my-project.yml --class SampleController --method defaultResponse"
```

- 진입 메서드를 생략하면 매핑 어노테이션(`@RequestMapping` 등) → `public execute()` 순으로 찾습니다. 후보가 여럿이면 오류 → `--method` 로 지정.
- 설정 파일 없이 경로만 줘도 됩니다: `--source-root /path/to/src/main/java`

### 출력 (`output/<클래스>/`)

| 파일 | 내용 |
|---|---|
| `flowchart.html` | **흐름도 뷰어** — 브라우저로 열기만 하면 됨 ([사용법](#흐름도-뷰어-flowcharthtml-사용법)) |
| `spec.md` | 공개용 간결한 명세: 1. Request Param 정의 · 2. Response 정의(JSP 지정 시) |
| `vo.md` | 내부용 VO 목록: VO 클래스 · 변수명 · 사용 위치 + VO 마다 쓰는 프로퍼티(이름·타입·set/get) |
| `analysis.md` | 분석서: Request Parameter · Mapper SQL(id·테이블) · 관련 클래스 · 바인딩 · 예외 |
| `mapping.md` | 요청 파라미터 → 객체 필드 바인딩, 필드 커버리지, 변수 상태 변화 이력 |
| `call-tree.txt` | 진입 메서드부터의 호출 트리 |
| `analysis.yaml` | 분석 결과 원본 (나머지 파일은 모두 이 모델에서 생성) |
| `flowchart.mmd` · `flowchart-sections/` · `flowchart-skeleton/` | Mermaid 흐름도: 전체 한 장 · 메서드별 구간 분할 · 골격(기본 꺼짐) |
| `phase-flowchart.*` · `phase-summary.md` | **단계 보기**: ① 바인딩 › ② 체크 › ③ 로직 › ④ 후처리 |
| `response.md` | JSP 지정 시: XML 구조와 값마다 출처 |

흐름도 형식은 따로 켜고 끕니다: `output.flowchart-html`(HTML, 기본 켜짐) · `output.mermaid`(Mermaid 파일, 기본 켜짐).

### 요청 값 읽기 규칙 (프로젝트 고유 유틸)

`request.getParameter*` 는 기본 내장입니다. `ParamUtil.getString(request, "x", "def")` 같은 프로젝트 유틸은 설정에 등록하면 요청 파라미터로 잡힙니다.

```yaml
request-parameter-rules:
  - class: ParamUtil
    methods:
      - { name: getString, name-arg-index: 1, default-arg-index: 2, type: String }
      - { name: getInt,    name-arg-index: 1, default-arg-index: 2, type: int }
  - methods:                                  # class 생략 = 메서드 이름만으로
      - { name: getSystemId }                 # 이름 인자 없음 → 메서드명에서: systemId
      - { name: getDeviceAbiType, parameter: abiType }
  - class: StringUtil
    methods:
      - { name: defVal, wraps: 0, default-arg-index: 1 }   # 기본값 래퍼: defVal(getParam(req, "x"), "0")
```

| 키 | 의미 |
|---|---|
| `class` | 수신/선언 클래스 단순명 또는 FQN. 생략하면 이름만으로 (이름이 고유할 때만) |
| `name-arg-index` | 파라미터 이름이 든 인자 위치(0부터). `-1` = 전체 조회 |
| `parameter` | 이름 인자가 없는 메서드의 고정 이름 |
| (둘 다 없음) | 메서드명에서: `getSystemId` → `systemId` |
| `default-arg-index` | 기본값 인자 위치 |
| `wraps` | 기본값 래퍼: 이 위치 인자가 요청 읽기면 그 값에 기본값을 붙임 (commons `StringUtils.defaultIfEmpty` 등은 내장) |

### Response(JSP) 등록

Action 이 응답으로 쓰는 JSP 의 **파일명만 주면** 원본 프로젝트의 웹 루트(`WEB-INF` 가 있는 폴더)에서 찾아 분석합니다. 어느 JSP 인지는 직접 정합니다(추측하지 않음).

```bash
./gradlew run --args="--config my-project.yml --class AppDetailAction --jsp appDetail.jsp"
```

| 상황 | 해결 |
|---|---|
| 같은 이름이 여러 개 (`AMBIGUOUS`) | 앞 폴더까지: `--jsp vas/appDetail.jsp` |
| 못 찾음 (`NOT_FOUND`) | 웹 루트 지정 `response.web-roots: [/path/to/WebContent]` 또는 파일 경로 그대로 |

`spec.md` 의 `## 2. Response 정의` 에 Content-Type 과 응답 XML 이 JSP 코드 그대로 들어갑니다(반복·조건은 `<!-- 반복: … -->` 주석으로 범위 표시, include 는 펼침). 값마다 Action 의 어느 attribute 에서 왔는지는 `response.md`.

---

## 2. 전체 클래스 흐름도 — `./gradlew runClass`

`ContentUtil` 같은 클래스의 **메서드 전부**를 진입으로 **한 번에** 분석해 흐름도 한 장으로 만듭니다. Action 분석과 실행 명령·결과 폴더가 따로이고, 분석 규칙은 같습니다(빈 이름·리플렉션·DAO·`[⛁ DB]` 모두).

```bash
./gradlew runClass --args="--config config/my-project.yml --class ContentUtil"
```

- 결과는 `output/class/<클래스>/flowchart.html` **하나**입니다 (VO·spec 등은 만들지 않음).
- 메서드마다 시작 상자가 왼쪽 열에 소스 순서로 세로로 놓이고, 각자 부르는 Service·DAO 는 **모두 오른쪽 열**의 부른 줄 높이에 붙습니다. 여러 메서드가 같이 쓰는 Biz·DAO 는 **상자 하나로 공유**합니다.
- 클래스 자신의 메서드도 `output.flowchart-exclude` 로 뺄 수 있습니다(`"ContentUtil.setiAppCache"` — 출발점에서 빠지고, 그 메서드만 쓰던 내부 메서드도 함께 빠짐).
- 처음 열면 **Util 클래스의 메서드 상자는 모두 접혀** 있습니다(메서드 목록과 호출 관계만 보임). 상자 제목 클릭 또는 `모두 펴기`로 펼칩니다.

### 카탈로그 적용 `--apply`

카탈로그 파일(`catalog/<클래스>.yml`)에 숨길 메서드를 손으로 적고 `--apply` 로 실행하면, 흐름도를 처음 열 때부터 숨긴 상태입니다.

```yaml
# catalog/ContentUtil.yml (최상위에 추가)
hidden:
  - ProductInfoBizImpl.getInfo      # 메서드 하나
  - ProductDAO                      # 클래스의 메서드 전부
  - "*CacheBiz"                     # * 패턴 (YAML 에서 * 로 시작하면 따옴표)
```

```bash
./gradlew runClass --args="--config config/my-project.yml --class ContentUtil --apply"
```

- 맞는 메서드가 없는 항목(오타 등)은 경고합니다. `--apply` 가 없으면 카탈로그를 보지 않습니다.
- 카탈로그 목록이 기준이고, 화면에서 바꾼 숨김(브라우저에 기억)보다 우선합니다.

---

## 3. Util 기능 목록 (카탈로그) — `--all-methods`

Util 클래스 **전체**를 기능 목록 YAML 로 정리합니다(메서드 하나 = 기능 하나). 실행마다 지우는 `output/` 과 달리 **`catalog/` 에 계속 관리**합니다 — git 으로 관리하고, 빌드는 건드리지 않습니다.

```bash
./gradlew run --args="--config config/class-survey.yml --class ContentUtil"
# 아무 설정 + --all-methods 도 같음
./gradlew run --args="--config config/my-project.yml --class ContentUtil --all-methods"
```

`config/class-survey.yml` 은 저장소에 있는 설정입니다(소스 경로만 실제 프로젝트로 바꿔 씀).

```yaml
class: com.x.common.ContentUtil
methods:
  - signature: info(String id)
    visibility: public
    static: true
    returns: String
    services: [ProductInfoBiz.getInfo → ProductInfoBizImpl.getInfo]   # 선언 → 구현 (빈 이름 연결 포함)
    daos: [ProductDAO.select]
    sql: [{id: product.select, xml: ProductMapper.xml, tables: [TB_PRODUCT]}]
    uses-utils: [StringUtil.isEmpty]        # 다른 Util
    internal-calls: [ContentUtil.helper]     # 같은 클래스 안 메서드
    beans: ['빈 이름 "productInfoBiz" → …ProductInfoBizImpl (@Service("productInfoBiz"))']
    reflection: []
    unresolved: []                           # 못 이은 호출과 사유
    note: ""                                 # 직접 적는 칸
void-methods: [refresh(String id)]           # 반환 타입이 void 인 메서드
```

**다시 돌리면**

- 분석 칸(`services`·`daos`·`sql` …)만 새로 계산합니다.
- `note` 와 직접 추가한 키(메서드 안·파일 최상위 모두 — `hidden:` 등)는 **그대로 둡니다**.
- 소스에서 없어진 메서드는 지우지 않고 `removed: true` 로 남깁니다.
- 실행 시각 같은 정보는 넣지 않아, 소스가 그대로면 파일도 그대로입니다(git diff 에 실제 변경만).

**정리·초기화** — 둘 다 기존 파일을 `catalog/.backup/<클래스>.<시각>.yml` 로 **먼저 백업**합니다.

| 옵션 | 하는 일 |
|---|---|
| `--catalog-prune` | 없어진(`removed: true`) 메서드만 지움. `note`·`hidden` 등은 그대로 |
| `--catalog-reset` | 이전 내용(`note`·직접 추가한 키·`removed`)을 버리고 지금 소스로 새로 |

메서드마다 기존 Action 형태의 출력도 `output/<클래스>/<메서드>/` 에 함께 만듭니다(`output.catalog-outputs`, 기본 켜짐).

**작업 흐름**: `--all-methods` 로 목록 갱신 → `catalog/*.yml` 에 `note`·`hidden` 손으로 관리 → `runClass --apply` 로 흐름도에 반영.

---

## 4. 메서드 사용처 (공통화) — `./gradlew query`

공통화할 때 "이 메서드는 어느 Action 이 쓰나", "이 Action 은 무엇을 쓰나"를 카탈로그 데이터로 묻습니다. 분석하지 않고 카탈로그만 읽어서 빠릅니다.

**데이터 쌓기** — 두 카탈로그가 같은 형식(`key` · `uses`)으로 쌓입니다.

| 카탈로그 | 언제 | 내용 |
|---|---|---|
| `catalog/run/<Action>.yml` | Action 을 `./gradlew run` 할 때마다 (`output.run-catalog`, 기본 켜짐) | 진입 메서드마다 쓰는 프로젝트 메서드 전부 |
| `catalog/<Util>.yml` | `--all-methods` | Util 메서드마다 쓰는 프로젝트 메서드 전부 + 요약 |

```yaml
# catalog/run/OrderAction.yml
class: com.x.action.OrderAction
entries:
  - key: com.x.action.OrderAction#execute(HttpServletRequest,HttpServletResponse)   # analysis.yaml 의 메서드 키
    method: execute(HttpServletRequest request, HttpServletResponse response)
    uses:                                         # 흐름도에 있는 프로젝트 메서드 key 전부 (흐름도 내용 그대로)
      - com.x.biz.ProductInfoBizImpl#getInfo(String)
      - com.x.common.ContentUtil#getInfo(String)
      - com.x.dao.ProductDAO#select(String)
    note: ""
```

- `key` = 분석 모델의 메서드 키(`클래스FQN#이름(타입…)`) — 오버로딩·같은 이름 클래스·중첩 클래스까지 정확히 구분해서, 두 카탈로그가 같은 메서드를 같은 키로 가리킵니다.
- 같은 Action 의 다른 진입 메서드는 따로 쌓이고, `note`·직접 추가한 키는 다시 돌려도 남습니다.

**한 번에 쌓기 (일괄)** — 소스에서 찾아 전부 돌립니다(`--class` 불필요).

```bash
./gradlew run --args="--config config/my-project.yml --all-actions"   # Action/Controller 전부 → catalog/run/ (진입 메서드마다)
./gradlew run --args="--config config/my-project.yml --all-utils"     # Util 전부 → catalog/<Util>.yml
./gradlew query --args="usage --config config/my-project.yml --md usage.md --csv usage.csv"   # 모든 메서드의 역색인
```

- 진입 메서드 = 매핑 어노테이션(`@RequestMapping`·`@GetMapping` …)이 붙은 메서드 전부, 없으면 `public execute(…)`. 둘 다 없는 Action 은 건너뛰고 사유를 출력합니다(그런 Action 은 `--class --method` 로 직접).
- 하나가 실패해도 계속하고 마지막에 성공·실패·건너뜀을 요약합니다. 기본은 카탈로그만 쓰고(빠르게) `output/` 상세는 만들지 않습니다 — 함께 만들려면 `--with-outputs`.

**묻기**

```bash
./gradlew query --args="who-uses ContentUtil.getInfo"        # 이 메서드를 쓰는 Action (+ 쓰는 Util 메서드)
./gradlew query --args="who-uses ProductDAO"                 # 클래스의 메서드 전부
./gradlew query --args="uses OrderAction"                    # 이 Action(.진입)이 쓰는 메서드
./gradlew query --args="common 2 class=*Util"                # 2곳 이상에서 쓰는 메서드 — 공통화 후보
./gradlew query --args="shared OrderAction ProductAction"    # 함께 쓰는 메서드
./gradlew query --args="unused ContentUtil"                  # Util 기능 목록 중 어느 Action 도 안 쓰는 메서드
./gradlew query --args="usage"                               # 메서드마다 쓰는 곳 전부
./gradlew query --args="coverage --config config/my-project.yml"   # 빠진 데이터
```

```
# who-uses ProductInfoBizImpl.getInfo
기준: run 카탈로그 Action 2개 · 진입 메서드 4개 · Util 기능 목록 1개
메서드                              쓰는 Action 수  쓰는 곳 (Action.진입)  쓰는 Util 메서드
ProductInfoBizImpl.getInfo(String)  1               EdgeAction.utilBeans   ProductUtil.info, ProductUtil.infoInline, …
```

- 결과는 **콘솔 표**로 나오고, `--md 파일` · `--csv 파일`(엑셀용 BOM) 을 붙이면 같은 결과를 **파일로도 남깁니다**. 파일 이름만 주면 `query/` 폴더 아래(`--md usage.md` → `query/usage.md`, 설정 `output.query-dir`), 절대 경로는 그대로.
- 필터: `class=패턴`(쓰이는 메서드의 클래스), `action=패턴`(쓰는 Action). 패턴은 단순명·FQN, `*`.
- 옵션: `--config`(카탈로그 폴더·소스 경로), `--catalog-dir`.

**데이터가 다 있는지** — 결과는 **쌓아 둔 카탈로그 기준**입니다. 매 결과 위에 `기준: Action n개 · 진입 메서드 m개 · Util 기능 목록 k개` 가 나오고, `coverage` 가 빠진 것을 알려 줍니다.

| 빠진 것 | 채우는 법 |
|---|---|
| run 카탈로그가 없는 Action/Controller (소스와 비교) | 그 Action 을 `run` |
| Action 이 쓰는데 기능 목록이 없는 Util | 그 Util 을 `--all-methods` |
| 카탈로그를 만든 뒤 소스가 바뀐 클래스 | 다시 `run` / `--all-methods` |

## 흐름도 뷰어 (`flowchart.html`) 사용법

브라우저로 열기만 하면 됩니다 — 설치·인터넷 불필요(배치 엔진 ELK 내장). Action 흐름도와 전체 클래스 흐름도가 같은 뷰어입니다.

| 기능 | 방법 |
|---|---|
| 이동·확대 | 끌기 = 이동, 휠 = 확대/축소, `f` 또는 `화면 맞춤` |
| 배치 | 진입 메서드가 가운데, 불린 메서드(ServiceImpl → DAO …)는 **부른 줄 높이**에 좌우 가까운 쪽으로 |
| 접기 / 펴기 | 상자 제목 클릭. `메서드 접기` = 호출 구조만, `모두 펴기` |
| 검색 | `/` 또는 검색창, Enter 로 다음 결과 (접힌·숨긴 곳도 펴서 이동) |
| 노드 상세 | 노드 클릭 → 코드 전체 · 파일:라인 · 모델 ID |
| 세부 메서드로 이동 | 노란 호출 상자 더블클릭 |
| **연결 강조** | 노드 클릭 → `연결 강조` 체크 (+ `↑ 부른 쪽` / `↓ 부르는 쪽`) — 호출로 이어진 경로만 진하게. 여러 노드를 체크하면 함께. Esc·`강조 해제` |
| **VO 연결** | Service·DAO 호출 노드 클릭 → `VO 연결` 체크 — 넘기는 VO 를 set 하는 **줄**부터 실행 순서대로 실제 흐름선을 따라 강조. 패널에 VO 와 set 코드 |
| **메서드 숨기기** | 노드 클릭 → `이 메서드 숨기기` 체크. 상단 `숨김 n · 모두 보이기`. 숨긴 메서드가 부르던 하위 메서드는 부른 곳 모두에 이어짐 |
| **작은 세부 생략** | 상단 체크 + 숫자: 노드 수가 그 이하인 세부 메서드 상자를 자동으로 뺌 (기본 10, 설정 `output.flowchart-html-hide-nodes`) |

> 숨김·작은 세부 생략 설정은 브라우저에 기억됩니다. 상자가 안 보이면 상단 체크를 확인하세요.

---

## 흐름도 읽는 법

**표시**

| 표시 | 뜻 |
|---|---|
| `[⛁ DB]` (빨강) | 그 호출·메서드 안에서 (몇 단계를 거치든) DAO·Mapper·Repository 를 부름. 아래 `⛁ DB 로직: ScpAppDAO.selectApps, …` 에 닿는 DAO 목록 |
| 노란 상자 | 세부 메서드로 이어지는 호출 (더블클릭 = 그 메서드로) |
| 청록 글자 | 요청 값 `request.이름 ?: 기본값` |
| 주황 글자 · `· x v2` | 변수 값이 바뀌는 줄과 버전 (`total -= usePoint · total v2`) |
| 보라 `▸` / `│` | 값을 채우는 함수(바인딩 함수)의 본문을 그 자리에 펼침. 같은 노드에서 다시 부르면 `(위 #2 와 같은 본문)` |
| `↻ for` 상자 · 파란 선 | 반복 영역과 되돌아가는 선 |
| `impl 후보:` | 인터페이스 호출의 실제 구현 (유일 구현 또는 빈 이름으로 확정) |
| `#n` | 메서드 안 호출 순번 (call-tree·analysis.yaml 과 같은 번호) |

**코드 줄 규칙**

- 모든 줄은 `타입 대상 = 값` 코드 한 줄이고, 길어도 줄이지(`…`) 않습니다. 긴 줄은 `output.max-line-length`(기본 100자)에서 쉼표·연산자·체인 앞에서 끊어 다음 줄로 이어 씁니다.
- 메서드 시작 노드는 파라미터 이름까지: `NoticeWriteBiz.write(NoticeVO vo, String body)`.
- VO 값 설정은 `프로퍼티 = 값` (클래스는 `· 클래스 변수` 줄).
- 로직 호출이 없는 선언·대입 문장은 문장 통째로 한 줄, 다른 호출의 인자 안 호출과 조건식 안 호출은 바깥 코드에 보이므로 따로 그리지 않습니다.
- 노드 하나가 `output.max-node-lines`(기본 30줄)를 넘으면 다음 노드로 이어서 나눕니다.

**단계 보기** (`phase-flowchart`, Action 분석) — 진입 메서드의 요소를 ① 바인딩(요청 값·VO 채우기·값 조회) › ② 체크(Validator·throw 분기) › ③ 로직(Service·DAO…) › ④ 후처리로 판정해 바인딩·체크를 요약하고 로직만 상세히 그립니다. 판정 근거는 `phase-summary.md`, 규칙은 설정 `phases`.

---

## 분석 규칙

**어디까지 따라가나**

- 기본 `max-depth` 까지 + 역할이 Service·Biz·DAO·Repository·Mapper 면 끝까지 (`analysis.expand-roles`).
- `analysis.expand-classes` 에 적은 클래스는 어느 Depth 든 끝까지 (기본 `["CountryUtil", "ODAction", "ContentUtil"]`, `*` 패턴).
- **Action 안에 선언된 클래스**(`ParamMgr` 등)는 이름과 상관없이 자동으로 따라가고, `new ParamMgr(request)` 생성자 본문도 분석합니다. 표시는 `AppDetailAction.ParamMgr`.
- 인터페이스 호출은 source 안 구현이 정확히 하나면 구현으로 잇습니다(`impl 후보:`).

**빈 이름으로 꺼내 쓰는 Service·DAO** (`Wcommon.getBean("productInfoBiz")` 등)

```java
ProductInfoBiz biz = (ProductInfoBiz) Wcommon.getBean("productInfoBiz");   // 변수로 받아 쓰기
((ProductInfoBiz) Wcommon.getBean("productInfoBiz")).getInfo(id);           // 바로 캐스팅
bean.getClass().getMethod("getInfo", String.class).invoke(bean, id);       // 리플렉션 (메서드 이름이 리터럴)
```

- 빈 이름 → 클래스: `@Service("productInfoBiz")` 같은 어노테이션 값 → XML `<bean id class>` → 이름 없는 어노테이션의 기본 이름(첫 글자 소문자). 후보가 없거나 여럿이면 잇지 않고 `implementationReason` 에 사유.
- 인터페이스 구현이 여럿이어도 빈 이름이 하나를 정합니다. 빈을 꺼내는 메서드(Util 등)는 `max-depth` 밖이어도 분석합니다.
- 조회 메서드 이름은 `analysis.bean-lookup-methods`(기본 `["getBean"]`). 빈 이름을 변수·문자열 연결로 만들면 추적하지 않습니다.
- Spring XML 이 `src/main/resources`·`src/main/webapp` 밖이면 `analysis.mapper-xml-roots` 에 그 폴더를 추가 (매퍼 XML 폴더도 함께).

**Biz·Service 안의 VO** — Service·Biz·DAO 메서드는 VO 를 채우기만 해도 상자로 그립니다. Biz 가 파라미터로 받은 VO 를 DAO 에 넘기면 Action 쪽 원래 VO 까지 이어 VO 연결에 보여 줍니다(호출 위치가 하나일 때).

**매퍼 SQL** — 어떤 XML 의 어떤 id 인지와 그 문장이 쓰는 테이블 이름만 (쿼리 본문은 넣지 않음). 흐름도 호출 노드 아래 `SQL id · 파일`·`테이블` 줄, `analysis.md` 2장.

---

## 설정

설정은 세 단계로 겹치고, 뒤의 것이 앞의 것을 덮어씁니다.

1. **내장 기본 조건** — [src/main/resources/analyzer-defaults.yml](src/main/resources/analyzer-defaults.yml) (모든 키와 설명이 여기에)
2. **프로젝트 설정 파일** `--config` — 바꿀 키만
3. **CLI 옵션**

맵(`analysis`·`output` …)은 키 단위로 합쳐지고, 목록·값은 통째로 교체됩니다. 키는 kebab-case 이고, 알 수 없는 키(오타)는 오류로 중단합니다. `--config` 가 없으면 `--source-root` 를 준 경우 기본 조건만, 아니면 `config/analyzer.yml`(샘플).

**자주 바꾸는 키**

| 키 | 기본 | 뜻 |
|---|---|---|
| `analysis.max-depth` | 1 | 기본으로 따라갈 깊이 |
| `analysis.expand-roles` | Service·Biz·DAO·Repository·Mapper·Validator | 깊이와 상관없이 끝까지 따라갈 역할 |
| `analysis.expand-classes` | CountryUtil·ODAction·ContentUtil | 끝까지 따라갈 클래스 (`*` 패턴) |
| `analysis.bean-lookup-methods` | `["getBean"]` | 빈 이름 조회 메서드 |
| `analysis.include-external-library-calls` | false | JDK·라이브러리 호출도 모델에 |
| `output.flowchart-html-hide-nodes` | 10 | 작은 세부 생략 기준 (0 = 생략 없음) |
| `output.max-line-length` · `max-node-lines` | 100 · 30 | 노드 한 줄 길이 · 노드 하나 줄 수 |
| `output.flowchart-exclude` | 없음 | 흐름도에서 뺄 호출·메서드 (`"*Util.*"`, `"LOG.debug"`) |
| `output.color-theme` | light | `dark` 가능 |
| `output.catalog-dir` · `catalog-outputs` | catalog · true | 기능 목록 폴더 · 메서드별 출력도 만들지 |
| `output.run-catalog` | true | run 할 때 `catalog/run/<Action>.yml` 에 쓰는 메서드 누적 |

---

## CLI 옵션

**`./gradlew run`** (Action 분석 · Util 기능 목록)

| 옵션 | 설명 |
|---|---|
| `--config <path>` | 프로젝트 설정 파일 |
| `--source-root <path>` | 소스 루트 (반복 가능 = 멀티 모듈). 주면 설정 파일의 루트를 대체 |
| `--class <name>` | 대상 클래스 (단순명 또는 FQN — 같은 이름이 여럿이면 FQN) |
| `--method <name>` | 진입 메서드 (생략 시 추론) |
| `--output <dir>` | 출력 폴더 (결과는 `<dir>/<클래스>/`) |
| `--jsp <path>` | Response 로 분석할 JSP 파일명·경로 (반복 가능) |
| `--all-methods` | Util 기능 목록 모드 → `catalog/<클래스>.yml` |
| `--all-actions` · `--all-utils` | 일괄: 소스의 Action/Controller 전부 → run 카탈로그 · Util 전부 → 기능 목록 (`--class` 불필요, `--with-outputs` 로 output/ 상세도) |
| `--catalog-prune` · `--catalog-reset` | 기능 목록 정리 · 초기화 (백업 후) |
| `--catalog-dir <dir>` | 카탈로그 폴더 (기본 `catalog`) |

**`./gradlew query`** (카탈로그 질의 — [4장](#4-메서드-사용처-공통화--gradlew-query))

| 질의 · 옵션 | 설명 |
|---|---|
| `who-uses` · `uses` · `common [N]` · `shared` · `unused` · `usage` · `coverage` | 질의 종류 |
| `class=패턴` · `action=패턴` | 필터 |
| `--md <파일>` · `--csv <파일>` | 결과를 파일로도 저장 |
| `--config` · `--catalog-dir` | 설정 · 카탈로그 폴더 |

**`./gradlew runClass`** (전체 클래스 흐름도)

| 옵션 | 설명 |
|---|---|
| `--config` · `--source-root` · `--class` · `--output` | 위와 같음 |
| `--apply` | `catalog/<클래스>.yml` 의 `hidden` 을 흐름도에 적용 |

---

## 테스트

```bash
./gradlew test
```

- 모든 샘플 진입 메서드 × 여러 설정으로 흐름도를 그려 빠진 코드·중복 줄·검증 오류가 없는지 확인합니다(`FlowchartSweepTest`, `DefaultConfigSweepTest`).
- 같은 입력이면 출력이 바이트 단위로 같은지 확인합니다(`DeterminismTest`).
- **회귀 비교 도구** — 기존 기능을 크게 바꿀 때, 바꾸기 전 커밋과 지금 코드의 Action 출력 전부를 만들어 비교합니다.

  ```bash
  GOLDEN_DIR=/tmp/new ./gradlew test --tests analyzer.GoldenDumpTest --rerun-tasks
  # 바꾸기 전 커밋을 git worktree 로 받아 같은 명령 → diff -r /tmp/base /tmp/new
  ```
