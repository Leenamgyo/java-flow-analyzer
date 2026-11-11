# Util 기능 목록

`<클래스>.yml` = Util 클래스 하나의 기능 목록 (메서드 하나 = 기능 하나 → 얽힌 Service·Biz·DAO·SQL·빈 이름 연결).
분석기가 만들고 갱신하지만 **계속 관리하는 파일**입니다 (git 으로 관리, 빌드가 지우지 않음).

```bash
./gradlew run --args="--config config/class-survey.yml --class ContentUtil"
```

- 다시 돌리면 분석 칸(`services`·`daos`·`sql` …)만 새로 계산합니다.
- `note` 와 직접 추가한 키는 그대로 둡니다.
- 소스에서 없어진 메서드는 지우지 않고 `removed: true` 로 남깁니다.
- `void-methods` = 반환 타입이 void 인 현재 메서드 목록.
- `ProductUtil.yml` 은 샘플 프로젝트 예시입니다.
