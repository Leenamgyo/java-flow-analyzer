package analyzer.resolver;

import analyzer.model.Origin;
import analyzer.model.ResolutionStatus;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.List;

/**
 * 호출 1건의 해석 결과.
 *
 * @param sourceDeclaration origin=SOURCE 이고 RESOLVED 면 대상 메서드 선언(분석기 자체 AST)
 * @param libraryDeclaration 심볼 솔버가 source-root 밖에서 확정한 선언
 * @param declaringType 선언 타입 FQN(가능 시) 또는 단순명. 미해결이면 null
 * @param receiverType 수신 타입(정적 호출이면 그 타입). 확인 못하면 null
 */
public record CallTarget(ResolutionStatus status, String basis, String reason, Origin origin,
                         String declaringType, String receiverType,
                         MethodDeclaration sourceDeclaration, ResolvedMethodDeclaration libraryDeclaration,
                         List<String> candidates) {

    public static CallTarget unresolved(String basis, String reason) {
        return new CallTarget(ResolutionStatus.UNRESOLVED, basis, reason, Origin.UNKNOWN, null, null, null, null, List.of());
    }
}
