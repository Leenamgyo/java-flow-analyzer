package analyzer;

import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 긴 메서드 보기: 구간 분할(A)·골격(B) — 기존 흐름도에서 떼어 낸 것이고 노드를 빠뜨리지 않는다 */
class FlowchartSectionsTest {

    private static final Pattern NODE_DEF = Pattern.compile("^\\s+([A-Za-z][A-Za-z0-9_]*)(\\[|\\{|\\(|>)", Pattern.MULTILINE);

    private static List<String> defs(String mmd) {
        List<String> out = new java.util.ArrayList<>();
        Matcher m = NODE_DEF.matcher(mmd);
        while (m.find()) {
            if (!m.group(1).startsWith("go_") && !m.group(1).startsWith("from_") && !m.group(1).startsWith("nav_")) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    @Test
    void sectionsPartitionEveryNodeOfTheMethod() {
        AnalysisResult r = Fixtures.analyze("ScpAppListAction", "execute");
        MermaidGraph full = new FlowchartGenerator().build(r);
        FlowchartGenerator.Sectioned sec = new FlowchartGenerator().renderSectioned(r, "TD", "dagre", 20);
        List<FlowchartGenerator.Part> entry = sec.sections().stream().filter(p -> p.file().startsWith("01-")).toList();
        assertTrue(entry.size() > 1, "작은 section-size 면 진입 메서드가 여러 구간"); // 01-…-s1, -s2 …
        assertTrue(entry.get(0).file().endsWith("-s1.mmd"), entry.get(0).file());
        Map<String, Integer> seen = new HashMap<>();
        for (FlowchartGenerator.Part p : entry) {
            defs(p.mermaid()).forEach(id -> seen.merge(id, 1, Integer::sum));
        }
        for (String id : full.idsUnder("sg_" + r.entryMethodId)) {
            if (!full.isSubgraph(id)) {
                assertEquals(1, seen.getOrDefault(id, 0), "구간마다 한 번씩: " + id);
            }
        }
        assertTrue(entry.get(1).mermaid().contains("◀ 구간 1 에서"), entry.get(1).mermaid());
        assertTrue(entry.get(0).mermaid().contains("▶ "), entry.get(0).mermaid());
        // 큰 size 면 메서드당 1 구간 (파일명에 -s 없음)
        FlowchartGenerator.Sectioned one = new FlowchartGenerator().renderSectioned(r, "TD", "dagre", 10_000);
        assertTrue(one.sections().stream().noneMatch(p -> p.file().contains("-s1")));
    }

    @Test
    void skeletonKeepsBranchesAndDetailCallsAndFoldsCode() {
        AnalysisResult r = Fixtures.analyze("ScpAppListAction", "execute");
        MermaidGraph full = new FlowchartGenerator().build(r);
        FlowchartGenerator.Sectioned sec = new FlowchartGenerator().renderSectioned(r, "TD", "dagre", 20);
        assertEquals("00-overview.mmd", sec.skeletons().get(0).file());
        String sk = sec.skeletons().get(1).mermaid();
        assertTrue(sec.skeletons().get(1).file().endsWith("-skeleton.mmd"));
        // 분기·세부 호출(==>) 은 남는다
        for (String id : full.idsUnder("sg_" + r.entryMethodId)) {
            MermaidGraph.Node n = full.nodeById(id);
            if (n != null && n.shape() == MermaidGraph.Shape.DIAMOND) {
                assertTrue(defs(sk).contains(id), "분기 노드: " + id);
            }
        }
        long calls = full.edges().stream().filter(e -> e.style() == MermaidGraph.EdgeStyle.CALL
                && full.idsUnder("sg_" + r.entryMethodId).contains(e.from())).count();
        assertEquals(calls, sk.lines().filter(l -> l.contains(" ==> ")).count());
        // 코드 사슬은 노드 하나로 합치되 코드 줄은 생략하지 않는다 ("⋯ 코드 n줄" 없음)
        assertFalse(sk.contains("⋯ 코드 "), sk);
        assertTrue(defs(sk).size() < full.idsUnder("sg_" + r.entryMethodId).size());
        for (String id : full.idsUnder("sg_" + r.entryMethodId)) {
            MermaidGraph.Node n = full.nodeById(id);
            if (n != null) {
                for (String line : n.lines()) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^#(\\d+) ").matcher(MermaidGraph.plain(line));
                    if (m.find()) { // 호출 코드 줄 #n — 골격에도 그대로 (렌더링에서 # = #35;)
                        assertTrue(sk.contains("#35;" + m.group(1) + " "), "골격에 빠진 줄: " + line);
                    }
                }
            }
        }
    }

    @Test
    void svgNodesLinkSkeletonSectionsAndOverview() {
        AnalysisResult r = Fixtures.analyze("ScpAppListAction", "execute");
        FlowchartGenerator.Sectioned sec = new FlowchartGenerator().renderSectioned(r, "TD", "dagre", 20);
        String s1 = sec.sections().get(0).mermaid();
        String s2 = sec.sections().get(1).mermaid();
        String base = sec.sections().get(0).file().replace("-s1.mmd", "");
        // 구간: ▶ 다음 구간, ◀ 이전 구간, ⌂ 골격
        assertTrue(s1.contains(".svg\" ") && s1.contains("href \"" + base + "-s2.svg\""), s1);
        assertTrue(s2.contains("href \"" + base + "-s1.svg\""), s2);
        assertTrue(s1.contains("click nav_skeleton href \"../skeleton/" + base + "-skeleton.svg\""), s1);
        // 골격: 접은 노드 → 상세 구간, ⌂ 전체 구조 / overview: 메서드 → 골격
        String sk = sec.skeletons().get(1).mermaid();
        assertTrue(sk.contains("href \"../sections/" + base + "-s1.svg\""), sk);
        assertTrue(sk.contains("click nav_overview href \"00-overview.svg\""), sk);
        assertTrue(sec.skeletons().get(0).mermaid().contains("click M1 href \"" + base + "-skeleton.svg\""));
    }
}
