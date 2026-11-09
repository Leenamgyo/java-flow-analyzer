package analyzer.mermaid;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Mermaid CLI(mmdc) 연동: 렌더링 검증, SVG 출력. mmdc 가 없으면 생략한다.
 * 위치는 설정 output.mermaid-cli(비우면 PATH 의 mmdc).
 */
public final class MermaidCli {
    /**
     * mmdc 설정(-c): 큰 흐름도도 그리도록 글자 수·간선 수 한계를 올린다. 이 두 값은 Mermaid 보안 설정이라
     * 흐름도 안 %%{init}%% 로는 바꿀 수 없고 초기화 설정으로만 바꿀 수 있다.
     */
    static final String CONFIG = "{\"maxTextSize\": 10000000, \"maxEdges\": 100000}";

    private MermaidCli() {
    }

    public static Optional<Path> find() {
        return find(null);
    }

    /** configured: 설정 output.mermaid-cli (비우면 PATH 에서 mmdc) */
    public static Optional<Path> find(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            return Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String dir : path.split(File.pathSeparator)) {
            Path p = Path.of(dir, "mmdc");
            if (Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** @return 검증 결과 메시지 */
    public static String validate(Path mmd) {
        Optional<Path> mmdc = find();
        if (mmdc.isEmpty()) {
            return "SKIPPED: Mermaid CLI(mmdc) 미설치 — 렌더링 검증 생략 (문법 구조 검증만 수행)";
        }
        try {
            Path out = Files.createTempFile("flowchart", ".svg");
            String result = svg(mmdc.get(), mmd, out, "white");
            Files.deleteIfExists(out);
            return result == null ? "OK: mmdc 렌더링 성공" : "FAILED: " + result;
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    /**
     * mmd → svg 렌더링.
     * @param background 배경색 (다크 테마면 어두운 색 — 밝은 간선·글자가 보이게)
     * @return 실패 사유, 성공이면 null
     */
    public static String svg(Path mmdc, Path mmd, Path out, String background) {
        try {
            Path cfg = Files.createTempFile("mermaid-config", ".json");
            Files.writeString(cfg, CONFIG);
            try {
                Process p = new ProcessBuilder(mmdc.toString(), "-i", mmd.toString(), "-o", out.toString(),
                        "-b", background, "-c", cfg.toString())
                        .redirectErrorStream(true).start();
                String log = new String(p.getInputStream().readAllBytes());
                if (!p.waitFor(300, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "mmdc 시간 초과";
                }
                return p.exitValue() == 0 && Files.exists(out) ? null : log.strip();
            } finally {
                Files.deleteIfExists(cfg);
            }
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
