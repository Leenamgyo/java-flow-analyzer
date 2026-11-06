package analyzer.model;

import java.util.ArrayList;
import java.util.List;

public final class ClassInfo {
    public String id;
    /** 안정 키: 정규화된 클래스명(확인 불가 시 단순명) */
    public String key;
    public String name;
    public String qualifiedName;
    public String packageName;
    /** CLASS | INTERFACE | ENUM | RECORD | ANNOTATION | UNKNOWN */
    public String kind;
    public boolean isAbstract;
    public Origin origin;
    /** TARGET(분석 대상) | REFERENCED(호출 대상으로 참조됨) */
    public String scope;
    public ClassRole role;
    /** 최종 역할을 결정한 근거 종류 */
    public String roleDecidedBy;
    public boolean roleConflict;
    public final List<RoleEvidence> roleEvidence = new ArrayList<>();
    public final List<String> annotations = new ArrayList<>();
    public final List<String> extendsTypes = new ArrayList<>();
    public final List<String> implementsTypes = new ArrayList<>();
    public SourceLocation location;
    public final List<String> methodIds = new ArrayList<>();
}
