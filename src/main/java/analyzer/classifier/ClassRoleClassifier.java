package analyzer.classifier;

import analyzer.model.ClassRole;
import analyzer.model.RoleEvidence;
import analyzer.parser.TypeNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 클래스 역할 분류. 우선순위: 어노테이션 > 상속·구현 타입명 > 클래스명 Suffix.
 * 모든 근거를 기록하고, 근거끼리 역할이 다르면 conflict 로 표시한다.
 */
public final class ClassRoleClassifier {

    public record Classification(ClassRole role, String decidedBy, boolean conflict, List<RoleEvidence> evidence) {
    }

    private static final Map<String, ClassRole> ANNOTATIONS = new LinkedHashMap<>();
    /** Suffix 검사 순서 (긴 것 우선) */
    private static final Map<String, ClassRole> SUFFIXES = new LinkedHashMap<>();

    static {
        ANNOTATIONS.put("RestController", ClassRole.CONTROLLER);
        ANNOTATIONS.put("Controller", ClassRole.CONTROLLER);
        ANNOTATIONS.put("Service", ClassRole.SERVICE);
        ANNOTATIONS.put("Repository", ClassRole.REPOSITORY);
        ANNOTATIONS.put("Mapper", ClassRole.MAPPER);

        SUFFIXES.put("Action", ClassRole.ACTION);
        SUFFIXES.put("Controller", ClassRole.CONTROLLER);
        SUFFIXES.put("ServiceImpl", ClassRole.SERVICE);
        SUFFIXES.put("Service", ClassRole.SERVICE);
        SUFFIXES.put("BizImpl", ClassRole.BIZ);
        SUFFIXES.put("Biz", ClassRole.BIZ);
        SUFFIXES.put("DAO", ClassRole.DAO);
        SUFFIXES.put("Dao", ClassRole.DAO);
        SUFFIXES.put("Repository", ClassRole.REPOSITORY);
        SUFFIXES.put("Mapper", ClassRole.MAPPER);
        SUFFIXES.put("VO", ClassRole.VO);
        SUFFIXES.put("Vo", ClassRole.VO);
        SUFFIXES.put("DTO", ClassRole.VO);
        SUFFIXES.put("Dto", ClassRole.VO);
        SUFFIXES.put("Form", ClassRole.VO);
        SUFFIXES.put("Command", ClassRole.VO);
        SUFFIXES.put("Validator", ClassRole.VALIDATOR);
        SUFFIXES.put("Utils", ClassRole.UTIL);
        SUFFIXES.put("Util", ClassRole.UTIL);
    }

    public Classification classify(String simpleName, List<String> annotations, List<String> superTypes) {
        List<RoleEvidence> evidence = new ArrayList<>();
        for (String a : annotations) {
            ClassRole r = ANNOTATIONS.get(TypeNames.simple(a));
            if (r != null) {
                evidence.add(new RoleEvidence("ANNOTATION", "@" + TypeNames.simple(a), r));
            }
        }
        for (String s : superTypes) {
            ClassRole r = bySuffix(TypeNames.simple(s));
            if (r != null) {
                evidence.add(new RoleEvidence("SUPERTYPE", TypeNames.simple(s), r));
            }
        }
        ClassRole bySuffix = bySuffix(simpleName);
        if (bySuffix != null) {
            evidence.add(new RoleEvidence("SUFFIX", suffixOf(simpleName), bySuffix));
        }
        if (evidence.isEmpty()) {
            return new Classification(ClassRole.OTHER, "NONE", false, evidence);
        }
        RoleEvidence decided = evidence.get(0); // 리스트가 우선순위 순으로 구성됨
        boolean conflict = evidence.stream().anyMatch(e -> e.role() != decided.role());
        return new Classification(decided.role(), decided.kind(), conflict, evidence);
    }

    private static ClassRole bySuffix(String name) {
        for (var e : SUFFIXES.entrySet()) {
            if (name.endsWith(e.getKey()) && name.length() >= e.getKey().length()) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String suffixOf(String name) {
        for (String s : SUFFIXES.keySet()) {
            if (name.endsWith(s)) {
                return s;
            }
        }
        return null;
    }
}
