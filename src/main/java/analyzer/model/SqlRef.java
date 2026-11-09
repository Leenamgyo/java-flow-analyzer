package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 호출이 쓰는 MyBatis/iBatis SQL 문 (매퍼 XML 의 statement). 쿼리 본문은 담지 않는다 — 어떤 XML 의 어떤 id 인지와
 * 그 문장이 쓰는 테이블 이름만 (사용자 요구: 쿼리는 손으로 옮김).
 */
public final class SqlRef {
    /** 호출에서 정한 statement id: "com.x.UserMapper.selectUser" / "selectUser" (못 정하면 원문 식) */
    public String statementId;
    /**
     * id 근거: MAPPER_INTERFACE(매퍼 인터페이스 FQN + 메서드명) | LITERAL | CONSTANT(상수·상수 연결) |
     * UNRESOLVED(식이라 확정 불가)
     */
    public String basis;
    /** XML 매칭: FOUND | NOT_FOUND | AMBIGUOUS(여러 XML) | ANNOTATION(@Select 등, XML 없음) | SKIPPED(id 미확정) */
    public String xmlStatus;
    /** 찾은 XML 파일 (source 기준 상대 경로) */
    public String xmlFile;
    /** XML namespace */
    public String namespace;
    /** XML 안 id */
    public String id;
    /** select | insert | update | delete | statement | procedure */
    public String statementType;
    /** SQL 에서 찾은 테이블 이름 (나온 순서, 중복 제거). ${…} 는 동적 이름 그대로 */
    public final List<String> tables = new ArrayList<>();
    /** AMBIGUOUS 후보 XML 들 */
    public final List<String> candidates = new ArrayList<>();
    /**
     * true = 호출 모양만으로는 SQL 호출이라 확정할 수 없어(SqlSession 타입이 아닌 수신 객체·상속 메서드, Mapper 역할이 아닌
     * 인터페이스) 매퍼 XML 에 그 statement 가 있을 때만 SQL 호출로 남긴다 (없으면 sql 을 지움 — 추측 금지). YAML 에는 쓰지 않음.
     */
    public boolean requiresXmlMatch;
    /** 참고 (include 미해결, 동적 테이블 등) */
    public final List<String> notes = new ArrayList<>();
}
