package analyzer.mermaid;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Mermaid CLI(mmdc)가 PATH 에 있으면 렌더링 검증을 수행한다. 없으면 생략. */
public final class MermaidCli {
    private MermaidCli() {
    }

    public static Optional<Path> find() {
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
            Process p = new ProcessBuilder(mmdc.get().toString(), "-i", mmd.toString(), "-o", out.toString())
                    .redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes());
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            Files.deleteIfExists(out);
            if (!done) {
                p.destroyForcibly();
                return "FAILED: mmdc 시간 초과";
            }
            return p.exitValue() == 0 ? "OK: mmdc 렌더링 성공" : "FAILED: " + log.strip();
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }
}
