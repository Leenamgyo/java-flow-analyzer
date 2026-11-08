package analyzer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 선언 뒤에 값이 바뀌는 변수 1개 (V*). 지역 변수 또는 메서드 파라미터. 필드·for 헤더 변수·향상된 for 변수는 대상이 아니다.
 * 변경 이력은 changes(VC*)에 소스 순서로 있다 — 첫 항목은 선언 초기값(INIT) 또는 파라미터(PARAM).
 */
public final class VariableInfo {
    public String id;
    public String methodId;
    public String name;
    public String type;
    /** LOCAL | PARAMETER */
    public String kind;
    public SourceLocation location;
    public final List<String> changeIds = new ArrayList<>();
}
