package analyzer.resolver;

import analyzer.parser.ProjectSources;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 빈 이름 → source 클래스 (사용자: Util 이 Wcommon.getBean("productInfoBiz") 처럼 빈 이름으로 Service·Biz·DAO 를 꺼내 써서
 * 의존 관계가 안 보임). 추측하지 않는다 — 근거가 있는 것만:
 * <ol>
 *   <li>스테레오타입 어노테이션 값: {@code @Service("productInfoBiz")}·@Component·@Repository·@Controller·@RestController·@Named</li>
 *   <li>XML 빈 정의: {@code <bean id="productInfoBiz" class="com.x.ProductInfoBizImpl">} (id 또는 name)</li>
 *   <li>Spring 기본 이름: 값 없는 스테레오타입 어노테이션이 붙은 클래스의 단순명 첫 글자 소문자</li>
 * </ol>
 * 후보가 2개 이상이면 고르지 않는다 (AMBIGUOUS). 읽기 전용.
 */
public final class BeanIndex {

    public static final String ANNOTATION = "BEAN_ANNOTATION";
    public static final String XML = "BEAN_XML";
    public static final String DEFAULT_NAME = "BEAN_DEFAULT_NAME";

    private static final Set<String> STEREOTYPES = Set.of("Service", "Component", "Repository", "Controller", "RestController", "Named");

    /** @param fqn 찾은 클래스 (없으면 null), basis 근거, reason 설명, candidates 후보들 */
    public record Bean(String fqn, String basis, String reason, List<String> candidates) {
        public boolean found() {
            return fqn != null;
        }
    }

    private final ProjectSources sources;
    private final List<Path> xmlRoots;
    private final Map<String, Bean> cache = new HashMap<>();
    private Map<String, List<String[]>> xmlBeans; // 이름 → [fqn, 파일]

    public BeanIndex(ProjectSources sources, List<Path> xmlRoots) {
        this.sources = sources;
        this.xmlRoots = xmlRoots;
    }

    public Bean find(String name) {
        return cache.computeIfAbsent(name, this::compute);
    }

    private Bean compute(String name) {
        Map<String, String> found = new LinkedHashMap<>(); // fqn → 근거 설명
        String basis = null;
        // 1. 어노테이션 값 (빈 이름 문자열이 들어 있는 파일만 파싱)
        for (Path file : sources.allFiles()) {
            if (!mentions(file, "\"" + name + "\"")) {
                continue;
            }
            for (ClassOrInterfaceDeclaration ci : classes(file)) {
                for (AnnotationExpr a : ci.getAnnotations()) {
                    if (STEREOTYPES.contains(a.getName().getIdentifier()) && name.equals(value(a).orElse(null))) {
                        found.put(fqn(ci), "@" + a.getName().getIdentifier() + "(\"" + name + "\")");
                        basis = ANNOTATION;
                    }
                }
            }
        }
        // 2. XML 빈 정의
        if (found.isEmpty()) {
            for (String[] b : xml().getOrDefault(name, List.of())) {
                found.put(b[0], "<bean id=\"" + name + "\"> " + b[1]);
                basis = XML;
            }
        }
        // 3. 기본 이름 (값 없는 스테레오타입 어노테이션 + 단순명 첫 글자 소문자)
        if (found.isEmpty() && !name.isEmpty()) {
            String simple = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (var td : sources.findTypesBySimpleName(simple)) {
                if (td instanceof ClassOrInterfaceDeclaration ci && !ci.isInterface()
                        && ci.getAnnotations().stream().anyMatch(a -> STEREOTYPES.contains(a.getName().getIdentifier()) && value(a).isEmpty())) {
                    found.put(fqn(ci), "@" + ci.getAnnotations().get(0).getName().getIdentifier() + " 기본 이름 " + name);
                    basis = DEFAULT_NAME;
                }
            }
        }
        List<String> candidates = new ArrayList<>(found.keySet());
        if (found.size() == 1) {
            String fqn = candidates.get(0);
            return new Bean(fqn, basis, "빈 이름 \"" + name + "\" → " + fqn + " (" + found.get(fqn) + ")", candidates);
        }
        if (found.isEmpty()) {
            return new Bean(null, null, "빈 이름 \"" + name + "\" 정의를 source·XML 에서 찾지 못함 (어노테이션 값·<bean id>·기본 이름)", candidates);
        }
        return new Bean(null, basis, "빈 이름 \"" + name + "\" 후보가 " + found.size() + "개라 고르지 않음: " + candidates, candidates);
    }

    private static Optional<String> value(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr s && s.getMemberValue() instanceof StringLiteralExpr l) {
            return Optional.of(l.getValue());
        }
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals("value") && p.getValue() instanceof StringLiteralExpr l) {
                    return Optional.of(l.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private List<ClassOrInterfaceDeclaration> classes(Path file) {
        Optional<CompilationUnit> cu = sources.parse(file);
        return cu.map(c -> c.findAll(ClassOrInterfaceDeclaration.class)).orElse(List.of());
    }

    private static String fqn(ClassOrInterfaceDeclaration ci) {
        return ci.getFullyQualifiedName().orElse(ci.getNameAsString());
    }

    private static boolean mentions(Path file, String text) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(text);
        } catch (IOException | java.io.UncheckedIOException e) {
            try {
                return new String(Files.readAllBytes(file), java.nio.charset.Charset.forName("MS949")).contains(text);
            } catch (IOException e2) {
                return false;
            }
        }
    }

    private static final Pattern BEAN = Pattern.compile("<bean\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("\\b(id|name|class)\\s*=\\s*\"([^\"]*)\"");

    /** XML 파일들의 <bean id|name class> (읽기 전용, 정규식 수준) */
    private Map<String, List<String[]>> xml() {
        if (xmlBeans != null) {
            return xmlBeans;
        }
        xmlBeans = new HashMap<>();
        for (Path root : xmlRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    String text;
                    try {
                        text = Files.readString(f, StandardCharsets.UTF_8);
                    } catch (IOException | java.io.UncheckedIOException e) {
                        text = new String(Files.readAllBytes(f), java.nio.charset.Charset.forName("MS949"));
                    }
                    if (!text.contains("<bean")) {
                        continue;
                    }
                    Matcher m = BEAN.matcher(text);
                    while (m.find()) {
                        Map<String, String> attrs = new HashMap<>();
                        Matcher a = ATTR.matcher(m.group(1));
                        while (a.find()) {
                            attrs.put(a.group(1), a.group(2));
                        }
                        String cls = attrs.get("class");
                        if (cls == null || cls.isBlank()) {
                            continue;
                        }
                        List<String> names = new ArrayList<>();
                        if (attrs.containsKey("id")) {
                            names.add(attrs.get("id"));
                        }
                        if (attrs.containsKey("name")) {
                            names.addAll(List.of(attrs.get("name").split("[,;\\s]+")));
                        }
                        for (String n : names) {
                            if (!n.isBlank()) {
                                xmlBeans.computeIfAbsent(n, k -> new ArrayList<>()).add(new String[]{cls.trim(), f.getFileName().toString()});
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // 읽을 수 없는 폴더는 건너뛴다 (근거 없음 = 연결 안 함)
            }
        }
        return xmlBeans;
    }
}
