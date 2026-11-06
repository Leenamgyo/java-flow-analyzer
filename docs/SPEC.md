# Java Action 정적 분석기 개발 — YAML + Mermaid + 제어 블록 분석 (원본 요구사항)

> 이 문서는 프로젝트의 원본 요구사항(프롬프트)이다. 작업 규칙 요약과 구현 현황은 `CLAUDE.md` 를 본다.

## 1. 역할 및 목적

당신은 Java 21, Spring Legacy, Spring Boot, MyBatis 및 JavaParser를 전문적으로 다루는 정적 분석 도구 개발자입니다.

사용자가 지정한 Action 또는 Controller 하나를 시작점으로 Java 소스를 정적 분석하고, 클래스·메서드 호출 관계와 제어 흐름을 구조화하여 저장하는 독립적인 분석 프로그램을 개발합니다.

분석 결과는 YAML로 저장하고, 같은 데이터를 기반으로 Mermaid 다이어그램과 텍스트 트리를 자동 생성합니다.

**핵심 원칙: 실제 소스 코드에서 추출한 하나의 공통 분석 모델을 YAML, 트리, Mermaid 출력에 동일하게 사용합니다.**

AI가 소스에 없는 메서드, 예외 처리, 호출 관계를 추측해서 생성해서는 안 됩니다.

---

## 2. 기술 환경

- Java 21
- Gradle
- JavaParser
- JavaSymbolSolver
- Jackson YAML 또는 SnakeYAML
- Mermaid
- 로컬 파일 시스템

### 보안 및 실행 제약

1. Git을 사용하지 않습니다.
2. 원본 프로젝트를 수정하지 않습니다.
3. 원본 애플리케이션을 실행하지 않습니다.
4. DB 및 외부 API에 연결하지 않습니다.
5. 원본 소스는 읽기 전용으로 접근합니다.
6. 분석 결과는 별도 output 디렉터리에 저장합니다.
7. 분석 프로그램과 원본 프로젝트의 의존성을 분리합니다.
8. 필요한 라이브러리는 승인된 사내 저장소 또는 로컬 캐시를 사용합니다.
9. 실제 코드에서 확인되지 않은 관계는 unresolved로 표시합니다.

---

## 3. 분석 대상

전체 프로젝트가 아니라 사용자가 지정한 Action 또는 Controller 하나만 분석합니다.

다만 메서드 호출을 해석하기 위해 필요한 클래스, 인터페이스, 상속 관계 및 설정 파일은 참조할 수 있습니다.

참조용으로 읽은 모든 파일을 분석 결과에 포함해서는 안 됩니다.

### 설정 예시

```yaml
target:
  source-root: /workspace/legacy-project/src/main/java
  class: ScpAppListAction
  entry-method: execute

analysis:
  max-depth: 1
  expand-same-class-methods: true
  include-conditions: true
  include-exceptions: true
  include-request-parameters: true
  include-external-library-calls: false

output:
  yaml: true
  tree: true
  mermaid: true
  direction: TD
```

### 진입 메서드 식별

- 명시적으로 지정된 메서드를 우선 사용합니다.
- 메서드 오버로딩이 있으면 파라미터 타입으로 식별합니다.
- 진입 메서드가 불명확하면 프레임워크 매핑 및 구현 코드를 확인합니다.
- 동일한 이름의 후보가 여러 개라면 임의로 선택하지 않습니다.

---

## 4. 클래스 역할 분류

클래스의 어노테이션, 상속·구현 관계, 클래스명 Suffix를 이용해 역할을 분류합니다.

| 역할 | 식별 기준 |
|---|---|
| Action | *Action |
| Controller | *Controller, @Controller, @RestController |
| Service | *Service, *ServiceImpl, @Service |
| Biz | *Biz, *BizImpl |
| DAO | *DAO, *Dao |
| Repository | *Repository, @Repository |
| Mapper | *Mapper, @Mapper |
| Util | *Util, *Utils |
| Other | 기타 |

### 분류 원칙

1. 실제 코드 구조와 어노테이션을 우선 확인합니다.
2. Suffix는 보조적인 분류 기준으로 사용합니다.
3. Suffix가 일치하더라도 실제 역할을 확정할 수 없으면 분류 근거를 기록합니다.
4. 여러 분류 조건이 충돌하면 사전에 정의한 우선순위를 적용합니다.
5. 분류 결과와 근거를 YAML에 저장합니다.

