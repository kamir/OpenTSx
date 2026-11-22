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
                            + "|mapToDouble\\s*\\([^;]*\\.sum\\s*\\(", Pattern.DOTALL),
            "text formatted with the default locale", Pattern.compile(
                    "\\bString\\s*\\.\\s*format\\s*\\(\\s*(?!(java\\.util\\.)?Locale\\b)|\\.formatted\\s*\\("
                            + "|\\bprintf\\s*\\(\\s*(?!(java\\.util\\.)?Locale\\b)|\\bto(Upper|Lower)Case\\s*\\(\\s*\\)"));

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
                found.addAll(notPortable(root.relativize(p).toString(), Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertEquals(List.of(), found);
    }

    /** The guard reads code on every line, also after a line break inside an expression, and skips only comments. */
    @Test
    void guardChecksCodeAndSkipsComments() {
        String source = String.join("\n",
                "/** Math.cos(x) in a doc comment */",
                "class F {",
                "    double f(double mean, double a, double x) {",
                "        String s = \"// no comment\" + 'x' + \"\\\" /*\" + Math.tan(x); // Math.sin(x)",
                "        return mean - a / 2",
                "                * Math.cos(x); /* Math.exp(x)",
                "        Math.log(x) */",
                "    }",
                "",
                "    String g(int m) {",
                "        return String.format(\"%02d\", m) + String.format(Locale.ROOT, \"%02d\", m);",
                "    }",
                "}");
        assertEquals(List.of("F.java:11 text formatted with the default locale: String.format(",
                "F.java:4 java.lang.Math function that may round differently between JVMs: Math.tan",
                "F.java:6 java.lang.Math function that may round differently between JVMs: Math.cos"),
                notPortable("F.java", source));
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

    /** Every call of {@code source} that {@link #NOT_PORTABLE} names, as {@code name:line why: call}, sorted. */
    private static List<String> notPortable(String name, String source) {
        String code = withoutComments(source);
        List<String> found = new ArrayList<>();
        NOT_PORTABLE.forEach((why, pattern) -> {
            Matcher m = pattern.matcher(code);
            while (m.find()) {
                int line = code.substring(0, m.start()).split("\n", -1).length;
                found.add(name + ":" + line + " " + why + ": " + m.group().strip());
            }
        });
        return found.stream().sorted().toList();
    }

    /** {@code source} with every comment blanked out; line breaks and string, text block and char literals stay. */
    private static String withoutComments(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && source.startsWith("//", i)) {
                for (; i < n && source.charAt(i) != '\n'; i++) {
                    sb.append(' ');
                }
            } else if (c == '/' && source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                assertTrue(end >= 0, "unterminated comment at character " + i);
                for (; i < end + 2; i++) {
                    sb.append(source.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '"' || c == '\'') {
                String quote = source.startsWith("\"\"\"", i) ? "\"\"\"" : String.valueOf(c);
                int j = i + quote.length();
                while (j < n && !source.startsWith(quote, j)) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                assertTrue(j < n, "unterminated literal at character " + i);
                j += quote.length();
                sb.append(source, i, j);
                i = j;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
