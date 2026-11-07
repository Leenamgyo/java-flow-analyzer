package analyzer.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * source-root(들)의 Java 파일을 읽기 전용으로 인덱싱하고 필요할 때만 파싱한다.
 * 참조용으로 읽은 파일은 분석 결과에 자동 포함되지 않는다.
 * <p>멀티 모듈(예: actions/src/main/java + lib/src/main/java)은 루트를 여러 개 준다. FQN 은 모든 루트에서 찾고,
 * 같은 FQN 이 2개 이상 루트에 있으면 어느 것이 쓰이는지 확정할 수 없으므로 찾지 못한 것으로 처리한다(추측 금지).
 * 파일 경로 표시는 루트가 1개면 그 루트 기준, 여러 개면 루트들의 공통 상위 디렉터리 기준(모듈 경로가 보이도록).</p>
 */
public final class ProjectSources {
    private final List<Path> sourceRoots;
    private final Path sourceBase;
    private final JavaParser parser;
    private final CombinedTypeSolver typeSolver;
    private final Map<String, List<Path>> filesBySimpleName = new HashMap<>();
    private final Map<Path, Optional<CompilationUnit>> cache = new LinkedHashMap<>();
    private final List<String> parseProblems = new ArrayList<>();
    private final Map<String, List<String>> duplicateTypes = new java.util.TreeMap<>();
    /** 파일 경로 기준 최상위 타입 FQN → 파일들 (여러 루트에 있으면 중복) */
    private final Map<String, List<Path>> filesByFqn = new java.util.TreeMap<>();

    public ProjectSources(Path sourceRoot, List<Path> jars) {
        this(List.of(sourceRoot), jars);
    }

