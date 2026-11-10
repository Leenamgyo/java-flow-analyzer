package analyzer.mermaid;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;

import java.util.List;
import java.util.Map;

/** 테스트에서 패키지 전용 HtmlFlowchart.voInputs 호출 */
public final class HtmlFlowchartAccess {
    private HtmlFlowchartAccess() {
    }

    public static List<Map<String, Object>> voInputs(AnalysisResult r, MermaidGraph g, CallSiteInfo s) {
        return HtmlFlowchart.voInputs(r, g, s, FlowchartGenerator.DEFAULT_LOGIC_ROLES);
    }
}
