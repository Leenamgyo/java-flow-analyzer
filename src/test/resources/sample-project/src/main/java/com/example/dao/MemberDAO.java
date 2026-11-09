package com.example.dao;

/** MyBatis 매퍼 인터페이스를 *DAO 로 이름 붙인 경우 (XML namespace = 이 FQN) */
public interface MemberDAO {
    Object selectMember(String memberId);

    /** XML 에 없는 메서드 — SQL 호출로 보지 않는다 */
    Object notInXml(String memberId);
}
