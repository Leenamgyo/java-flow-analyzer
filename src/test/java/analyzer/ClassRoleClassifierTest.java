package analyzer;

import analyzer.classifier.ClassRoleClassifier;
import analyzer.model.ClassRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassRoleClassifierTest {
    private final ClassRoleClassifier c = new ClassRoleClassifier();

    @ParameterizedTest
    @CsvSource({
            "ScpAppListAction,ACTION", "AppController,CONTROLLER", "AppService,SERVICE", "AppServiceImpl,SERVICE",
            "AppBiz,BIZ", "AppBizImpl,BIZ", "AppDAO,DAO", "AppDao,DAO", "AppRepository,REPOSITORY",
            "AppMapper,MAPPER", "StringUtil,UTIL", "StringUtils,UTIL", "AppListCommand,VO",
            "OrderVO,VO", "OrderVo,VO", "OrderDTO,VO", "OrderDto,VO", "SearchForm,VO", "OrderValidator,VALIDATOR"})
    void suffix(String name, ClassRole expected) {
        var r = c.classify(name, List.of(), List.of());
        assertEquals(expected, r.role());
        assertEquals(expected == ClassRole.OTHER ? "NONE" : "SUFFIX", r.decidedBy());
    }

    @Test
    void annotationWinsOverSuffixAndRecordsConflict() {
        var r = c.classify("LegacyHelperService", List.of("Repository"), List.of());
        assertEquals(ClassRole.REPOSITORY, r.role());
        assertEquals("ANNOTATION", r.decidedBy());
        assertTrue(r.conflict());
        assertEquals(2, r.evidence().size());
    }

    @Test
    void restControllerAnnotation() {
        var r = c.classify("AppApi", List.of("RestController"), List.of());
        assertEquals(ClassRole.CONTROLLER, r.role());
        assertFalse(r.conflict());
    }

    @Test
    void supertypeBeforeSuffix() {
        var r = c.classify("ListHandler", List.of(), List.of("DispatchAction"));
        assertEquals(ClassRole.ACTION, r.role());
        assertEquals("SUPERTYPE", r.decidedBy());
    }
}
