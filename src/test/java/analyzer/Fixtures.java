package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;

import java.util.List;
import java.util.function.Consumer;

final class Fixtures {
    static final String SOURCE_ROOT = "src/test/resources/sample-project/src/main/java";

    private Fixtures() {
    }

    static AnalyzerConfig config(String clazz, String method) {
        AnalyzerConfig cfg = new AnalyzerConfig();
        cfg.target.sourceRoot = SOURCE_ROOT;
        cfg.target.className = clazz;
        cfg.target.entryMethod = method;
        AnalyzerConfig.RequestParameterRule ru = new AnalyzerConfig.RequestParameterRule();
        ru.className = "RequestUtil";
        AnalyzerConfig.MethodRule m = new AnalyzerConfig.MethodRule();
        m.name = "getString";
        m.nameArgIndex = 1;
        m.defaultArgIndex = 2;
        m.type = "String";
        ru.methods.add(m);
        cfg.requestParameterRules.add(ru);
        return cfg;
    }

    static AnalysisResult analyze(String clazz, String method) {
        return analyze(clazz, method, c -> {
        });
    }

    static AnalysisResult analyze(String clazz, String method, Consumer<AnalyzerConfig> tweak) {
        AnalyzerConfig cfg = config(clazz, method);
        tweak.accept(cfg);
        return Main.analyze(cfg);
    }

    static MethodInfo method(AnalysisResult r, String classSimpleName, String signature) {
        return r.methods().stream()
                .filter(m -> r.classOf(m).name.equals(classSimpleName) && m.signature.equals(signature))
                .findFirst().orElseThrow(() -> new AssertionError("method not found: " + classSimpleName + "." + signature
                        + " in " + r.methods().stream().map(x -> r.classOf(x).name + "." + x.signature).toList()));
    }

    static List<CallSiteInfo> callsTo(AnalysisResult r, MethodInfo target) {
        return r.callSites.stream().filter(s -> target.id.equals(s.targetMethodId)).toList();
    }

    static List<ControlBlock> blocks(AnalysisResult r, BlockType type) {
        return r.blocks.stream().filter(b -> b.type == type).toList();
    }
}
