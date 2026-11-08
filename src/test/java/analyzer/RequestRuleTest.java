package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.model.AnalysisResult;
import analyzer.model.RequestParameterInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 프로젝트 고유 요청 읽기 메서드를 YAML(request-parameter-rules)로 바인딩 처리 */
class RequestRuleTest {

    static AnalyzerConfig config(Path tmp) throws Exception {
        Path f = tmp.resolve("rules.yml");
        Files.writeString(f, """
                target:
                  source-root: %s
                  class: GetVasAppList
                request-parameter-rules:
                  # class 생략 = 메서드 이름만으로 (상속받아 바로 부르는 메서드)
                  - methods:
                      - { name: getVasDeviceIdParameter, name-arg-index: 1, type: String }
                      - { name: getSystemId, type: String }                           # 이름 = 메서드명에서 (systemId)
                      - { name: getDeviceAbiType, parameter: abiType, type: String }  # 이름 고정
                  - class: StringUtil
                    methods:
                      - { name: getParam, name-arg-index: 1, type: String }
                      - { name: defVal, wraps: 0, default-arg-index: 1 }              # defVal(<요청 읽기>, 기본값)
                """.formatted(Path.of(Fixtures.SOURCE_ROOT).toAbsolutePath()));
        return ConfigLoader.load(f);
    }

    @Test
    void projectSpecificReadersAreRequestParameters(@TempDir Path tmp) throws Exception {
        AnalyzerConfig cfg = config(tmp);
        ConfigLoader.validate(cfg);
        AnalysisResult r = Main.analyze(cfg);
        List<String> names = r.parameters.stream().map(p -> p.name).toList();
        assertEquals(List.of("deviceId", "systemId", "abiType", "query", "isAutoUpdate"), names);
        RequestParameterInfo auto = r.parameters.get(4);
        assertEquals("\"0\"", auto.defaultValue);                       // 래퍼의 기본값
        assertEquals("isAutoUpdate", auto.variable);                    // 래퍼 결과를 받는 변수
        assertEquals("StringUtil.getParam → StringUtil.defVal", auto.extraction);
        RequestParameterInfo sys = r.parameters.get(1);
        assertEquals("CONFIG", sys.nameResolution);
        assertEquals("getSystemId", sys.extraction);
        // 흐름도: 한 덩어리 요청 파라미터 노드 (호출 노드가 아님)
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("요청 파라미터<br/>#35;1 String deviceId = request.deviceId<br/>#35;2 String systemId = request.systemId<br/>"
                + "#35;3 String abi = request.abiType<br/>#35;4 String query = request.query<br/>"
                + "#35;6 String isAutoUpdate = request.isAutoUpdate ?: #quot;0#quot;"), mmd); // 안쪽 getParam(#5)은 따로 없음
    }

    @Test
    void wrapsWithNameArgIndexIsAnError(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bad2.yml");
        Files.writeString(f, """
                target: { source-root: x, class: A }
                request-parameter-rules:
                  - methods:
                      - { name: defVal, wraps: 0, name-arg-index: 1 }
                """);
        AnalyzerConfig cfg = ConfigLoader.load(f);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.validate(cfg));
    }

    @Test
    void parameterAndNameArgIndexTogetherIsAnError(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("bad.yml");
        Files.writeString(f, """
                target: { source-root: x, class: A }
                request-parameter-rules:
                  - methods:
                      - { name: getX, name-arg-index: 1, parameter: x }
                """);
        AnalyzerConfig cfg = ConfigLoader.load(f);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.validate(cfg));
    }

    @Test
    void longLabelsAreNotTruncated() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "voBinding");
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertFalse(mmd.contains("…"), mmd);
    }
}