---

## 5. 메서드 메타데이터 분석

각 메서드에서 다음 정보를 수집합니다.

- 선언 클래스
- 메서드명
- 메서드 전체 시그니처
- 접근 제어자
- static 여부
- abstract 여부
- 반환 타입
- 파라미터 타입 및 이름
- 소스 파일 경로
- 시작 및 종료 라인
- 호출 Depth
- 직접 호출 관계

### 접근 제어자

- public
- protected
- private
- package-private

접근 제어자는 JavaParser의 실제 메서드 선언부에서 확인합니다.

오버로딩된 메서드는 파라미터 타입을 포함한 시그니처로 구분합니다.

상속 및 인터페이스 호출은 가능한 경우 JavaSymbolSolver로 선언 대상을 확인합니다.

런타임 구현체가 확정되지 않으면 추측하지 않습니다.

---

## 6. Depth 및 호출 관계 분석

### Depth 정의

- Depth 0: 지정된 진입 메서드
- Depth 1: 진입 메서드에서 직접 호출하는 메서드
- Depth 2 이상: 기본적으로 분석하지 않음

### 동일 클래스 확장

`expand-same-class-methods: true`인 경우 동일 클래스 내부 메서드를 추가 분석할 수 있습니다.

예를 들어:

```text
ScpAppListAction [Action]
└── execute() [public, D0]
    ├── validateParameters() [private, D1]
    ├── createCommand() [private, D1]
    │   └── parseParameter() [private, D2]
    └── ScpAppListBiz.getAppList() [public, D1]
```

### 세부 규칙

1. 메서드 호출은 실제 소스 코드 순서대로 수집합니다.
2. 동일 클래스 내부 메서드는 설정에 따라 확장합니다.
3. 외부 클래스 메서드는 기본적으로 내부 분석하지 않습니다.
4. 외부 메서드 호출은 클래스명과 시그니처를 표시합니다.
5. 실제 Depth는 동일 클래스 확장 여부와 관계없이 유지합니다.
6. 재귀 및 순환 호출은 cycle로 표시하고 재탐색하지 않습니다.
7. 동일 메서드가 여러 위치에서 호출되면 호출 위치별 관계를 별도 기록합니다.
8. 조건부 호출과 무조건 호출을 구분합니다.
9. 외부 라이브러리 호출은 설정에 따라 출력에서 제외할 수 있습니다.
10. 분석 깊이 제한으로 생략한 관계는 미해결 호출과 구분합니다.

---

## 7. Legacy 및 Spring Boot Request Parameter 분석

### Legacy 방식

다음 코드를 인식합니다.

```java
request.getParameter("mcc");
request.getParameterValues("appId");
request.getParameterMap();
RequestUtil.getString(request, "deviceId");
ParameterUtil.getInt(request, "startNum");
```

사용자 정의 Request 유틸리티는 YAML 규칙으로 등록합니다.

### Spring Boot 방식

- @RequestParam
- @PathVariable
- @RequestHeader
- @RequestBody
- @ModelAttribute
- HttpServletRequest 직접 접근

### 저장 데이터

- 파라미터명
- 추출 방식
- 소스 메서드
- 대입 변수명
- 타입
- 기본값
- 코드 위치

파라미터명이 동적 표현식으로 구성되어 정확히 확인되지 않으면 UNKNOWN으로 기록합니다.

`getParameterMap()`처럼 전체 파라미터를 가져오는 경우 개별 파라미터를 임의로 만들어서는 안 됩니다.

---

## 8. 제어 블록 분석

JavaParser AST를 이용하여 메서드 내부의 제어 구조를 분석합니다.

### 지원 대상

- if / else / else if
- switch / case
- for / while / do-while
- try / catch / finally
- try-with-resources
- return / throw
- 중첩 제어 블록

### 공통 규칙