    public ProjectSources(List<Path> roots, List<Path> jars) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("source-root 가 필요합니다.");
        }
        this.sourceRoots = roots.stream().map(r -> r.toAbsolutePath().normalize()).distinct().toList();
        for (Path r : sourceRoots) {
            if (!Files.isDirectory(r)) {
                throw new IllegalArgumentException("source-root 디렉터리가 없습니다: " + r);
            }
        }
        this.sourceBase = commonBase(sourceRoots);
        ParserConfiguration plain = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        typeSolver = new CombinedTypeSolver();
        // jreOnly=true: 분석기 자신의 의존성(JavaParser, Jackson)을 원본 프로젝트 타입으로 오인하지 않는다.
        typeSolver.add(new ReflectionTypeSolver(true));
        for (Path r : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(r, plain));
        }
        for (Path jar : jars) {
            try {
                typeSolver.add(new JarTypeSolver(jar));
            } catch (IOException e) {
                throw new UncheckedIOException("classpath jar 를 읽을 수 없습니다: " + jar, e);
            }
        }
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        this.parser = new JavaParser(cfg);
        indexFiles();
    }

    private static Path commonBase(List<Path> roots) {
        if (roots.size() == 1) {
            return roots.get(0);
        }
        Path base = roots.get(0);
        while (base != null) {
            Path b = base;
            if (roots.stream().allMatch(r -> r.startsWith(b))) {
                return base;
            }
            base = base.getParent();
        }
        return roots.get(0).getRoot();
    }

    private void indexFiles() {
        for (Path root : sourceRoots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                        .sorted()
                        .forEach(p -> {
                            String fqn = root.relativize(p).toString().replace('\\', '/')
                                    .replaceFirst("\\.java$", "").replace('/', '.');
                            filesByFqn.computeIfAbsent(fqn, k -> new ArrayList<>()).add(p);
                            String simple = p.getFileName().toString().replaceFirst("\\.java$", "");
                            filesBySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(p);
                        });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** source-root 의 모든 .java 파일 (경로순, 결정적) */
    public List<Path> allFiles() {
        return filesBySimpleName.values().stream().flatMap(List::stream).sorted().toList();
    }

    /** 소스 루트 목록 (설정 순서) */
    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    /** 파일 경로 표시 기준 (루트 1개면 그 루트, 여러 개면 공통 상위) */
    public Path sourceBase() {
        return sourceBase;
    }

    /** 같은 FQN 이 여러 루트에 있어 해석하지 않은 타입 (FQN → 파일들) — 실제로 조회된 것만 */
    public Map<String, List<String>> duplicateTypes() {
        return duplicateTypes;
    }

    /** 여러 루트에 같은 경로(=같은 최상위 FQN)로 존재하는 타입 전체 (FQN → 표시 경로들) */
    public Map<String, List<String>> duplicateFqns() {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        filesByFqn.forEach((fqn, files) -> {
            if (files.size() > 1) {
                out.put(fqn, files.stream().map(this::relative).toList());
            }
        });
        return out;
    }

    public CombinedTypeSolver typeSolver() {
        return typeSolver;
    }

    public List<String> parseProblems() {
        return parseProblems;
    }

    public Optional<CompilationUnit> parse(Path file) {
        Path key = file.toAbsolutePath().normalize();
        return cache.computeIfAbsent(key, p -> {
            try {
                ParseResult<CompilationUnit> r = parser.parse(p);
                if (!r.isSuccessful()) {
                    parseProblems.add(relative(p) + ": " + r.getProblems());
                }
                return r.getResult();
            } catch (IOException e) {
                parseProblems.add(relative(p) + ": " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** 단순 클래스명으로 소스 타입을 찾는다(파일명 기준 + 중첩 타입). */
    public List<TypeDeclaration<?>> findTypesBySimpleName(String simpleName) {
        List<TypeDeclaration<?>> found = new ArrayList<>();
        for (Path p : filesBySimpleName.getOrDefault(simpleName, List.of())) {
            parse(p).ifPresent(cu -> cu.findAll(TypeDeclaration.class).stream()
                    .filter(t -> t.getNameAsString().equals(simpleName))
                    .forEach(t -> found.add((TypeDeclaration<?>) t)));
        }
        return found;
    }

    public Optional<TypeDeclaration<?>> findTypeByQualifiedName(String fqn) {
        // 최상위 타입 경로 → 점점 짧게 시도하여 중첩 타입도 찾는다.
        String[] parts = fqn.split("\\.");
        for (int i = parts.length; i >= 1; i--) {
            String top = String.join("/", java.util.Arrays.copyOfRange(parts, 0, i));
            List<Path> hits = sourceRoots.stream().map(r -> r.resolve(top + ".java")).filter(Files::isRegularFile).toList();
            if (hits.isEmpty()) {
                continue;
            }
            List<TypeDeclaration<?>> found = new ArrayList<>();
            for (Path p : hits) {
                Optional<CompilationUnit> cu = parse(p);
                if (cu.isEmpty()) {
                    continue;
                }
                for (TypeDeclaration<?> t : cu.get().findAll(TypeDeclaration.class)) {
                    if (t.getFullyQualifiedName().map(fqn::equals).orElse(false)) {
                        found.add(t);
                        break;
                    }
                }
            }
            if (found.size() > 1) {
                duplicateTypes.put(fqn, found.stream().map(this::relativeFile).toList());
                return Optional.empty();
            }
            return found.stream().findFirst();
        }
        return Optional.empty();
    }

    /** 다른 파서 인스턴스(심볼 솔버)가 만든 AST 노드를 이 인덱스의 동일 위치 메서드로 매핑한다. */
    public Optional<CallableDeclaration<?>> mapToOwnAst(Node foreign) {
        Optional<Path> path = foreign.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(CompilationUnit.Storage::getPath);
        if (path.isEmpty() || foreign.getRange().isEmpty()) {
            return Optional.empty();
        }
        var begin = foreign.getRange().get().begin;
        return parse(path.get()).flatMap(cu -> cu.findAll(CallableDeclaration.class).stream()
                .filter(m -> m.getRange().map(r -> r.begin.equals(begin)).orElse(false))
                .map(m -> (CallableDeclaration<?>) m)
                .findFirst());
    }

    public boolean isInSource(Node node) {
        return node.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(s -> isInSource(s.getPath()))
                .orElse(false);
    }

    public boolean isInSource(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return sourceRoots.stream().anyMatch(abs::startsWith);
    }

    public String relativeFile(Node node) {
        return node.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(s -> relative(s.getPath()))
                .orElse("<unknown>");
    }

    public String relative(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return isInSource(abs) ? sourceBase.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    public static boolean isMethod(CallableDeclaration<?> c) {
        return c instanceof MethodDeclaration;
    }
}
