package org.opentsx.twin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.twin.house.HouseScenario;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The run hash is portable (TWIN-COUPLING-SPEC section 8): hash-relevant code uses no function whose result may
 * differ between JVMs, the pinned runs give the pinned hashes, and a JVM without libm intrinsics gives the same.
 */
class PortableRunHashTest {

    /** Main sources of the twin and of the model code on its classpath; every one of them is hash-relevant. */
    private static final List<Path> SOURCES = List.of(Path.of("src", "main", "java"),
            Path.of("..", "opentsx-model", "src", "main", "java"));

    private static final Map<String, Pattern> NOT_PORTABLE = Map.of(
            "java.lang.Math function that may round differently between JVMs", Pattern.compile(
                    "\\bMath\\s*(\\.|::)\\s*(exp|log|log10|log1p|expm1|pow|sin|cos|tan|asin|acos|atan|atan2|sinh|cosh"
                            + "|tanh|cbrt|hypot|toRadians|toDegrees)\\b|import\\s+static\\s+java\\.lang\\.Math\\."),
            "SplittableRandom, whose values the JDK fixes only within one program",
            Pattern.compile("\\bSplittableRandom\\b"),
            "floating-point stream reduction without a specified order of additions", Pattern.compile(
                    "\\.average\\s*\\(|summaryStatistics|summingDouble|averagingDouble|\\bDoubleStream\\b"
                            + "|mapToDouble\\s*\\([^;]*\\.sum\\s*\\(", Pattern.DOTALL));

    private static JsonNode computed;

    private static synchronized JsonNode computed() {
        if (computed == null) {
            computed = RunHashVectors.compute();
        }
        return computed;
    }

    @Test
    void hashRelevantSourcesUseOnlyPortableFunctions() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : SOURCES) {
            assertTrue(Files.isDirectory(root), root.toAbsolutePath() + " is missing");
            List<Path> java;
            try (Stream<Path> s = Files.walk(root)) {
                java = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            assertTrue(java.size() > 10, root + " has only " + java.size() + " sources");
            for (Path p : java) {
                String code = withoutCommentLines(Files.readString(p, StandardCharsets.UTF_8));
                NOT_PORTABLE.forEach((why, pattern) -> {
                    Matcher m = pattern.matcher(code);
                    while (m.find()) {
                        int line = code.substring(0, m.start()).split("\n", -1).length;
                        found.add(root.relativize(p) + ":" + line + " " + why + ": " + m.group().strip());
                    }
                });
            }
        }
        assertEquals(List.of(), found.stream().sorted().toList());
    }

    @Test
    void pinnedRunsGiveThePinnedHashes() throws IOException {
        JsonNode pinned;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("twin/run-hashes.v1.json")) {
            pinned = new ObjectMapper().readTree(in);
        }
        assertEquals(6, pinned.path("runs").size());
        assertEquals(pinned.path("runs"), computed().path("runs"));
    }

    @Test
    void jvmWithoutLibmIntrinsicsGivesTheSameHashes(@TempDir Path dir) throws Exception {
        assertFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:-UseLibmIntrinsic"),
                "this JVM must use the libm intrinsics");
        Path err = dir.resolve("stderr.txt");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic", "-cp", System.getProperty("java.class.path"),
                RunHashVectors.class.getName()).redirectError(err.toFile()).start();
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(5, TimeUnit.MINUTES), "child JVM did not finish");
        String log = Files.readString(err, StandardCharsets.UTF_8);
        assumeFalse(child.exitValue() != 0 && log.contains("Unrecognized VM option 'UseLibmIntrinsic'"),
                "this JVM has no libm intrinsics to switch off");
        assertEquals(0, child.exitValue(), log);
        assertEquals(computed().path("runs"), new ObjectMapper().readTree(out).path("runs"));
    }

    /** Text that enters the run id writes doubles the same way on Java 17 and 21 ({@code 2e23} reads 2.0E23). */
    @Test
    void runIdTextWritesDoublesTheSameWayOnEveryJvm() {
        TwinPlan plan = new TwinPlan("t", Instant.parse("2025-01-01T00:00:00Z"), Duration.ofMinutes(1), 1,
                List.of(new TwinPlan.AtomSpec("w", "weather", Map.of("meanC", 2e23), null)), List.of());
        assertEquals("3e5282d7a8f4a7b6", plan.hash());
        Instant at = Instant.parse("2025-01-01T00:00:00Z");
        assertEquals("oil delivery 2.0E23 l", HouseScenario.oilDelivery("tank", at, 2e23, Duration.ofHours(1))
                .get(0).reason());
        assertEquals("wood 1.0E23 Ster", HouseScenario.woodDelivery("store", at, 1e23, Duration.ofHours(1))
                .get(0).reason());
    }

    private static String withoutCommentLines(String source) {
        StringBuilder sb = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            String t = line.strip();
            sb.append(t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") ? "" : line).append('\n');
        }
        return sb.toString();
    }
}
