package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.SeriesStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindparkMetadataTest {

    static {
        // surefire sets the default level to warn; the start line is logged at INFO
        System.setProperty("org.slf4j.simpleLogger.log.org.opentsx.openmetadata.WindparkMetadata", "info");
    }

    /** The park of {@link SimulatedPark} as a scenario file, so that the statistics of the simulation belong to it. */
    private static final String SCENARIO_YAML = """
            name: om
            seed: 11
            start: 2026-10-01T00:00:00Z
            duration: PT3H
            sampleInterval: PT1S
            episodeDuration: PT10M
            park:
              id: ostsee-1
              name: Ostsee 1
              latitude: 54.5
              longitude: 13.6
              turbineModel: GENERIC-3MW
              rows: 1
              columns: 3
              spacingRotorDiameters: 5
              rowSpacingRotorDiameters: 7
              wakeDecay: 0.05
            """;

    private static List<SeriesStats> stats;

    @TempDir
    Path tmp;

    private record Exit(int code, String out, String err) {
    }

    @BeforeAll
    static void simulate() {
        stats = SimulatedPark.simulate().stats;
    }

    /** A statistics topic of domain wind with the statistics of the simulated park; counts the consumers it hands out. */
    private static final class StatsTopic implements Function<String, Consumer<byte[], byte[]>> {
        final List<String> bootstraps = new ArrayList<>();

        @Override
        public Consumer<byte[], byte[]> apply(String bootstrap) {
            bootstraps.add(bootstrap);
            MockConsumer<byte[], byte[]> c = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
            String topic = new TopicLayout("wind").seriesStats();
            Node node = new Node(0, "localhost", 9092);
            c.updatePartitions(topic, List.of(new PartitionInfo(topic, 0, node, new Node[]{node}, new Node[]{node})));
            TopicPartition tp = new TopicPartition(topic, 0);
            c.updateBeginningOffsets(Map.of(tp, 0L));
            c.updateEndOffsets(Map.of(tp, (long) stats.size()));
            OpenTsxAvro avro = new OpenTsxAvro();
            c.schedulePollTask(() -> {
                for (int i = 0; i < stats.size(); i++) {
                    c.addRecord(new ConsumerRecord<>(topic, 0, i, null, avro.encode(stats.get(i))));
                }
            });
            return c;
        }
    }

    private Path scenario() throws IOException {
        Path p = tmp.resolve("om.yaml");
        if (!Files.exists(p)) {
            Files.writeString(p, SCENARIO_YAML);
        }
        return p;
    }

    /** Runs the command in this JVM; stderr also holds the log lines. */
    private static Exit run(Map<String, String> env, Function<String, Consumer<byte[], byte[]>> topic, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        PrintStream stderr = System.err;
        System.setErr(errStream);
        try {
            int code = WindparkMetadata.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                    errStream, topic);
            return new Exit(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(stderr);
        }
    }

    private static long files(Path dir, String part) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().contains(part)).count();
        }
    }

    @Test
    void aMisspelledOptionExitsTwoAndWritesNothing() throws IOException {
        Path dir = tmp.resolve("requests");
        Exit e = run(Map.of(), new StatsTopic(), "--scenario", scenario().toString(), "--dry-run", dir.toString(),
                "--namspace", "lake");
        assertEquals(new Exit(2, "", "opentsx: --namspace: unknown option of metadata; did you mean --namespace?\n"), e);
        assertEquals(0, files(dir, ""));
    }

    @Test
    void theTokenOnTheCommandLineIsRefusedAndNamesTheVariable() throws IOException {
        try (FakeOpenMetadata om = new FakeOpenMetadata()) {
            Exit e = run(Map.of(), new StatsTopic(), "--scenario", scenario().toString(), "--om", om.uri().toString(),
                    "--token", "test-token");
            assertEquals(new Exit(2, "", "opentsx: --token: secret keys are not accepted on the command line; "
                    + "set OPENTSX_OPENMETADATA_TOKEN or OPENMETADATA_JWT or put opentsx.openmetadata.token "
                    + "in a properties file\n"), e);
            assertEquals(0, om.requests);
        }
    }

    @Test
    void theTokenFromOpenmetadataJwtIsUsedAndMaskedInTheStartLine() throws IOException {
        try (FakeOpenMetadata om = new FakeOpenMetadata()) {
            Exit e = run(Map.of("OPENMETADATA_JWT", "test-token"), new StatsTopic(), "--scenario", scenario().toString(),
                    "--om", om.uri().toString());
            assertEquals(0, e.code(), e.err());
            assertEquals("published 39 entities for wind farm ostsee-1\n", e.out());
            assertEquals(List.of(), om.violations);
            String start = e.err().lines().filter(l -> l.contains("starting metadata: ")).findFirst().orElse("");
            assertTrue(start.contains("opentsx.openmetadata.token=****** (environment)"), e.err());
            assertTrue(start.contains("opentsx.openmetadata.url=" + om.uri() + " (commandLine)"), start);
            assertFalse(e.err().contains("test-token"), e.err());
        }
    }

    @Test
    void theProfilesSwitchOffReadsNoStatistics() throws IOException {
        Path dir = tmp.resolve("requests");
        StatsTopic topic = new StatsTopic();
        Exit e = run(Map.of(), topic, "--scenario", scenario().toString(), "--dry-run", dir.toString(),
                "--kafscale", "localhost:9092", "--profiles", "never");
        assertEquals(0, e.code(), e.err());
        assertEquals("published 39 entities for wind farm ostsee-1\n", e.out());
        assertEquals(List.of(), topic.bootstraps);
        assertEquals(0, files(dir, "tableProfile"));
    }

    @Test
    void theBootstrapOnTheCommandLinePublishesProfilesAndSixtyMinutesIsTheHourlyWindow() throws IOException {
        Path dir = tmp.resolve("requests");
        StatsTopic topic = new StatsTopic();
        Exit e = run(Map.of(), topic, "--scenario", scenario().toString(), "--dry-run", dir.toString(),
                "--kafscale", "localhost:9092", "--profile-window", "PT60M", "--dq-window", "10m");
        assertEquals(0, e.code(), e.err());
        assertTrue(e.out().startsWith("published 39 entities for wind farm ostsee-1, 3 profiles, "), e.out());
        assertEquals(List.of("localhost:9092"), topic.bootstraps);
        assertEquals(3, files(dir, "tableProfile"));
        assertTrue(files(dir, "testCaseResults") > 0);
    }

    @Test
    void aBootstrapThatFailsLeavesNoRequest() throws IOException {
        Path dir = tmp.resolve("requests");
        Exit e = run(Map.of(), b -> {
            throw new KafkaException("Failed to construct kafka consumer");
        }, "--scenario", scenario().toString(), "--dry-run", dir.toString(), "--kafscale", "x");
        assertEquals(1, e.code(), e.err());
        assertEquals("", e.out());
        assertTrue(e.err().endsWith("\nopentsx: metadata: org.apache.kafka.common.KafkaException: Failed to construct "
                + "kafka consumer\n"), e.err());
        assertEquals(0, files(dir, ""));
    }

    @Test
    void aBootstrapFromTheEnvironmentDoesNotSwitchProfilesOn() throws IOException {
        Path dir = tmp.resolve("requests");
        StatsTopic topic = new StatsTopic();
        Exit e = run(Map.of("OPENTSX_KAFSCALE_BOOTSTRAP", "localhost:9092"), topic, "--scenario", scenario().toString(),
                "--dry-run", dir.toString());
        assertEquals(0, e.code(), e.err());
        assertEquals("published 39 entities for wind farm ostsee-1\n", e.out());
        assertEquals(List.of(), topic.bootstraps);
        assertEquals(0, files(dir, "tableProfile"));
    }

    @Test
    void profilesAlwaysReadsTheBootstrapFromTheEnvironmentAndNeedsOne() throws IOException {
        StatsTopic topic = new StatsTopic();
        Exit e = run(Map.of("KAFSCALE_BOOTSTRAP", "ks:9092"), topic, "--scenario", scenario().toString(),
                "--dry-run", tmp.resolve("a").toString(), "--profiles", "always");
        assertEquals(0, e.code(), e.err());
        assertEquals(List.of("ks:9092"), topic.bootstraps);
        assertEquals(3, files(tmp.resolve("a"), "tableProfile"));

        Exit none = run(Map.of(), topic, "--scenario", scenario().toString(), "--dry-run", tmp.resolve("b").toString(),
                "--profiles", "always");
        assertEquals(new Exit(2, "", "opentsx: opentsx.kafscale.bootstrap: not set; set it with --kafscale, "
                + "--conf opentsx.kafscale.bootstrap=<value> or OPENTSX_KAFSCALE_BOOTSTRAP\n"), none);
        assertEquals(0, files(tmp.resolve("b"), ""));
    }

    @Test
    void anEmptyOmNamesTheOption() throws IOException {
        Exit e = run(Map.of(), new StatsTopic(), "--scenario", scenario().toString(), "--om", "");
        assertEquals(new Exit(2, "", "opentsx: --om: empty value; set a value or leave the key unset\n"), e);
    }

    @Test
    void anOmWithoutSchemeIsRefusedBeforeTheFirstRequest() throws IOException {
        Exit e = run(Map.of(), new StatsTopic(), "--scenario", scenario().toString(), "--om", "localhost:8585/api");
        assertEquals(new Exit(2, "", "opentsx: opentsx.openmetadata.url: \"localhost:8585/api\" is not an http or https "
                + "URL; expected the API root such as http://localhost:8585/api\n"), e);
    }

    @Test
    void omOrDryRunIsRequiredAndNotBoth() throws IOException {
        Exit neither = run(Map.of(), new StatsTopic(), "--scenario", scenario().toString());
        assertEquals(new Exit(2, "", "opentsx: opentsx.openmetadata.url: not set; set it with --om, --conf "
                + "opentsx.openmetadata.url=<value> or OPENTSX_OPENMETADATA_URL, or write the requests to a directory "
                + "with --dry-run\n"), neither);
        Path dir = tmp.resolve("requests");
        Exit both = run(Map.of("OPENTSX_OPENMETADATA_URL", "http://localhost:8585/api"), new StatsTopic(),
                "--scenario", scenario().toString(), "--dry-run", dir.toString());
        assertEquals(new Exit(2, "", "opentsx: opentsx.openmetadata.dryRunDir: set together with "
                + "opentsx.openmetadata.url; set only one of them: the directory for a dry run or the URL to publish to\n"), both);
        assertEquals(0, files(dir, ""));
    }

    @Test
    void aBadDomainIsRefusedAndKeepsTheCause() throws IOException {
        OpenTsxConf conf = OpenTsxConf.load(List.of("metadata", "--scenario", scenario().toString(), "--dry-run",
                tmp.resolve("r").toString(), "--domain", "Wind!"), Map.of(), null).conf();
        OpenTsxConfException e = assertThrows(OpenTsxConfException.class, () -> WindparkMetadata.check(conf));
        assertEquals("opentsx.domain: \"Wind!\" is not allowed; expected lower-case letters, digits, _ and -, starting "
                + "with a letter or a digit", e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    void aMissingValueAndAMalformedValueExitTwo() throws IOException {
        Exit missing = run(Map.of(), new StatsTopic(), "--dry-run", tmp.toString(), "--scenario");
        assertEquals(new Exit(2, "", "opentsx: --scenario: missing value; expected --scenario <path>\n"), missing);
        Exit malformed = run(Map.of(), new StatsTopic(), "--scenario", scenario().toString(), "--dry-run",
                tmp.resolve("r").toString(), "--profile-window", "PT60");
        assertEquals(new Exit(2, "", "opentsx: --profile-window: \"PT60\" is not a duration; expected ISO-8601 such as "
                + "PT10M or a whole number with the unit ms, s, m, min, h or d such as 10m, at most 999999999 days\n"), malformed);
        Exit zero = run(Map.of(), new StatsTopic(), "--scenario", scenario().toString(), "--dry-run",
                tmp.resolve("r").toString(), "--dq-window", "0s");
        assertEquals(new Exit(2, "", "opentsx: opentsx.openmetadata.dqWindow: \"0s\" is not allowed; expected a window "
                + "longer than zero such as PT10M\n"), zero);
    }

    /** The args of the container of a Job in deploy/k3s/jobs without the command word, with $(VAR) replaced. */
    private static List<String> jobArgs(String file, Map<String, String> vars) throws IOException {
        JsonNode container = new ObjectMapper(new YAMLFactory()).readTree(Path.of("../deploy/k3s/jobs", file).toFile())
                .path("spec").path("template").path("spec").path("containers").get(0);
        List<String> args = new ArrayList<>();
        for (JsonNode a : container.path("args")) {
            String v = a.asText();
            for (Map.Entry<String, String> e : vars.entrySet()) {
                v = v.replace("$(" + e.getKey() + ")", e.getValue());
            }
            args.add(v);
        }
        assertEquals("metadata", args.remove(0));
        return args;
    }

    /**
     * The environment of the pod of a Job in deploy/k3s/jobs in the namespace of deploy/k3s/base: its env entries with
     * the config map values of the base, and the variables Kubernetes sets for each Service of the base unless the pod
     * sets enableServiceLinks to false.
     */
    static Map<String, String> podEnv(String file) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path base = Path.of("../deploy/k3s/base");
        JsonNode kustomization = yaml.readTree(base.resolve("kustomization.yaml").toFile());
        Map<String, String> literals = new HashMap<>();
        for (JsonNode literal : kustomization.path("configMapGenerator").get(0).path("literals")) {
            String[] kv = literal.asText().split("=", 2);
            literals.put(kv[0], kv[1]);
        }
        JsonNode pod = yaml.readTree(Path.of("../deploy/k3s/jobs", file).toFile()).path("spec").path("template").path("spec");
        Map<String, String> env = new HashMap<>();
        if (pod.path("enableServiceLinks").asBoolean(true)) {
            for (JsonNode resource : kustomization.path("resources")) {
                for (JsonNode doc : yaml.readValues(yaml.createParser(base.resolve(resource.asText()).toFile()), JsonNode.class)
                        .readAll()) {
                    if (doc.path("kind").asText().equals("Service")) {
                        String name = doc.path("metadata").path("name").asText().toUpperCase().replace('-', '_');
                        String port = doc.path("spec").path("ports").get(0).path("port").asText();
                        env.put(name + "_SERVICE_HOST", "10.43.0.10");
                        env.put(name + "_SERVICE_PORT", port);
                        env.put(name + "_PORT", "tcp://10.43.0.10:" + port);
                    }
                }
            }
        }
        for (JsonNode e : pod.path("containers").get(0).path("env")) {
            JsonNode ref = e.path("valueFrom").path("configMapKeyRef");
            env.put(e.path("name").asText(), ref.isMissingNode() ? e.path("value").asText() : literals.get(ref.path("key").asText()));
        }
        return env;
    }

    @Test
    void theJobsLoadTheirConfigurationInTheNamespaceOfTheBase() throws IOException {
        for (String file : List.of("metadata.yaml", "metadata-publish.yaml")) {
            Map<String, String> env = podEnv(file);
            env.computeIfPresent("OM_URL", (k, v) -> "http://openmetadata.invalid:8585/api");
            List<String> argv = new ArrayList<>(List.of("metadata"));
            argv.addAll(jobArgs(file, env));
            assertEquals("/opt/opentsx/share/scenarios/ostsee-demo.yaml",
                    OpenTsxConf.load(argv, env, null).conf().get("opentsx.windpark.scenario"), file);
        }
    }

    @Test
    void theDryRunJobWritesTheRequestsWithoutProfiles() throws IOException {
        Map<String, String> env = Map.of("OPENTSX_SCENARIO", scenario().toString(), "OPENTSX_DOMAIN", "wind",
                "OPENTSX_EXTRA_ARGS", "");
        List<String> args = jobArgs("metadata.yaml", env);
        Path dir = tmp.resolve("om-requests");
        args.set(args.indexOf("--dry-run") + 1, dir.toString());
        StatsTopic topic = new StatsTopic();
        Exit e = run(env, topic, args.toArray(String[]::new));
        assertEquals(0, e.code(), e.err());
        assertEquals("published 39 entities for wind farm ostsee-1\n", e.out());
        assertEquals(List.of(), topic.bootstraps);
        assertEquals(0, files(dir, "tableProfile"));
    }

    @Test
    void thePublishJobPublishesTheProfiles() throws IOException {
        try (FakeOpenMetadata om = new FakeOpenMetadata()) {
            Map<String, String> env = Map.of("OPENTSX_SCENARIO", scenario().toString(), "OPENTSX_DOMAIN", "wind",
                    "OM_URL", om.uri().toString(), "KAFSCALE_BOOTSTRAP", "ks:9092", "OPENTSX_EXTRA_ARGS", "",
                    "OPENMETADATA_JWT", "test-token");
            StatsTopic topic = new StatsTopic();
            Exit e = run(env, topic, jobArgs("metadata-publish.yaml", env).toArray(String[]::new));
            assertEquals(0, e.code(), e.err());
            assertTrue(e.out().startsWith("published 39 entities for wind farm ostsee-1, 3 profiles, "), e.out());
            assertEquals(List.of("ks:9092"), topic.bootstraps);
            assertEquals(List.of(), om.violations);
        }
    }

    @Test
    void helpPrintsTheKeysOnStdout() {
        Exit e = run(Map.of(), new StatsTopic(), "--help");
        assertEquals(0, e.code());
        assertEquals("", e.err());
        assertTrue(e.out().startsWith("usage: opentsx metadata [options]\n"), e.out());
        assertTrue(e.out().contains("  opentsx.openmetadata.profiles  enum = auto  one of auto|always|never  --profiles"), e.out());
        assertFalse(e.out().contains("parses its own flags"), e.out());
    }

    @Test
    void versionPrintsTheVersionOnTheGivenStdoutAndReturnsZero() {
        assertEquals(new Exit(0, ConfCli.versionLine(), ""), run(Map.of(), new StatsTopic(), "--version"));
    }
}
