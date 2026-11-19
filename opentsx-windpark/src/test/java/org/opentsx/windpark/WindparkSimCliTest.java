package org.opentsx.windpark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.iceberg.IcebergSeriesStore;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindparkSimCliTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> admins = new ArrayList<>();
    private final List<String> producers = new ArrayList<>();
    private static final Node BROKER = new Node(0, "127.0.0.1", 1);

    private final MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER);
    private final MockProducer<String, byte[]> producer =
            new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
    private Function<String, Admin> adminFactory = b -> {
        admins.add(b);
        return admin;
    };

    private int run(Map<String, String> env, String... args) {
        Function<String, Producer<String, byte[]>> producerFactory = b -> {
            producers.add(b);
            return producer;
        };
        return WindparkSim.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), adminFactory, producerFactory);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /** A 20-minute copy of the demo scenario, so tests stay fast. */
    private String shortScenario() throws Exception {
        Path p = dir.resolve("short.yaml");
        Files.writeString(p, Files.readString(Path.of("scenarios/ostsee-demo.yaml")).replace("duration: PT6H", "duration: PT20M"));
        return p.toString();
    }

    private String jdbcIceberg() {
        return "catalog-impl=org.apache.iceberg.jdbc.JdbcCatalog,uri=jdbc:sqlite:" + dir.resolve("c.db")
                + ",jdbc.schema-version=V1,warehouse=" + dir.resolve("wh").toUri();
    }

    private Set<String> topics() throws Exception {
        return new TreeSet<>(admin.listTopics().names().get());
    }

    @Test
    void aMisspelledOptionExitsTwoBeforeAnyAdminCall() throws Exception {
        assertEquals(2, run(Map.of(), "--scenario", shortScenario(), "--kafscale", "ks:9092", "--partitons", "12"));
        assertEquals("opentsx: --partitons: unknown option of windpark-sim; did you mean --partitions?\n", err());
        assertEquals("", out());
        assertEquals(List.of(), admins);
        assertEquals(Set.of(), topics());
    }

    @Test
    void aFlagWithoutValueOrAMalformedValueExitsTwo() throws Exception {
        String scenario = shortScenario();
        assertEquals(2, run(Map.of(), "--kafscale", "ks:9092", "--partitions", "six", "--scenario", scenario));
        assertEquals(2, run(Map.of(), "--kafscale", "ks:9092", "--partitions", "0", "--scenario", scenario));
        assertEquals(2, run(Map.of(), "--kafscale", "ks:9092", "--domain", "Wind", "--scenario", scenario));
        assertEquals(2, run(Map.of(), "--kafscale", "ks:9092", "--scenario"));
        assertEquals(2, run(Map.of(), "--kafscale", "ks:9092"));
        assertEquals("""
                opentsx: --partitions: "six" is not an int; expected a whole number from -2147483648 to 2147483647
                opentsx: opentsx.kafscale.partitions: "0" is not allowed; expected 1 or more
                opentsx: opentsx.domain: "Wind" is not allowed; expected lower-case letters, digits, _ and -, starting with a letter or a digit
                opentsx: --scenario: missing value; expected --scenario <path>
                opentsx: opentsx.windpark.scenario: not set; set it with --scenario, --conf opentsx.windpark.scenario=<value> or OPENTSX_WINDPARK_SCENARIO
                """, err());
        assertEquals("", out());
        assertEquals(List.of(), admins);
    }

    @Test
    void aScenarioThatBreaksARuleExitsTwoAndNamesTheFile() throws Exception {
        Path file = dir.resolve("no-limit.yaml");
        Files.writeString(file, Files.readString(Path.of("scenarios/ostsee-demo.yaml"))
                .replace("duration: PT40M, limitKw: 1500 }", "duration: PT40M }"));
        assertEquals(2, run(Map.of(), "--scenario", file.toString(), "--kafscale", "ks:9092"));
        assertEquals("opentsx: " + file + ": CURTAILMENT operation at PT1H: limitKw missing; "
                + "set limitKw to the power limit in kW, 0 or more\n", err());
        assertEquals(2, run(Map.of(), "--scenario", dir.resolve("none.yaml").toString()));
        assertTrue(err().endsWith("opentsx: " + dir.resolve("none.yaml") + ": no such file; give an existing file\n"), err());
        assertEquals(List.of(), admins);
    }

    @Test
    void theSinksKeyChoosesTheSinksSoAKafScaleFlagAloneOpensNoProducer() throws Exception {
        assertEquals(0, run(Map.of(), "--scenario", shortScenario(), "--conf", "opentsx.windpark.sinks=iceberg",
                "--iceberg", jdbcIceberg(), "--kafscale", "ks:9092"), err());
        assertEquals(List.of(), producers);
        assertEquals(List.of(), admins);
        assertTrue(out().startsWith("scenario ostsee-demo: 200 episodes, "), out());
        try (var store = IcebergSeriesStore.open(WindparkSim.icebergProperties(List.of(jdbcIceberg().split(","))), "tsx")) {
            assertEquals(100, store.listSeries(SeriesSelector.all()).size());
        }
    }

    @Test
    void aBootstrapFromTheEnvironmentAddsNoSink() throws Exception {
        Map<String, String> env = Map.of("OPENTSX_KAFSCALE_BOOTSTRAP", "ks:9092", "KAFSCALE_BOOTSTRAP", "ks:9092");
        assertEquals(0, run(env, "--scenario", shortScenario()), err());
        assertEquals(List.of(), admins);
        assertEquals(List.of(), producers);
        assertTrue(out().startsWith("scenario ostsee-demo: 200 episodes, "), out());
    }

    @Test
    void theKafScaleSinkProvisionsTheTopicsAndWritesOneSealedBucket() throws Exception {
        Map<String, String> env = Map.of("OPENTSX_KAFSCALE_BOOTSTRAP", "ks:9092");
        assertEquals(0, run(env, "--scenario", shortScenario(), "--sinks", "kafscale", "--partitions", "2"), err());
        assertEquals(List.of("ks:9092"), admins);
        assertEquals(List.of("ks:9092"), producers);
        assertEquals(new TreeSet<>(new TopicLayout("wind").all()), topics());
        assertEquals(2, admin.describeTopics(List.of(new TopicLayout("wind").episodes())).allTopicNames().get()
                .get(new TopicLayout("wind").episodes()).partitions().size());
        assertTrue(producer.closed());
        assertTrue(out().contains(" sealed: 200 episodes, checksum "), out());
    }

    @Test
    void anIcebergSinkWithoutWarehouseExitsTwoBeforeTheTopicsAreCreated() throws Exception {
        assertEquals(2, run(Map.of(), "--scenario", shortScenario(), "--kafscale", "ks:9092", "--iceberg", "type=rest,uri=x"));
        assertEquals("opentsx: opentsx.iceberg.properties: \"type=rest,uri=x\" sets no warehouse; "
                + "add warehouse=<location>, such as warehouse=s3://lake/\n", err());
        assertEquals(Set.of(), topics());
        assertEquals(List.of(), admins);
        assertEquals(List.of(), producers);
    }

    @Test
    void aGroundTruthPathThatCannotBeWrittenExitsTwoBeforeTheTopicsAreCreated() throws Exception {
        String scenario = shortScenario();
        Path missing = dir.resolve("missing/gt.json");
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--kafscale", "ks:9092", "--ground-truth", missing.toString()));
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--kafscale", "ks:9092", "--ground-truth", dir.toString()));
        assertEquals("opentsx: opentsx.windpark.groundTruth: \"" + missing + "\": " + dir.resolve("missing")
                + " is not an existing directory; give a file in an existing directory, such as ground-truth.json\n"
                + "opentsx: opentsx.windpark.groundTruth: \"" + dir + "\" is a directory; "
                + "give a file in an existing directory, such as ground-truth.json\n", err());
        assertEquals("", out());
        assertEquals(Set.of(), topics());
        assertEquals(List.of(), admins);
        assertEquals(List.of(), producers);
    }

    @Test
    void aRuntimeFailureBeforeTheRunLogsItsStackTraceAtDebug() throws Exception {
        Path file = Files.writeString(dir.resolve("file"), "a file where a directory belongs");
        Path scenario = file.resolve("scenario.yaml");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = run(Map.of(), "--scenario", scenario.toString(), "--kafscale", "ks:9092");
        } finally {
            System.setErr(stderr);
        }
        assertEquals(1, code);
        assertTrue(err().startsWith("opentsx: windpark-sim: java.io.UncheckedIOException: " + scenario
                + ": cannot read the scenario; caused by java.nio.file.FileSystemException: " + scenario), err());
        String logged = log.toString(StandardCharsets.UTF_8);
        assertTrue(logged.contains("DEBUG org.opentsx.windpark.WindparkSim - stack trace of the failed run"), logged);
        assertTrue(logged.contains("at org.opentsx.windpark.sim.Scenario.load("), logged);
        assertEquals(List.of(), admins);
    }

    @Test
    void contradictorySinksAndIcebergPropertiesExitTwo() throws Exception {
        String scenario = shortScenario();
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--sinks", "auto,kafscale", "--kafscale", "ks:9092"));
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--sinks", "iceberg,iceberg", "--iceberg", jdbcIceberg()));
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--sinks", "iceberg"));
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--iceberg", "type=rest,catalog-impl=x,warehouse=w"));
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--iceberg", "uri=x,warehouse=w"));
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--iceberg", "type=rest,type=jdbc,warehouse=w"));
        assertEquals(2, run(Map.of(), "--scenario", scenario, "--iceberg", "type=rest,s3,warehouse=w"));
        assertEquals("""
                opentsx: opentsx.windpark.sinks: "auto,kafscale" combines auto with other sinks; expected auto alone or a list of iceberg, kafscale
                opentsx: opentsx.windpark.sinks: "iceberg,iceberg" names a sink twice; name each sink once
                opentsx: opentsx.iceberg.properties: not set; set it with --iceberg, --conf opentsx.iceberg.properties=<value> or OPENTSX_ICEBERG_PROPERTIES
                opentsx: opentsx.iceberg.properties: "type=rest,catalog-impl=x,warehouse=w" sets both type and catalog-impl; set exactly one of them, such as type=rest
                opentsx: opentsx.iceberg.properties: "uri=x,warehouse=w" sets neither type nor catalog-impl; set exactly one of them, such as type=rest
                opentsx: opentsx.iceberg.properties: "type=rest,type=jdbc,warehouse=w" sets type twice; set each property once
                opentsx: opentsx.iceberg.properties: item "s3" is not key=value; expected catalog properties such as type=rest,uri=http://localhost:8181,warehouse=s3://lake/
                """, err());
        assertEquals(List.of(), admins);
    }

    @Test
    void helpPrintsTheGeneratedUsageOnStdoutWithExitZero() {
        assertEquals(0, run(Map.of(), "--help"));
        assertEquals(Registry.shipped().help("windpark-sim"), out());
        assertTrue(out().contains("  opentsx.kafscale.partitions  int = 6  --partitions  OPENTSX_KAFSCALE_PARTITIONS\n"), out());
        assertTrue(out().contains("  opentsx.windpark.sinks  list of enum = auto  one of auto|iceberg|kafscale  --sinks"), out());
        assertFalse(out().contains("parses its own flags"), out());
        assertEquals("", err());
        assertEquals(List.of(), admins);
    }

    @Test
    void theJobLoadsItsConfigurationInTheNamespaceOfTheBase() throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path base = Path.of("../deploy/k3s/base");
        JsonNode kustomization = yaml.readTree(base.resolve("kustomization.yaml").toFile());
        Map<String, String> literals = new HashMap<>();
        for (JsonNode literal : kustomization.path("configMapGenerator").get(0).path("literals")) {
            String[] kv = literal.asText().split("=", 2);
            literals.put(kv[0], kv[1]);
        }
        JsonNode pod = yaml.readTree(Path.of("../deploy/k3s/jobs/windpark-sim.yaml").toFile())
                .path("spec").path("template").path("spec");
        Map<String, String> env = new HashMap<>();
        if (pod.path("enableServiceLinks").asBoolean(true)) {
            // the variables Kubernetes sets for each Service of the namespace
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
        JsonNode container = pod.path("containers").get(0);
        for (JsonNode e : container.path("env")) {
            JsonNode ref = e.path("valueFrom").path("configMapKeyRef");
            env.put(e.path("name").asText(), ref.isMissingNode() ? e.path("value").asText() : literals.get(ref.path("key").asText()));
        }
        List<String> argv = new ArrayList<>();
        for (JsonNode a : container.path("args")) {
            String v = a.asText();
            for (Map.Entry<String, String> e : env.entrySet()) {
                v = v.replace("$(" + e.getKey() + ")", e.getValue());
            }
            argv.add(v);
        }
        OpenTsxConf conf = OpenTsxConf.load(argv, env, null).conf();
        assertEquals("kafscale.invalid:9092", conf.get("opentsx.kafscale.bootstrap"));
        assertEquals("/data/wind-index.tsv", conf.get("opentsx.windpark.index"));
    }

    @Test
    void versionPrintsTheVersionOnTheGivenStdoutAndReturnsZero() {
        assertEquals(0, run(Map.of(), "--version"));
        assertEquals(ConfCli.versionLine(), out());
        assertEquals("", err());
        assertEquals(List.of(), admins);
    }

    @Test
    void aRuntimeFailureExitsOneWithOneLine() throws Exception {
        adminFactory = b -> {
            throw new KafkaException("no resolvable bootstrap urls", new IllegalStateException(b + " is unknown"));
        };
        assertEquals(1, run(Map.of(), "--scenario", shortScenario(), "--kafscale", "ks:9092"));
        assertEquals("opentsx: windpark-sim: org.apache.kafka.common.KafkaException: no resolvable bootstrap urls; "
                + "caused by java.lang.IllegalStateException: ks:9092 is unknown\n", err());
        assertEquals("", out());
        assertEquals(List.of(), producers);
    }
}
