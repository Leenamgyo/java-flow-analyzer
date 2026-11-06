package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallRelation;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.ResolutionStatus;

import java.util.List;

/** analysis.md — 공통 모델 요약 보고서 */
public final class AnalysisReportWriter {

    public String render(AnalysisResult r, String treeText, String mermaidStatus) {
        StringBuilder sb = new StringBuilder();
        ClassInfo target = r.clazz(r.targetClassId);
        MethodInfo entry = r.method(r.entryMethodId);

        sb.append("# 정적 분석 결과: ").append(target.name).append("\n\n");
        sb.append("> 이 문서와 YAML/트리/Mermaid 는 모두 동일한 분석 모델에서 생성되었습니다. ID(C*, M*, CALL*, 블록 ID)는 모든 파일에서 동일합니다.\n\n");

        sb.append("## 1. 분석 대상\n\n");
        sb.append("| 항목 | 값 |\n|---|---|\n");
        sb.append("| 클래스 | `").append(target.qualifiedName).append("` (").append(target.id).append(", ").append(target.role.label()).append(") |\n");
        sb.append("| 진입 메서드 | `").append(entry.signature).append("` (").append(entry.id).append(") |\n");
        sb.append("| 진입 메서드 선택 방식 | ").append(r.entrySelection).append(" |\n");
        sb.append("| 소스 위치 | `").append(entry.location.file()).append(":").append(entry.location.beginLine())
                .append("-").append(entry.location.endLine()).append("` |\n");
        sb.append("| source-root | `").append(r.sourceRoot).append("` |\n\n");

        sb.append("## 2. 분석 설정 및 Depth\n\n");
        sb.append("| 설정 | 값 |\n|---|---|\n");
        r.settings.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        int maxSeen = r.callSites.stream().mapToInt(s -> s.depth).max().orElse(0);
        sb.append("\n- 실제 기록된 최대 호출 Depth: D").append(maxSeen)
                .append(" (동일 클래스 확장 시 max-depth 를 넘는 Depth 가 실제 값으로 기록됨)\n");
        sb.append("- 본문을 분석한 메서드: ").append(r.methods().stream().filter(m -> m.analyzed).count()).append("개\n\n");

        sb.append("## 3. 클래스 및 메서드\n\n");
        sb.append("| ID | 클래스 | 역할 | 근거 | 출처 |\n|---|---|---|---|---|\n");
        for (ClassInfo c : r.classes()) {
            String ev = String.join(", ", c.roleEvidence.stream().map(e -> e.kind() + ":" + e.value()).toList());
            sb.append("| ").append(c.id).append(" | `").append(c.name).append("` | ").append(c.role.label())
                    .append(c.roleConflict ? " ⚠️충돌" : "").append(" | ").append(ev.isEmpty() ? "-" : ev)
                    .append(" | ").append(c.origin).append(" |\n");
        }
        sb.append("\n| ID | 메서드 | 접근 | static | Depth | 분석 | 위치 |\n|---|---|---|---|---|---|---|\n");
        for (MethodInfo m : r.methods()) {
            sb.append("| ").append(m.id).append(" | `").append(r.classOf(m).name).append('.').append(m.signature)
                    .append("` | ").append(m.visibility.text()).append(m.modifiersImplicit ? " (암묵적)" : "")
                    .append(" | ").append(m.isStatic ? "Y" : "").append(" | D").append(m.depth)
                    .append(" | ").append(m.analyzed ? "전개" : m.notAnalyzedReason.name())
                    .append(" | ").append(m.location == null ? "-" : m.location.file() + ":" + m.location.beginLine())
                    .append(" |\n");
        }

        sb.append("\n## 4. Request Parameter\n\n");
        if (r.parameters.isEmpty()) {
            sb.append("확인된 요청 파라미터 없음.\n");
        } else {
            sb.append("| ID | 이름 | 이름 확인 | 추출 방식 | 변수 | 타입 | 기본값 | 메서드 | 위치 |\n|---|---|---|---|---|---|---|---|---|\n");
            for (RequestParameterInfo p : r.parameters) {
                sb.append("| ").append(p.id).append(" | ").append(p.name != null ? "`" + p.name + "`" : "REQUEST_BODY".equals(p.nameResolution) ? "(body)" : "(전체)")
                        .append(" | ").append(p.nameResolution)
                        .append(p.nameExpression != null && !"LITERAL".equals(p.nameResolution) ? " `" + p.nameExpression + "`" : "")
                        .append(" | ").append(p.extraction).append(" | ").append(n(p.variable)).append(" | ")
                        .append(n(p.type)).append(" | ").append(n(p.defaultValue)).append(" | ").append(p.sourceMethodId)
                        .append(" | ").append(p.location.file()).append(":").append(p.location.beginLine()).append(" |\n");
            }
        }

        sb.append("\n## 5. 호출 관계\n\n");
        sb.append("| ID | From | To | Depth | 호출 위치 | cycle |\n|---|---|---|---|---|---|\n");
        for (CallRelation rel : r.relations) {
            MethodInfo to = r.method(rel.toMethodId);
            sb.append("| ").append(rel.id).append(" | ").append(rel.fromMethodId).append(" | `")
                    .append(r.classOf(to).name).append('.').append(to.signature).append("` (").append(to.id).append(")")
                    .append(" | D").append(rel.depth).append(" | ").append(String.join(", ", rel.callSiteIds))
                    .append(" | ").append(rel.cycle ? "Y" : "").append(" |\n");
        }
        if (r.excludedExternalCalls > 0) {
            sb.append("\n- `include-external-library-calls: false` 로 제외된 외부 라이브러리 호출: ")
                    .append(r.excludedExternalCalls).append("건\n");
        }

        sb.append("\n## 6. 호출 트리 / 제어 블록 구조\n\n```text\n").append(treeText).append("```\n\n");
        long tries = r.blocks.stream().filter(b -> b.type == BlockType.TRY).count();
        sb.append("- 제어 블록 ").append(r.blocks.size()).append("개 (try ").append(tries).append("개)\n");

        sb.append("\n## 7. 주요 예외 처리 흐름\n\n");
        List<ControlBlock> exBlocks = r.blocks.stream()
                .filter(b -> b.type == BlockType.TRY || b.type == BlockType.THROW || b.type == BlockType.RETURN && !b.executesFinally.isEmpty())
                .toList();
        if (exBlocks.isEmpty()) {
            sb.append("예외 처리 블록 없음.\n");
        }
        for (ControlBlock b : exBlocks) {
            String loc = b.location.file() + ":" + b.location.beginLine();
            switch (b.type) {
                case TRY -> {
                    sb.append("- **").append(b.id).append("** try (").append(b.methodId).append(", ").append(loc).append(")");
                    for (String cid : b.catchBlockIds) {
                        ControlBlock c = r.block(cid);
                        sb.append("\n  - ").append(c.id).append(" catch ").append(String.join(" | ", c.exceptionTypes))
                                .append(c.rethrows ? " — 재던짐(rethrow)" : "").append(c.throwsNewException ? " — 새 예외 발생" : "");
                    }
                    if (b.finallyBlockId != null) {
                        ControlBlock f = r.block(b.finallyBlockId);
                        sb.append("\n  - ").append(f.id).append(" finally")
                                .append(f.containsReturn || f.containsThrow ? " — ⚠️ finally 내부 return/throw 로 원래 종료 흐름이 대체될 수 있음" : "");
                    }
                    if (!b.resources.isEmpty()) {
                        sb.append("\n  - try-with-resources: ")
                                .append(String.join(", ", b.resources.stream().map(x -> x.variable() + "(close#" + x.closeOrder() + ")").toList()));
                    }
                    sb.append('\n');
                }
                case THROW -> sb.append("- **").append(b.id).append("** `throw ").append(b.expression).append("` (").append(loc).append(") → ")
                        .append(b.caughtBy != null ? "caught by " + b.caughtBy
                                : Boolean.TRUE.equals(b.propagatesToCaller) ? "호출자로 전파" : "unresolved: " + b.exceptionReason)
                        .append(b.executesFinally.isEmpty() ? "" : ", finally 경유 " + b.executesFinally).append('\n');
                default -> sb.append("- **").append(b.id).append("** `return` (").append(loc).append(") → finally 경유 ")
                        .append(b.executesFinally).append('\n');
            }
        }
        sb.append("\n> 호출된 메서드 내부에서 발생할 수 있는 예외 타입은 정적으로 확정하지 않으므로 특정 호출을 catch 로 연결하지 않습니다.\n");

        sb.append("\n## 8. Unresolved 목록\n\n");
        List<CallSiteInfo> unresolved = r.callSites.stream().filter(s -> s.resolution != ResolutionStatus.RESOLVED).toList();
        List<ControlBlock> unresolvedThrows = r.blocks.stream()
                .filter(b -> b.type == BlockType.THROW && b.exceptionResolution == ResolutionStatus.UNRESOLVED).toList();
        if (unresolved.isEmpty() && unresolvedThrows.isEmpty()) {
            sb.append("없음.\n");
        } else {
            sb.append("| ID | 표현식 | 상태 | 사유 | 위치 |\n|---|---|---|---|---|\n");
            for (CallSiteInfo s : unresolved) {
                sb.append("| ").append(s.id).append(" | `").append(Labels.truncate(s.expression, 60).replace("|", "\\|"))
                        .append("` | ").append(s.resolution).append(" | ").append(n(s.resolutionReason).replace("|", "\\|"))
                        .append(" | ").append(s.location.file()).append(":").append(s.location.beginLine()).append(" |\n");
            }
            for (ControlBlock b : unresolvedThrows) {
                sb.append("| ").append(b.id).append(" | `throw ").append(Labels.truncate(b.expression, 50))
                        .append("` | 예외 흐름 UNRESOLVED | ").append(b.exceptionReason).append(" | ")
                        .append(b.location.file()).append(":").append(b.location.beginLine()).append(" |\n");
            }
        }
        long depthLimited = r.callSites.stream().filter(s -> s.expansion == Expansion.DEPTH_LIMIT).count();
        sb.append("\n- 깊이 제한으로 전개하지 않은 호출(미해결과 구분): ").append(depthLimited).append("건\n");

        sb.append("\n## 9. 분석 범위와 정적 분석 한계\n\n");
        sb.append("- 지정 클래스의 진입 메서드에서 도달한 메서드만 결과에 포함하며, 타입 해석을 위해 참조만 한 파일은 포함하지 않습니다.\n");
        sb.append("- 인터페이스/추상 메서드 호출은 선언 대상까지만 표시하며 런타임 구현체(Spring DI 등)는 추측하지 않습니다.\n");
        sb.append("- 같은 문장 내 중첩 호출은 Java 평가 순서(수신 객체 → 인자 → 호출)로 기록합니다. for 문은 init → 조건 → 본문 → update 순입니다.\n");
        sb.append("- 생성자 호출(`new`), 메서드 참조(`::`), 익명/로컬 클래스 본문, switch fall-through, Class Diagram 은 MVP 범위에서 제외되었습니다.\n");
        sb.append("- 심볼 솔버가 외부 jar 없이 실패하면 선언 타입+이름+인자 수로만 보수적으로 확인하며(동일 인자 수 오버로딩은 AMBIGUOUS), 근거는 `resolution-basis` 에 기록합니다.\n");
        sb.append("- Mermaid 렌더링 검증: ").append(mermaidStatus).append('\n');
        if (!r.notes.isEmpty()) {
            sb.append("\n### 분석 중 생략된 항목\n\n");
            r.notes.forEach(x -> sb.append("- ").append(x).append('\n'));
        }
        return sb.toString();
    }

    private static String n(String s) {
        return s == null ? "-" : s;
    }
}
