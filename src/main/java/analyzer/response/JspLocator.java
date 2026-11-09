package analyzer.response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JSP 파일 찾기 (읽기 전용). 사용자는 파일명(또는 끝부분 경로)만 정하고, 위치는 웹 루트(WEB-INF 가 있는 폴더)에서 찾는다.
 * 웹 루트를 설정하지 않으면 source root 의 프로젝트 폴더(…/src 의 부모, 아니면 source root 의 부모)에서 WEB-INF 를 찾는다.
 * 0개·2개 이상이면 고르지 않는다 (추측 금지 — 후보를 보여 주고 사용자가 앞 폴더까지 적게 한다).
 */
public final class JspLocator {

    /** 찾지 않는 폴더 (빌드 결과·VCS·IDE) */
    private static final Set<String> SKIP = Set.of(".git", ".svn", ".idea", ".gradle", ".settings", "build", "target", "bin",
            "out", "classes", "node_modules");
    private static final int MAX_DEPTH = 8;

    public enum Status { FOUND, NOT_FOUND, AMBIGUOUS, NO_WEB_ROOT }

    public record Result(String requested, Status status, Path file, Path webRoot, List<Path> candidates) {
    }

    private final List<Path> webRoots;

    public JspLocator(List<Path> webRoots) {
        this.webRoots = webRoots;
    }

    public List<Path> webRoots() {
        return webRoots;
    }

    /** 설정 web-roots, 없으면 source root 들의 프로젝트 폴더에서 WEB-INF 의 부모 폴더 */
    public static JspLocator of(List<String> configured, List<Path> sourceRoots) {
        Set<Path> roots = new LinkedHashSet<>();
        if (configured != null && !configured.isEmpty()) {
            configured.forEach(c -> roots.add(Path.of(c).toAbsolutePath().normalize()));
            return new JspLocator(new ArrayList<>(roots));
        }
        Set<Path> projects = new LinkedHashSet<>();
        for (Path src : sourceRoots) {
            projects.add(projectDir(src.toAbsolutePath().normalize()));
        }
        for (Path p : projects) {
            if (p == null || !Files.isDirectory(p)) {
                continue;
            }
            try (Stream<Path> s = walk(p)) {
                s.filter(d -> Files.isDirectory(d) && d.getFileName() != null && d.getFileName().toString().equals("WEB-INF"))
                        .map(Path::getParent).sorted().forEach(roots::add);
            } catch (IOException e) {
                // 읽을 수 없는 폴더는 건너뛴다 (찾지 못하면 NO_WEB_ROOT 로 보고)
            }
        }
        return new JspLocator(new ArrayList<>(roots));
    }

    /** …/Vas/src/main/java → …/Vas, …/Vas/src → …/Vas, …/Vas/JavaSource → …/Vas */
    static Path projectDir(Path src) {
        for (Path p = src; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("src")) {
                return p.getParent();
            }
        }
        return src.getParent();
    }

    /** 이름(appList.jsp / vas/appList.jsp / /WEB-INF/jsp/vas/appList.jsp)으로 찾기 */
    public Result find(String requested) {
        if (webRoots.isEmpty()) {
            return new Result(requested, Status.NO_WEB_ROOT, null, null, List.of());
        }
        String name = requested.replace('\\', '/').replaceFirst("^/+", "");
        List<Path[]> hits = new ArrayList<>();
        for (Path root : webRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = walk(root)) {
                s.filter(Files::isRegularFile).forEach(f -> {
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    if (rel.equals(name) || rel.endsWith("/" + name)) {
                        hits.add(new Path[]{f, root});
                    }
                });
            } catch (IOException e) {
                // 건너뜀
            }
        }
        if (hits.size() == 1) {
            return new Result(requested, Status.FOUND, hits.get(0)[0], hits.get(0)[1], List.of());
        }
        return new Result(requested, hits.isEmpty() ? Status.NOT_FOUND : Status.AMBIGUOUS, null, null,
                hits.stream().map(h -> h[0]).toList());
    }

    /** JSP 안의 include 경로: "/" 로 시작하면 웹 루트 기준, 아니면 현재 파일 폴더 기준 */
    static Path resolveInclude(Path current, Path webRoot, String path) {
        String p = path.replace('\\', '/');
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        Path base = p.startsWith("/") ? webRoot : current.getParent();
        return base == null ? null : base.resolve(p.replaceFirst("^/+", "")).normalize();
    }

    private static Stream<Path> walk(Path root) throws IOException {
        return Files.walk(root, MAX_DEPTH).filter(p -> {
            for (Path part : root.relativize(p)) {
                if (SKIP.contains(part.toString())) {
                    return false;
                }
            }
            return true;
        });
    }
}