1. 제어 블록마다 고유 ID를 부여합니다.
2. 부모 블록 및 소속 메서드를 기록합니다.
3. 블록 내부 메서드 호출을 연결합니다.
4. 소스상의 실행 위치와 순서를 기록합니다.
5. 메서드 호출 Depth와 제어 블록 중첩 Depth를 구분합니다.
6. 블록이 중첩되어도 메서드 호출 Depth는 증가하지 않습니다.
7. 분석한 블록 구조는 YAML과 Mermaid 양쪽에 사용합니다.

### Try-Catch-Finally 상세 규칙

JavaParser의 `TryStmt`, `CatchClause`, `BlockStmt`를 이용합니다.

1. try, catch, finally를 각각 독립된 제어 블록으로 식별합니다.
2. 하나의 try에 연결된 모든 catch와 finally 관계를 보관합니다.
3. catch 블록의 예외 타입을 수집합니다.
4. multi-catch의 여러 예외 타입을 구분합니다.
5. catch 블록의 선언 순서를 유지합니다.
6. try 내부의 메서드 호출을 순서대로 수집합니다.
7. catch 및 finally 내부의 메서드 호출도 별도로 수집합니다.
8. 중첩 try-catch를 지원합니다.
9. throw 또는 재예외 발생 여부를 기록합니다.
10. 예외 발생 가능성만으로 catch가 반드시 실행된다고 표시하지 않습니다.
11. 호출 대상에서 발생하는 예외 타입을 정확히 알 수 없다면 임의의 catch 경로로 연결하지 않습니다.
12. finally의 실행 경로는 Java 제어 흐름 규칙을 반영합니다.
13. try-with-resources의 리소스 초기화와 자동 종료 동작을 별도 메타데이터로 기록합니다.

### 종료 흐름

- return은 현재 메서드의 정상 반환으로 처리합니다.
- throw는 예외 발생으로 처리합니다.
- 예외가 catch되면 해당 처리 흐름을 연결합니다.
- catch되지 않은 예외는 상위 호출자로 전파될 수 있음을 기록합니다.
- finally는 return 및 throw와 함께 실행될 수 있음을 고려합니다.
- finally 자체가 return 또는 throw를 수행하면 원래의 종료 흐름이 달라질 수 있음을 고려합니다.

정적 분석으로 정확한 예외 흐름을 결정할 수 없는 경우 unresolved로 표시합니다.

---

## 9. 공통 분석 데이터 모델

YAML, 트리, Mermaid는 동일한 분석 데이터 모델을 사용합니다.

### 모델 구성

- ClassInfo
- MethodInfo
- CallSiteInfo
- CallRelation
- ControlBlock
- RequestParameterInfo
- SourceLocation
- ResolutionStatus

### ID 설계

1. 클래스, 메서드, 호출 위치, 제어 블록에 각각 고유 ID를 부여합니다.
2. 메서드 ID는 클래스와 전체 시그니처를 기준으로 안정적으로 생성합니다.
3. 호출 위치 ID는 호출 메서드와 코드 위치를 기준으로 구분합니다.
4. 동일 메서드가 여러 위치에서 호출되어도 MethodInfo는 중복 생성하지 않습니다.
5. 호출 관계는 CallSiteInfo를 통해 연결합니다.
6. YAML과 Mermaid가 동일 ID를 참조하도록 합니다.

---

## 10. YAML 저장 구조

다음 파일로 데이터를 구분합니다.

### classes.yaml

클래스 및 메서드 메타데이터

```yaml
classes:
  - id: C1
    name: ScpAppListAction
    role: Action
    methods:
      - id: M1
        name: execute
        signature: execute()
        visibility: public
        return-type: void

      - id: M2
        name: validateParameters
        signature: validateParameters()
        visibility: private
        return-type: void
```

### call-relations.yaml

메서드 호출 관계와 호출 위치

```yaml
calls:
  - id: CALL1
    from: M1
    to: M2
    order: 1
    depth: 1
    resolved: true
    control-block-id: T1
```

### control-blocks.yaml

제어 블록의 구조와 연결 관계

```yaml
blocks:
  - id: T1
    type: try
    parent-method-id: M1
    parent-block-id: null
    calls:
      - CALL1

  - id: CATCH1
    type: catch
    parent-method-id: M1
    parent-block-id: T1
    exception-types:
      - IllegalArgumentException
    calls: []

  - id: FINALLY1
    type: finally
    parent-method-id: M1
    parent-block-id: T1
    calls: []
```

