package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The processor descriptions are in step with the code, and the operator chapter is the one they render. */
class OperatorCatalogTest {

    static final Path ROOT = SystemDocTest.ROOT;
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void theDescriptionsAreInStepWithTheCode() {
        List<JsonNode> ops = OperatorCatalog.load(ROOT);
        assertFalse(ops.isEmpty());
        assertEquals(List.of(), OperatorCatalog.problems(ROOT, ops));
    }

    @Test
    void theChapterIsCurrent() throws IOException {
        String page = Files.readString(ROOT.resolve(OperatorCatalog.PAGE), StandardCharsets.UTF_8);
        assertEquals(OperatorCatalog.render(ROOT, OperatorCatalog.load(ROOT)), page,
                "regenerate: java -cp \"$(bin/opentsx classpath opentsx-retrieval)\" org.opentsx.retrieval.system.OperatorCatalog");
    }

    static List<String> problemsOf(String yaml) throws IOException {
        return OperatorCatalog.problems(ROOT, List.of(YAML.readTree(yaml)));
    }

    static void assertProblem(List<String> problems, String part) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(part)), "expected a problem with \"" + part + "\" in " + problems);
    }

    static final String BASE = """
            schema: opentsx.processor.v1
            id: test.op
            version: 1
            kind: transformer
            category: statistics
            title: Test
            inputs: [{ name: in, type: series }]
            outputs: [{ name: out, type: series }]
            """;

    @Test
    void anAvailableRuntimeMustNameCodeThatExists() throws IOException {
        assertProblem(problemsOf(BASE + "runtimes: { java: { status: available, class: org.opentsx.nope.Missing } }"),
                "test.op@java: no module has the source of org.opentsx.nope.Missing");
        assertProblem(problemsOf(BASE + "runtimes: { java: { status: available, class: org.opentsx.stats.StatsEngine#noSuchMethod } }"),
                "has no noSuchMethod");
        assertProblem(problemsOf(BASE + "runtimes: { processor: { status: available } }"), "test.op@processor: class is missing");
        assertProblem(problemsOf(BASE + "runtimes: { python: { status: available, class: opentsx.algorithms.dfa.NoSuchClass } }"),
                "defines no NoSuchClass");
        assertProblem(problemsOf(BASE + "runtimes: { python: { status: available, class: opentsx.algorithms.dfa.DFA#no_such_method } }"),
                "has no method no_such_method");
        assertProblem(problemsOf(BASE + "runtimes: { workbench: { status: available } }"), "the workbench has no operator 'test.op'");
    }

    @Test
    void aPlannedRuntimeNeedsItsPlanAndACurrentOneItsExample() throws IOException {
        assertProblem(problemsOf(BASE + "runtimes: { python: { status: planned } }"), "a runtime is planned but plan is missing");
        assertProblem(problemsOf(BASE + "plan: { file: EVOLUTION/TASK-999.md, item: P1 }\nruntimes: { python: { status: planned } }"),
                "plan file EVOLUTION/TASK-999.md does not exist");
        assertProblem(problemsOf(BASE + "runtimes: { workbench: { status: available, class: acf } }"),
                "an operator with a current runtime needs an example");
        assertProblem(problemsOf(BASE + "example: { source: README.md, chart: docs/user-manual/img/operators/none.png, shows: x }\n"
                + "runtimes: { workbench: { status: available, class: acf } }"), "has no usage:begin / usage:end block");
        assertProblem(problemsOf(BASE + "docs: docs/user-manual/99-none.md#x\nruntimes: { go: { status: none } }"),
                "docs docs/user-manual/99-none.md#x does not exist");
    }

    @Test
    void aLegacyJavaRuntimeNeedsNoExample() throws IOException {
        assertEquals(List.of(), problemsOf(BASE
                + "runtimes: { java: { status: available, class: org.opentsx.algorithms.statistics.GrangerCausality } }"));
    }

    @Test
    void theExampleCommandFollowsTheTestFile() {
        assertEquals("mvn -B -ntp -pl opentsx-stats test -Dtest=StatsExampleTest",
                OperatorCatalog.run("opentsx-stats/src/test/java/org/opentsx/stats/examples/StatsExampleTest.java"));
        assertEquals("(cd python-package && python -m pytest -q tests/examples/test_dfa_example.py)",
                OperatorCatalog.run("python-package/tests/examples/test_dfa_example.py"));
        assertEquals("(cd opentsx-saas-frontend && npx vitest run src/workbench/examples/fft.example.test.ts)",
                OperatorCatalog.run("opentsx-saas-frontend/src/workbench/examples/fft.example.test.ts"));
        assertNull(OperatorCatalog.run("docs/user-manual/README.md"));
    }

    @Test
    void theVersionIsTheProjectVersion() throws IOException {
        String pom = Files.readString(ROOT.resolve("pom.xml"));
        assertTrue(pom.contains("<version>" + OperatorCatalog.version(ROOT) + "</version>"));
    }
}
