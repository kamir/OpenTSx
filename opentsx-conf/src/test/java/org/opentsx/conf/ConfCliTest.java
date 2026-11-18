package org.opentsx.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** opentsx config list|show|get and version on the shipped registry: output, exit codes, precedence and secrets. */
class ConfCliTest {

    static final String ALPHA = "opentsx.stats.sketchAlpha";
    static final String TOKEN = "opentsx.openmetadata.token";

    @TempDir
    Path tmp;

    record Run(int exit, String out, String err) {
    }

    static Run run(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = ConfCli.run(Registry.shipped(), List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static String projectVersion() throws IOException {
        Matcher m = Pattern.compile("<parent>.*?<version>([^<]+)</version>", Pattern.DOTALL).matcher(Files.readString(Path.of("pom.xml")));
        assertTrue(m.find());
        return m.group(1);
    }

    @Test
    void versionPrintsTheStampedProjectVersion() throws IOException {
        String line = "opentsx " + projectVersion() + " (java)\n";
        assertEquals(new Run(0, line, ""), run(Map.of(), "version"));
        assertEquals(new Run(0, line, ""), run(Map.of(), "--version"));
        assertEquals(new Run(0, line, ""), run(Map.of(), "config", "show", "--version"));
    }

    @Test
    void helpExitsWithZero() {
        Run r = run(Map.of(), "--help");
        assertEquals(0, r.exit());
        assertTrue(r.out().startsWith("usage: opentsx <command> [options] [args]\n\ncommands:\n"), r.out());
        assertTrue(Pattern.compile("\n  config show +Print every key that has a value, with the source of the value\\.\n").matcher(r.out()).find(), r.out());
        Run show = run(Map.of(), "config", "show", "-h");
        assertEquals(0, show.exit());
        assertTrue(show.out().endsWith("  --output <text|json>        print a table or one line of JSON\n"), show.out());
    }

    @Test
    void aUsageErrorExitsWithTwoAndOneLineOnStderr() {
        assertEquals(new Run(2, "", "opentsx: opentsx.domian: unknown key; did you mean opentsx.domain?\n"),
                run(Map.of(), "config", "show", "--conf", "opentsx.domian=x"));
        assertEquals(new Run(2, "", "opentsx: OPENTSX_STATS_WINDOWS: item \"PT1X\" is not a duration; expected ISO-8601 such as PT10M"
                        + " or a whole number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days\n"),
                run(Map.of("OPENTSX_STATS_WINDOWS", "PT10M,PT1X"), "config", "show"));
        assertEquals(new Run(2, "", "opentsx: stats: not a command of org.opentsx.conf.ConfCli; run bin/opentsx stats\n"),
                run(Map.of(), "stats"));
        assertEquals(0, run(Map.of(), "config", "--help").exit());
    }

    @Test
    void aFileThatIsNotUtf8IsAConfigurationErrorWithExitTwo() throws IOException {
        Path file = tmp.resolve("job.conf");
        Files.write(file, new byte[]{'o', 'p', (byte) 0xC3, (byte) 0x28, '\n'});
        assertEquals(new Run(2, "", "opentsx: " + file + ": not valid UTF-8; save the file as UTF-8\n"),
                run(Map.of(), "config", "show", "--properties-file", file.toString()));
    }

    @Test
    void aFailedWriteToStdoutExitsWithOne() {
        OutputStream full = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("No space left on device");
            }
        };
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = ConfCli.run(Registry.shipped(), List.of("config", "list"), Map.of(), new PrintStream(full, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(1, exit);
        assertEquals("opentsx: stdout: cannot write the output; check that standard output is open and not full\n",
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aDanglingDefaultsFileIsAnErrorNotIgnored() throws IOException {
        Path confDir = Files.createDirectory(tmp.resolve("conf"));
        Files.createSymbolicLink(confDir.resolve("opentsx-defaults.conf"), tmp.resolve("missing.conf"));
        assertEquals(new Run(2, "", "opentsx: " + confDir + "/opentsx-defaults.conf: no such file; give an existing file\n"),
                run(Map.of("OPENTSX_CONF_DIR", confDir.toString()), "config", "show"));
    }

    @Test
    void helpOfACommandOutsideConfigSaysWhatTheJavaCommandReads() {
        assertTrue(Registry.shipped().help("stats").startsWith("usage: opentsx stats [options]\n"
                + "Run the streaming statistics processor on KafScale.\n"
                + "In the Java runtime, stats parses its own flags and does not read --conf, --properties-file, "
                + "OPENTSX_CONF_DIR or the canonical OPENTSX_ variables.\n"));
        assertFalse(Registry.shipped().help("config show").contains("In the Java runtime"));
    }

    @Test
    void configShowMasksSecretsInTextAndJson() {
        Map<String, String> env = Map.of("OPENMETADATA_JWT", "t0k3n-value", "OPENTSX_DOMAIN", "solar");
        Run text = run(env, "config", "show");
        assertEquals(0, text.exit());
        assertTrue(text.out().startsWith("KEY "), text.out());
        assertTrue(text.out().contains("\nopentsx.domain  "), text.out());
        assertTrue(Pattern.compile("\nopentsx\\.openmetadata\\.token +\\*{6} +environment\n").matcher(text.out()).find(), text.out());
        assertTrue(Pattern.compile("\nopentsx\\.domain +solar +environment\n").matcher(text.out()).find(), text.out());
        assertFalse(text.out().contains("t0k3n"), text.out());
        Run json = run(env, "config", "show", "--output", "json");
        assertEquals(0, json.exit());
        assertTrue(json.out().contains("{\"key\":\"opentsx.openmetadata.token\",\"value\":\"******\",\"source\":\"environment\"}"), json.out());
        assertTrue(json.out().startsWith("{\"schema\":\"opentsx.config.show.v1\",\"values\":[{"), json.out());
        assertTrue(json.out().endsWith("}]}\n") && json.out().indexOf('\n') == json.out().length() - 1, json.out());
        assertFalse(json.out().contains("t0k3n"), json.out());
        assertEquals(new Run(0, "******\n", ""), run(env, "config", "get", TOKEN));
    }

    @Test
    void aSecretOnTheCommandLineIsRejectedWithoutEchoingIt() {
        assertEquals(new Run(2, "", "opentsx: opentsx.openmetadata.token: secret keys are not accepted on the command line; "
                        + "set OPENTSX_OPENMETADATA_TOKEN or put opentsx.openmetadata.token in a properties file\n"),
                run(Map.of(), "config", "show", "-c", TOKEN + "=t0k3n-value"));
        String message = assertThrows(OpenTsxConfException.class,
                () -> OpenTsxConf.load(List.of("metadata", "--token", "t0k3n-value"), Map.of(), null)).getMessage();
        assertEquals("--token: secret keys are not accepted on the command line; set OPENTSX_OPENMETADATA_TOKEN or put "
                + "opentsx.openmetadata.token in a properties file", message);
        String malformed = assertThrows(OpenTsxConfException.class,
                () -> OpenTsxConf.load(List.of("market", "catalog"), Map.of("OPENTSX_DWD_HTTPS_PROXY", "a,\u0001"), null)).getMessage();
        assertEquals("OPENTSX_DWD_HTTPS_PROXY: value contains a control character; remove it", malformed);
    }

    @Test
    void toDebugStringMasksSecretsAndGetAllReturnsThem() {
        OpenTsxConf conf = new OpenTsxConf().set(TOKEN, "t0k3n-value").set("opentsx.domain", "solar");
        String debug = conf.toDebugString();
        assertTrue(debug.contains("\nopentsx.openmetadata.token=****** (code)\n"), debug);
        assertTrue(debug.contains("\nopentsx.domain=solar (code)\n"), debug);
        assertFalse(debug.contains("t0k3n"), debug);
        assertTrue(conf.getAll().contains(Map.entry(TOKEN, "t0k3n-value")));
        assertEquals("t0k3n-value", conf.get(TOKEN));
        List<String> keys = new ArrayList<>();
        conf.getAll().forEach(p -> keys.add(p.getKey()));
        assertEquals(keys.stream().sorted().toList(), keys);
    }

    @Test
    void loadSetsTheSlf4jSimpleLevelWhenASourceSetsTheLogLevel() throws IOException, InterruptedException {
        Path out = tmp.resolve("probe.out");
        Path err = tmp.resolve("probe.err");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"), LevelProbe.class.getName(),
                "stats", "--conf", "opentsx.log.level=debug")
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertEquals(0, p.waitFor(), Files.readString(err));
        assertEquals("before=null after=debug", Files.readString(out));
    }

    /** Runs in a fresh JVM, so that no logger exists yet and the property does not leak into the other tests. */
    public static class LevelProbe {
        public static void main(String[] args) {
            OpenTsxConf.load(List.of("stats"), Map.of(), null);
            String before = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");
            OpenTsxConf.load(List.of(args), Map.of(), null);
            System.out.print("before=" + before + " after=" + System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
        }
    }

    @Test
    void theWorkedPrecedenceExampleOfTheSpecification() throws IOException {
        Path confDir = Files.createDirectory(tmp.resolve("conf"));
        Files.writeString(confDir.resolve("opentsx-defaults.conf"), ALPHA + " 0.05\n");
        Path job = tmp.resolve("job.conf");
        Files.writeString(job, ALPHA + " = 0.04\n");
        Map<String, String> env = Map.of("OPENTSX_STATS_SKETCH_ALPHA", "0.03");
        List<String> withFile = List.of("stats", "--properties-file", job.toString());
        List<String> all = List.of("stats", "--alpha", "0.02", "--properties-file", job.toString());

        String dir = confDir.toString();
        OpenTsxConf code = OpenTsxConf.load(all, env, dir).conf().set(ALPHA, "0.06");
        assertEquals(List.of("0.06", "code"), List.of(code.get(ALPHA), code.source(ALPHA)));
        OpenTsxConf cli = OpenTsxConf.load(all, env, dir).conf();
        assertEquals(List.of("0.02", "commandLine"), List.of(cli.get(ALPHA), cli.source(ALPHA)));
        OpenTsxConf fromEnv = OpenTsxConf.load(withFile, env, dir).conf();
        assertEquals(List.of("0.03", "environment"), List.of(fromEnv.get(ALPHA), fromEnv.source(ALPHA)));
        OpenTsxConf props = OpenTsxConf.load(withFile, Map.of(), dir).conf();
        assertEquals(List.of("0.04", "propertiesFile"), List.of(props.get(ALPHA), props.source(ALPHA)));
        OpenTsxConf defaults = OpenTsxConf.load(List.of("stats"), Map.of(), dir).conf();
        assertEquals(List.of("0.05", "defaultsFile"), List.of(defaults.get(ALPHA), defaults.source(ALPHA)));
        OpenTsxConf none = OpenTsxConf.load(List.of("stats"), Map.of(), null).conf();
        assertEquals(List.of("0.01", "default"), List.of(none.get(ALPHA), none.source(ALPHA)));
        assertFalse(none.contains(ALPHA));
        assertTrue(defaults.contains(ALPHA));
        assertEquals(0.05, defaults.getDouble(ALPHA));

        Run show = run(Map.of("OPENTSX_CONF_DIR", dir), "config", "show");
        assertTrue(Pattern.compile("\nopentsx\\.stats\\.sketchAlpha +0\\.05 +defaultsFile\n").matcher(show.out()).find(), show.out());
    }

    @Test
    void typedGettersReadTheirTypeOnly() {
        OpenTsxConf conf = new OpenTsxConf();
        assertEquals(List.of(Duration.ofMinutes(10), Duration.ofHours(1)), conf.getDurationList("opentsx.stats.windows"));
        assertEquals(List.of("PT10M", "PT1H"), conf.getList("opentsx.stats.windows"));
        assertEquals(Duration.ofSeconds(20), conf.getDuration("opentsx.kafscale.checkTimeout"));
        assertEquals(8080, conf.getInt("opentsx.retrieval.port"));
        assertFalse(conf.getBoolean("opentsx.stats.once"));
        assertEquals("opentsx.domain: has type string; read it with get",
                assertThrows(OpenTsxConfException.class, () -> conf.getInt("opentsx.domain")).getMessage());
        assertEquals("opentsx.retrieval.port: has type int; read it with getInt",
                assertThrows(OpenTsxConfException.class, () -> conf.getLong("opentsx.retrieval.port")).getMessage());
        assertEquals("opentsx.market.mosmix.stations: has type list; read it with getList",
                assertThrows(OpenTsxConfException.class, () -> conf.getDurationList("opentsx.market.mosmix.stations")).getMessage());
        assertEquals("opentsx.kafscale.bootstrap: not set; set it with --kafscale, --conf opentsx.kafscale.bootstrap=<value> "
                        + "or OPENTSX_KAFSCALE_BOOTSTRAP",
                assertThrows(OpenTsxConfException.class, () -> conf.get("opentsx.kafscale.bootstrap")).getMessage());
        assertEquals("fallback", conf.get("opentsx.kafscale.bootstrap", "fallback"));
    }

    @Test
    void configListPrintsTheRegistry() throws IOException {
        Run json = run(Map.of(), "config", "list", "--output", "json");
        assertEquals(0, json.exit());
        assertEquals(Files.readString(RegistryTest.SOURCE, StandardCharsets.UTF_8), json.out());
        Run text = run(Map.of(), "config", "list");
        assertEquals(0, text.exit());
        List<String> lines = text.out().lines().toList();
        assertEquals(134, lines.size());
        assertTrue(Pattern.matches("KEY +TYPE +DEFAULT +FLAG +ENV", lines.get(0)), lines.get(0));
        assertTrue(Pattern.compile("\nopentsx\\.stats\\.sketchAlpha +double +0\\.01 +--alpha +OPENTSX_STATS_SKETCH_ALPHA\n")
                .matcher(text.out()).find(), text.out());
        int typeColumn = lines.get(0).indexOf("TYPE");
        assertTrue(lines.stream().allMatch(l -> l.charAt(typeColumn - 1) == ' ' && l.charAt(typeColumn) != ' '), text.out());
        assertTrue(Pattern.compile("\nopentsx\\.openmetadata\\.token +string +- +- +OPENTSX_OPENMETADATA_TOKEN, OPENMETADATA_JWT\n")
                .matcher(text.out()).find(), text.out());
    }
}
