package analyzer.resolver;

import analyzer.model.Origin;
import com.github.javaparser.ast.body.TypeDeclaration;

/** 소스 문맥에서 확인한 타입 참조. qualifiedName 은 확인 불가 시 null. */
public record TypeRef(String qualifiedName, String simpleName, TypeDeclaration<?> declaration, Origin origin,
                      String basis) {

    public String displayName() {
        return qualifiedName != null ? qualifiedName : simpleName;
    }

    public boolean inSource() {
        return declaration != null;
    }
}