### parameters.yaml

요청 파라미터 정보

```yaml
parameters:
  - name: mcc
    source-method: HttpServletRequest.getParameter
    variable: mcc
    type: String
    parent-method-id: M1
```

모든 YAML 예시는 데이터 구조를 보여주기 위한 것이며 실제 분석 결과는 아닙니다.

최종 저장 시 파일 경로, 코드 위치, 시그니처와 해석 상태 등 필요한 필드를 포함합니다.

---

## 11. 텍스트 호출 트리 생성

진입 메서드를 루트로 호출 관계 트리를 생성합니다.

### 출력 규칙

1. 호출 순서 유지
2. 클래스 역할 표시
3. 메서드 접근 제어자 표시
4. 호출 Depth 표시
5. 동일 클래스 메서드 중첩 표현
6. 다른 클래스 호출 시 클래스명 표시
7. 미해결 호출 표시
8. 순환 호출 표시
9. 제어 블록을 선택적으로 트리에 포함

제어 블록을 표시하는 경우 다음처럼 표현합니다.

```text
ScpAppListAction [Action]
└── execute() [public, D0]
    └── try
        ├── validate() [private, D1]
        ├── ScpAppListBiz.getAppList() [public, D1]
        ├── catch IllegalArgumentException
        │   └── handleValidationError() [private, D1]
        ├── catch Exception
        │   └── handleError() [private, D1]
        └── finally
            └── cleanup() [private, D1]
```

이 예시는 가상의 코드 구조입니다.

try, catch, finally는 동일한 예외 처리 구조에 속하며, 실제 실행 순서와 트리의 표시 순서를 혼동하지 않습니다.

---

## 12. Mermaid 자동 생성

Mermaid는 YAML에 저장되는 공통 분석 모델에서 생성합니다.

소스를 다시 분석하지 않습니다.

### A. Flowchart

- `flowchart TD` 사용
- Action / Controller / Biz / Service / DAO별 subgraph 구성
- 클래스 및 메서드 호출 관계 표시
- if/else 및 switch 분기 표시
- try/catch/finally를 개별 subgraph로 그룹화
- 제어 블록 중첩 구조 유지
- 조건부 호출에 조건 표시
- 정상 흐름과 예외 흐름 구분
- return, throw, catch되지 않은 예외를 구분
- 호출 대상 미해결 시 unresolved 표시

### Try-Catch 시각화 규칙

1. try 내부 처리와 호출 메서드를 try subgraph 안에 배치합니다.
2. catch마다 별도 subgraph를 생성합니다.
3. finally도 별도 subgraph로 생성합니다.
4. 예외 흐름은 점선 화살표로 표현합니다.
5. 확인되지 않은 예외 흐름은 추측해서 연결하지 않습니다.
6. 중첩 try-catch는 하위 subgraph로 표현합니다.
7. 제어 블록 그룹과 실행 흐름을 별도로 관리합니다.
8. 외부 클래스의 내부 코드는 Depth 제한에 따라 표시하지 않습니다.

### B. Class Diagram

Mermaid `classDiagram`을 사용하여 분석 대상 클래스와 메서드 관계를 표현합니다.

접근 제어자 표현:

- public: +
- private: -
- protected: #
- package-private: ~

클래스의 실제 상속, 인터페이스 구현, 확인된 의존 관계만 연결합니다.

### C. 출력 검증

- Mermaid 노드 ID 충돌 방지
- 메서드 시그니처 특수문자 처리
- YAML ID와 Mermaid 노드 매핑 검증
- 존재하지 않는 관계 생성 금지
- 제어 블록 부모·자식 관계 검증
- Mermaid CLI가 설치된 경우 렌더링 검증

---

## 13. 결과 파일 구조

```text
output/
└── ScpAppListAction/
    ├── classes.yaml
    ├── call-relations.yaml
    ├── control-blocks.yaml
    ├── parameters.yaml
    ├── call-tree.txt
    ├── flowchart.mmd
    ├── class-diagram.mmd
    └── analysis.md
```

### analysis.md

다음 항목을 포함합니다.

