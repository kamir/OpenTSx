package org.opentsx.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorJobTest {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final long T0 = 1_790_812_800_000_000L;   // 2026-10-01T00:00:00Z
    static final long SECOND = 1_000_000L;

    /** A DAG as the workbench writes it. */
    static String dag(String nodes, String sinks) {
        return """
                # opentsx.dag.v1, written by the OpenTSx web workbench
                dag: wind-exp2-smart
                version: 1
                source:
                  kind: kafscale-broker
                  topics: [tsx.wind.episodes.v2]
                  series: [%s]
                checkpoint: { backend: file }
                nodes:
                  - id: decode
                    op: decode.avro-soe
                %s
                sinks:
                %s
                """.formatted(String.join(", ", SPEED.getSeriesId(), POWER.getSeriesId()), nodes.indent(2).stripTrailing(),
                sinks.indent(2).stripTrailing());
    }

    static final SeriesKey SPEED = SeriesKeys.of("wind.met.wind_speed", Map.of("park", "p", "turbine", "T01"), "m/s");
    static final SeriesKey POWER = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "p", "turbine", "T01"), "kW");
    static final SeriesKey OTHER = SeriesKeys.of("wind.rotor.speed", Map.of("park", "p", "turbine", "T01"), "rpm");

    static final String VECTOR_NODE = """
            - id: n1
              op: align.vector   # not in the stream operator catalog
              in: [decode]
              params: { every: PT60S, key: turbine, agg: MEAN, emit: any, names: [state] }
            """;
    static final String VECTOR_SINK = "- { from: n1, topic: tsx.wind.smart.turbine-state.vectors.v1, schema: opentsx.vector.v1 }\n";

    static VectorJob.Plan plan(String yaml) throws Exception {
        return VectorJob.of(YAML.readTree(yaml), "wind", "tsx.wind.episodes.v2");
    }

    @Test
    void readsTheVectorSinkOfAWorkbenchDag() throws Exception {
        VectorJob.Plan plan = plan(dag(VECTOR_NODE, VECTOR_SINK + "- { from: decode, topic: tsx.wind.smart.raw.metrics.v2 }\n"));
        assertEquals(1, plan.jobs().size());
        VectorJob job = plan.jobs().get(0);
        assertEquals("wind-exp2-smart", job.dag());
        assertEquals("tsx.wind.smart.turbine-state.vectors.v1", job.topic());
        assertEquals("turbine-state", job.name());
        assertEquals(List.of(SPEED.getSeriesId(), POWER.getSeriesId()), job.seriesIds());
        assertEquals(List.of("state"), job.inputNames());
        assertEquals(new VectorAligner.Params(Duration.ofSeconds(60), "turbine", VectorAligner.Agg.MEAN, false, null, false), job.params());
        assertEquals(1, plan.skipped().size());
        assertTrue(plan.skipped().get(0).startsWith("tsx.wind.smart.raw.metrics.v2"));
    }

    @Test
    void readsLatenessAndWhereToStart() throws Exception {
        VectorJob plain = plan(dag(VECTOR_NODE, VECTOR_SINK)).jobs().get(0);
        assertEquals(Duration.ZERO, plain.lateness());
        assertTrue(plain.fromStart());

        String late = dag(VECTOR_NODE.replace("emit: any", "emit: any, lateness: PT2M"), VECTOR_SINK)
                .replace("  kind: kafscale-broker", "  kind: kafscale-broker\n  start: latest");
        VectorJob job = plan(late).jobs().get(0);
        assertEquals(Duration.ofMinutes(2), job.lateness());
        assertEquals(false, job.fromStart());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> plan(late.replace("start: latest", "start: yesterday")));
        assertTrue(e.getMessage().contains("earliest or latest"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> plan(late.replace("PT2M", "2 minutes")));
        assertEquals("node n1: lateness: \"2 minutes\" is not a duration; expected an ISO-8601 duration such as PT2M", e.getMessage());
    }

    @Test
    void aDurationThatIsNoneIsRefusedWithItsFieldAndItsValue() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> plan(dag(VECTOR_NODE.replace("every: PT60S", "every: 10min"), VECTOR_SINK)));
        assertEquals("node n1: every: \"10min\" is not a duration; expected an ISO-8601 duration such as PT60S", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> plan(dag(VECTOR_NODE.replace("emit: any", "emit: any, fill: as-of, maxAge: 5min"), VECTOR_SINK)));
        assertEquals("node n1: maxAge: \"5min\" is not a duration; expected an ISO-8601 duration such as PT60S", e.getMessage());
    }

    @Test
    void anAggThatIsNoAggregateIsRefusedWithTheAllowedValues() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> plan(dag(VECTOR_NODE.replace("agg: MEAN", "agg: medain"), VECTOR_SINK)));
        assertEquals("node n1: agg: \"medain\" is not allowed; expected one of MEAN, LAST, FIRST, MIN, MAX, SUM, COUNT",
                e.getMessage());
    }

    @Test
    void aFillOrEmitThatIsNoneOfItsValuesIsRefusedWithTheAllowedValues() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> plan(dag(VECTOR_NODE.replace("emit: any", "emit: any, fill: asof"), VECTOR_SINK)));
        assertEquals("node n1: fill: \"asof\" is not allowed; expected one of none, as-of", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> plan(dag(VECTOR_NODE.replace("emit: any", "emit: completed"), VECTOR_SINK)));
        assertEquals("node n1: emit: \"completed\" is not allowed; expected one of any, complete", e.getMessage());
        VectorAligner.Params given = plan(dag(VECTOR_NODE.replace("emit: any", "emit: complete, fill: as-of"), VECTOR_SINK))
                .jobs().get(0).params();
        assertTrue(given.asOf() && given.complete(), given.toString());
        VectorAligner.Params defaults = plan(dag(VECTOR_NODE.replace("emit: any, ", ""), VECTOR_SINK)).jobs().get(0).params();
        assertTrue(!defaults.asOf() && !defaults.complete(), defaults.toString());
        VectorAligner.Params named = plan(dag(VECTOR_NODE.replace("emit: any", "emit: any, fill: none"), VECTOR_SINK)).jobs().get(0).params();
        assertEquals(defaults, named);
    }

    /** Below the microsecond of the time axis the clock does not move; beyond what a long holds in nanoseconds it overflows. */
    @Test
    void aDurationTheClockCannotCountIsRefusedWithItsRange() throws Exception {
        for (String every : List.of("PT0.0000001S", "PT0S", "P200000D")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> plan(dag(VECTOR_NODE.replace("every: PT60S", "every: " + every), VECTOR_SINK)));
            assertEquals("node n1: every: \"" + every + "\" is out of range; expected an ISO-8601 duration from PT0.000001S to"
                    + " PT2562047H47M16.854775807S, such as PT60S", e.getMessage());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> plan(dag(VECTOR_NODE.replace("emit: any", "emit: any, lateness: -P200000D"), VECTOR_SINK)));
        assertEquals("node n1: lateness: \"-P200000D\" is out of range; expected an ISO-8601 duration of at most"
                + " PT2562047H47M16.854775807S, such as PT2M", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> plan(dag(VECTOR_NODE.replace("emit: any", "emit: any, fill: as-of, maxAge: P200000D"), VECTOR_SINK)));
        assertEquals("node n1: maxAge: \"P200000D\" is out of range; expected an ISO-8601 duration of at most"
                + " PT2562047H47M16.854775807S, such as PT60S", e.getMessage());
    }

    @Test
    void refusesWhatThisRuntimeCannotRun() throws Exception {
        String viaOther = """
                - id: n1
                  op: norm.zscore
                  in: [decode]
                - id: n2
                  op: align.vector
                  in: [decode, n1]
                  params: { every: PT60S, key: turbine, agg: MEAN, emit: any, names: [raw, standardized] }
                """;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> plan(dag(viaOther,
                "- { from: n2, topic: tsx.wind.smart.x.vectors.v1, schema: opentsx.vector.v1 }\n")));
        assertTrue(e.getMessage().contains("node n1 (norm.zscore) is not available"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> plan(dag(VECTOR_NODE, "- { from: decode, topic: t.metrics.v2 }\n")));
        assertTrue(e.getMessage().contains("no vector sink"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> plan(dag(VECTOR_NODE,
                "- { from: n1, topic: tsx.other.smart.x.vectors.v1, schema: opentsx.vector.v1 }\n")));
        assertTrue(e.getMessage().contains("must be tsx.wind.smart.<name>.vectors.v1"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> plan(dag(VECTOR_NODE.replace("PT60S", "60"), VECTOR_SINK)));
        assertTrue(e.getMessage().contains("every: \"60\" is not a duration"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> VectorJob.of(
                YAML.readTree(dag(VECTOR_NODE, VECTOR_SINK)), "wind", "tsx.wind.other.v2"));
        assertTrue(e.getMessage().contains("this server holds tsx.wind.other.v2"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> VectorJob.of(YAML.readTree("just: text"), "wind", "t"));
        assertTrue(e.getMessage().contains("no opentsx.dag.v1"), e.getMessage());
    }

    @Test
    void recordsRoundTripAndComeBackAsSeries() throws Exception {
        VectorJob job = plan(dag(VECTOR_NODE, VECTOR_SINK)).jobs().get(0);
        List<String> components = List.of("power_active", "wind_speed");
        List<VectorAligner.Point> points = new ArrayList<>(List.of(
                new VectorAligner.Point(Map.of("turbine", "T01"), T0, T0 + 60 * SECOND, components, List.of(100.0, 4.0)),
                new VectorAligner.Point(Map.of("turbine", "T01"), T0 + 60 * SECOND, T0 + 120 * SECOND, components, List.of(300.0, 8.0))));
        // a third tick with a missing power value
        points.add(new VectorAligner.Point(Map.of("turbine", "T01"), T0 + 180 * SECOND, T0 + 240 * SECOND,
                List.of("power_active", "wind_speed"), java.util.Arrays.asList(null, 9.0)));

        List<VectorJson.Row> rows = new ArrayList<>();
        for (VectorAligner.Point p : points) {
            JsonNode record = VectorJson.record(job.name(), p, job.dag(), "abc");
            assertEquals("opentsx.vector.v1", record.path("schema").asText());
            assertEquals("PT60S", record.path("clock").asText());
            assertEquals(record.path("window").path("to").asText(), record.path("ts").asText());
            assertEquals("abc", record.path("provenance").path("specHash").asText());
            rows.add(VectorJson.parse(VectorJson.bytes(record)));
        }
        assertEquals("2026-10-01T00:01:00Z", VectorJson.record(job.name(), points.get(0), job.dag(), "abc").path("ts").asText());
        assertEquals(1, VectorJson.record(job.name(), points.get(2), job.dag(), "abc").path("missing").asInt());
        assertEquals("turbine=T01", VectorJson.keyText(points.get(0).key()));
        assertNull(VectorJson.parse("not json".getBytes()));
        assertNull(VectorJson.parse("{\"schema\":\"other\"}".getBytes()));

        // a rerun appends the first tick again with other values: the later row wins
        rows.add(new VectorJson.Row("turbine-state", Map.of("turbine", "T01"), T0, 60 * SECOND,
                List.of("power_active", "wind_speed"), new double[]{150, 5}));
        List<Episode> episodes = VectorStoreBridge.episodes(job.topic(), rows);
        assertEquals(2, episodes.size());
        Episode speed = episodes.get(1);
        assertEquals("smart.turbine-state.wind_speed", speed.getSeries().getMetric());
        assertEquals(Map.of("turbine", "T01", "vector", "turbine-state"), speed.getSeries().getTags());
        assertEquals(job.topic(), speed.getLabels().get("smartTopic"));
        assertEquals(60 * SECOND, speed.getIntervalMicros());
        double[] v = Episodes.values(speed);
        assertEquals(4, v.length, "ticks 0, 1, the gap at 2, and 3");
        assertEquals(5.0, v[0]);
        assertEquals(8.0, v[1]);
        assertTrue(Double.isNaN(v[2]));
        assertEquals(9.0, v[3]);
        double[] power = Episodes.values(episodes.get(0));
        assertEquals(150.0, power[0]);
        assertTrue(Double.isNaN(power[3]), "null in the record is a gap in the series");
    }

    @Test
    void vectorsDeployRefusesASeriesSinkWhoseSignalAnotherDeployedDagWritesAndExitsWith1(@TempDir Path dir) throws Exception {
        VectorProcessorTest.FakeBroker broker = new VectorProcessorTest.FakeBroker();
        broker.append(VectorProcessorTest.LAYOUT.dags(), 0, VectorProcessor.deployRecord("wind-flow-7",
                VectorProcessorTest.cleanDag("dq.checks", ""), "ops"));
        Node node = new Node(0, "127.0.0.1", 1);
        List<MockProducer<String, byte[]>> producers = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        java.util.function.Function<Path, Integer> deploy = file -> {
            out.reset();
            err.reset();
            return VectorProcessorApp.run(List.of("deploy", "--kafscale", "ks:9092", "--dag", file.toString()), Map.of(),
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                    b -> new MockAdminClient(List.of(node), node), b -> {
                        producers.add(new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()));
                        return producers.get(producers.size() - 1);
                    }, b -> broker.consumer());
        };
        String copy = VectorProcessorTest.cleanDag("dq.checks", "").replace("dag: wind-flow-7", "dag: wind-flow-8");
        assertEquals(1, deploy.apply(Files.writeString(dir.resolve("copy.dag.yaml"), copy)));
        assertEquals("opentsx: wind-flow-8: sink tsx.wind.clean.v2 signal park/T01/WTUR.W of system h2-chain is already written"
                + " by DAG wind-flow-7; stop that DAG first or choose another signal\n", err.toString(StandardCharsets.UTF_8));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(), producers, "nothing reaches the control topic");

        Path other = Files.writeString(dir.resolve("other.dag.yaml"), copy.replace("id: park/T01/WTUR.W", "id: park/T02/WTUR.W"));
        assertEquals(0, deploy.apply(other), err.toString(StandardCharsets.UTF_8));
        Path again = Files.writeString(dir.resolve("again.dag.yaml"),
                VectorProcessorTest.cleanDag("dq.checks", "").replace("every: PT1M", "every: PT5M"));
        assertEquals(0, deploy.apply(again), err.toString(StandardCharsets.UTF_8));
        assertEquals("deployed wind-flow-7 (control topic tsx.wind.dags.v1)\n", out.toString(StandardCharsets.UTF_8));
    }
}
