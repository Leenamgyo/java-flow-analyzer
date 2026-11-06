package analyzer.controlflow;

import analyzer.parser.TypeNames;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;

/** throw 타입이 catch 타입에 잡히는지 판정. 확정할 수 없으면 UNKNOWN (추측 금지). */
public final class ExceptionTypeMatcher {

    public enum Match { YES, NO, UNKNOWN }

    public Match match(Type thrown, Type caught) {
        String t = TypeNames.simple(thrown.asString());
        String c = TypeNames.simple(caught.asString());
        if (t.equals(c) || c.equals("Throwable")) {
            return Match.YES;
        }
        try {
            ResolvedType ct = caught.resolve();
            ResolvedType tt = thrown.resolve();
            return ct.isAssignableBy(tt) ? Match.YES : Match.NO;
        } catch (RuntimeException | StackOverflowError e) {
            return Match.UNKNOWN;
        }
    }
}