1. 분석 대상 클래스와 진입 메서드
2. 분석 설정 및 적용된 Depth
3. 분석된 클래스 및 메서드 목록
4. Request Parameter 목록
5. 클래스 간 호출 관계
6. 제어 블록 구조
7. 주요 예외 처리 흐름
8. unresolved 목록
9. 분석 범위와 정적 분석 한계

---

## 14. 프로젝트 구조

```text
java-flow-analyzer/
├── build.gradle
├── settings.gradle
├── config/
│   └── analyzer.yml
├── src/main/java/
│   └── analyzer/
│       ├── Main.java
│       ├── parser/
│       ├── classifier/
│       ├── resolver/
│       ├── parameter/
│       ├── controlflow/
│       ├── model/
│       ├── serializer/
│       └── mermaid/
├── src/test/java/
└── output/
```

모듈 역할:

- parser: Java AST 파싱
- classifier: 클래스 역할 분류
- resolver: 메서드 호출 관계 해석
- parameter: Request Parameter 추출
- controlflow: 제어 블록과 흐름 분석
- model: 공통 분석 데이터
- serializer: YAML 및 트리 출력
- mermaid: Mermaid 생성 및 검증

---

## 15. 단계별 구현

다음 순서대로 구현합니다.

### 1단계 — 기본 AST 분석

- 지정 Action 탐색
- 진입 메서드 확인
- 접근 제어자 추출
- 클래스 역할 분류
- 메서드 시그니처 추출

### 2단계 — 호출 관계 분석

- Depth 1 직접 호출 추출
- 동일 클래스 메서드 확장
- JavaSymbolSolver 해석
- 중복 및 순환 관계 처리

### 3단계 — 제어 블록 분석

- if/else
- switch
- try/catch/finally
- 중첩 블록
- return 및 throw
- 호출 위치와 제어 블록 연결

### 4단계 — Request Parameter 분석

- HttpServletRequest
- 사용자 정의 RequestUtil
- Spring MVC 어노테이션

### 5단계 — YAML 및 트리 출력

- 공통 분석 데이터 구축
- YAML 파일 직렬화
- 호출 트리 생성
- ID 참조 무결성 검증

### 6단계 — Mermaid 생성

- Flowchart
- Class Diagram
- 제어 블록 subgraph
- 예외 흐름
- 출력 문법 검증

### 7단계 — 테스트

다음 케이스를 검증합니다.

- 접근 제어자 4종류
- 클래스 Suffix 분류
- 오버로딩
- Depth 1 제한
- 동일 클래스 메서드 확장
- 중복 호출 및 재귀
- Legacy Request Parameter
- 단일 및 다중 catch
- 중첩 try-catch
- try-with-resources
- return과 finally
- catch 내부 재예외 발생
- 미해결 외부 호출
- YAML과 Mermaid 관계 일치

각 단계는 컴파일과 테스트를 완료한 뒤 다음 단계로 진행합니다.

---

## 16. 최초 실행 지시

우선 최소 기능(MVP)을 구현합니다.

### MVP 우선순위

1. Action 클래스 지정
2. 진입 메서드 분석
3. 메서드 접근 제어자 수집
4. Suffix 기반 역할 분류
5. Depth 1 메서드 호출 추출
6. 동일 클래스 메서드 확장
7. try/catch/finally 블록 추출
8. YAML 저장
9. 텍스트 호출 트리 생성
10. Mermaid Flowchart 생성

Class Diagram, 고급 예외 전파 해석, Spring 프레임워크의 동적 호출 해석은 후속 기능으로 구현합니다.

### 작업 지침

- 최초에는 프로그램의 최소 구조와 핵심 데이터 모델을 작성합니다.
- 이후 각 모듈을 순차적으로 구현합니다.
- 실제 실행 가능한 코드와 테스트를 제시합니다.
- 구현하지 않은 기능을 완료했다고 표시하지 않습니다.
- 분석 결과의 근거가 되는 코드 위치를 기록합니다.
- 상세 분석은 지정된 Action과 관련된 범위로 제한합니다.
- 불필요한 기능을 임의로 추가하지 않습니다.

최종 결과는 동일한 분석 데이터를 기반으로 YAML, 텍스트 트리, Mermaid가 일관되게 생성되어야 합니다.
