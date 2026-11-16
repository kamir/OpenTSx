package org.opentsx.retrieval.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.retrieval.generate.Generators;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorLearningTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SECOND = 1_000_000L;

    private static JsonNode lessons() throws IOException {
        return JSON.readTree(Path.of("..", "docs", "learning", "library-operators.v1.json").toFile()).path("lessons");
    }

    private static JsonNode lesson(String operator) throws IOException {
        for (JsonNode lesson : lessons()) {
            if (operator.equals(lesson.path("operator").asText())) {
                return lesson;
            }
        }
        throw new AssertionError("missing generator lesson " + operator);
    }

    @Test
    void everyRuntimeGeneratorHasExactlyOneLearningContract() throws IOException {
        List<String> covered = StreamSupport.stream(lessons().spliterator(), false)
                .map(l -> l.path("operator").asText()).filter(op -> op.startsWith("gen.")).sorted().toList();
        assertEquals(Generators.IDS.stream().sorted().toList(), covered);
    }

    @Test
    void generatorStarterCodeCompilesAndRuns(@TempDir Path directory) throws Exception {
        for (String operator : Generators.IDS) {
            StringBuilder imports = new StringBuilder();
            StringBuilder statements = new StringBuilder();
            for (String line : lesson(operator).path("starter").path("code").asText().split("\n")) {
                (line.startsWith("import ") ? imports : statements).append(line).append('\n');
            }
            Path source = directory.resolve("LearningStarter.java");
            Files.writeString(source, imports + "public class LearningStarter { public static double[] run() throws Exception {\n"
                    + statements + "\nreturn values;\n}}\n");
            var diagnostics = new ByteArrayOutputStream();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                    "-classpath", System.getProperty("java.class.path"), "-d", directory.toString(), source.toString()),
                    diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
            try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
                var result = (double[]) loader.loadClass("LearningStarter").getMethod("run").invoke(null);
                assertArrayEquals(operator.equals("gen.ou") ? new double[]{10, 10, 10, 10} : new double[]{10, 12, 10, 8, 10},
                        result, 1e-12);
            }
        }
    }

    @Test
    void sineStarterHasThePredictedPeriodAndPhase() throws IOException {
        JsonNode data = lesson("gen.sine").path("starter").path("data");
        JsonNode params = data.path("params");
        double[] values = Generators.values("gen.sine", params, data.path("seed").asLong(),
                data.path("intervalMicros").asLong(), data.path("first").asLong(), data.path("count").asInt(), null, null);
        assertArrayEquals(new double[]{10, 12, 10, 8, 10}, values, 1e-12);
        var shifted = params.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) shifted).put("phase", 0.25);
        assertArrayEquals(new double[]{12, 10, 8, 10, 12},
                Generators.values("gen.sine", shifted, 7, SECOND, 0, 5, null, null), 1e-12);
        assertArrayEquals(Arrays.copyOfRange(values, 2, 5),
                Generators.values("gen.sine", params, 7, SECOND, 2, 3, null, null), 1e-12);
    }

    @Test
    void noiselessOuStarterAndRangeClippingHaveExactValues() throws IOException {
        JsonNode data = lesson("gen.ou").path("starter").path("data");
        JsonNode params = data.path("params");
        assertArrayEquals(new double[]{10, 10, 10, 10}, Generators.values("gen.ou", params,
                data.path("seed").asLong(), data.path("intervalMicros").asLong(), data.path("first").asLong(),
                data.path("count").asInt(), null, null));
        assertArrayEquals(new double[]{9, 9, 9, 9}, Generators.values("gen.ou", params, 7, SECOND, 0, 4, 0.0, 9.0));
        var noisy = params.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noisy).put("sigma", 2).put("reversion", 1);
        double[] all = Generators.values("gen.ou", noisy, 7, SECOND, 0, 8, null, null);
        assertArrayEquals(all, Generators.values("gen.ou", noisy, 7, SECOND, 0, 8, null, null));
        assertArrayEquals(Arrays.copyOfRange(all, 4, 8), Generators.values("gen.ou", noisy, 7, SECOND, 4, 4, null, null));
        assertFalse(Arrays.equals(all, Generators.values("gen.ou", noisy, 8, SECOND, 0, 8, null, null)));
    }

    @Test
    void generatorEpisodesHaveGridBoundariesAndExplicitSyntheticLineage() throws IOException {
        for (String operator : Generators.IDS) {
            JsonNode data = lesson(operator).path("starter").path("data");
            var target = new Generators.Target("learning", "signal", "learning.value", "1", operator, 7,
                    null, null, "learning-system");
            var episodes = Generators.episodes(target, data.path("params"), SECOND, 0, 601 * SECOND, Instant.EPOCH);
            assertEquals(List.of(600, 1), episodes.stream().map(e -> e.getCount()).toList());
            assertEquals(0, Micros.of(episodes.get(0).getTStart()));
            assertEquals(600 * SECOND, Micros.of(episodes.get(1).getTStart()));
            assertEquals(601 * SECOND, Micros.of(episodes.get(1).getTEnd()));
            for (var episode : episodes) {
                assertEquals("SYNTHETIC", episode.getLabels().get("origin"));
                assertEquals("demo", episode.getLabels().get("fidelity"));
                assertEquals(operator, episode.getLabels().get("generator"));
                assertEquals("1970-01-01T00:00:00Z", episode.getLabels().get("generatedAt"));
                assertTrue(Arrays.stream(Episodes.values(episode)).allMatch(Double::isFinite));
            }
            var again = Generators.episodes(target, data.path("params"), SECOND, 0, 601 * SECOND, Instant.EPOCH);
            assertEquals(episodes.stream().map(e -> e.getEpisodeId()).toList(), again.stream().map(e -> e.getEpisodeId()).toList());
        }
    }
}
