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
 * source-root 의 Java 파일을 읽기 전용으로 인덱싱하고 필요할 때만 파싱한다.
 * 참조용으로 읽은 파일은 분석 결과에 자동 포함되지 않는다.
 */
public final class ProjectSources {
    private final Path sourceRoot;
    private final JavaParser parser;
    private final CombinedTypeSolver typeSolver;
    private final Map<String, List<Path>> filesBySimpleName = new HashMap<>();
    private final Map<Path, Optional<CompilationUnit>> cache = new LinkedHashMap<>();
    private final List<String> parseProblems = new ArrayList<>();

    public ProjectSources(Path sourceRoot, List<Path> jars) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.sourceRoot)) {
            throw new IllegalArgumentException("source-root 디렉터리가 없습니다: " + this.sourceRoot);
        }
        ParserConfiguration plain = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        typeSolver = new CombinedTypeSolver();
        // jreOnly=true: 분석기 자신의 의존성(JavaParser, Jackson)을 원본 프로젝트 타입으로 오인하지 않는다.
        typeSolver.add(new ReflectionTypeSolver(true));
        typeSolver.add(new JavaParserTypeSolver(this.sourceRoot, plain));
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

    private void indexFiles() {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .sorted()
                    .forEach(p -> {
                        String simple = p.getFileName().toString().replaceFirst("\\.java$", "");
                        filesBySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(p);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path sourceRoot() {
        return sourceRoot;
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
            Path p = sourceRoot.resolve(top + ".java");
            if (Files.isRegularFile(p)) {
                Optional<CompilationUnit> cu = parse(p);
                if (cu.isPresent()) {
                    for (TypeDeclaration<?> t : cu.get().findAll(TypeDeclaration.class)) {
                        if (t.getFullyQualifiedName().map(fqn::equals).orElse(false)) {
                            return Optional.of(t);
                        }
                    }
                }
                return Optional.empty();
            }
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
                .map(s -> s.getPath().toAbsolutePath().normalize().startsWith(sourceRoot))
                .orElse(false);
    }

    public String relativeFile(Node node) {
        return node.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                .map(s -> relative(s.getPath()))
                .orElse("<unknown>");
    }

    public String relative(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(sourceRoot) ? sourceRoot.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    public static boolean isMethod(CallableDeclaration<?> c) {
        return c instanceof MethodDeclaration;
    }
}
