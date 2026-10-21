package org.opentsx.retrieval.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogTail;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.retrieval.EpisodeService;
import org.opentsx.retrieval.vector.VectorProcessor;
import org.opentsx.store.InMemorySeriesStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class KafkaStreamingIntegrationTest {

    private static final long T0 = 1_790_812_800_000_000L;
    private static final long SECOND = 1_000_000L;
    private static final SeriesKey SPEED = SeriesKeys.of("wind.speed", Map.of("turbine", "T01"), "m/s");
    private static final SeriesKey POWER = SeriesKeys.of("wind.power", Map.of("turbine", "T01"), "kW");
    private static final OpenTsxAvro AVRO = new OpenTsxAvro();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final class Installation implements AutoCloseable {
        final String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        final TopicLayout layout = new TopicLayout("stream-it-" + UUID.randomUUID().toString().substring(0, 8));
        final String token = UUID.randomUUID().toString();
        final KafkaProducer<String, byte[]> producer;
        final KafkaConsumer<byte[], byte[]> reader;
        final KafScaleFeed feed;
        final RetrievalServer server;

        Installation() throws Exception {
            try (Admin admin = KafScaleClients.admin(bootstrap)) {
                layout.provision(admin, 2, (short) 1);
                layout.provisionDagTopics(admin, (short) 1);
            }
            producer = KafScaleClients.producer(bootstrap);
            reader = KafScaleClients.consumer(bootstrap);
            InMemorySeriesStore store = new InMemorySeriesStore();
            Runnable[] invalidate = {() -> { }};
            feed = KafScaleFeed.start(bootstrap, layout.domain(), Duration.ofSeconds(1), store, 100, () -> invalidate[0].run());
            CompositeSeriesStore combined = new CompositeSeriesStore(store, feed::vectorStore);
            SeriesInventory inventory = new SeriesInventory(combined, SeriesInventory.Source.of("kafscale"), null, null, Duration.ZERO);
            invalidate[0] = inventory::invalidate;
            server = new RetrievalServer(new EpisodeService(combined), 0, inventory, new RetrievalServer.Dags() {
                @Override
                public JsonNode deploy(JsonNode dag, String text, String by) {
                    return feed.deploy(dag, text, by);
                }

                @Override
                public JsonNode stop(String name, String by) {
                    return feed.stop(name, by);
                }

                @Override
                public JsonNode list() {
                    return feed.dags();
                }
            }, true, token);
        }

        VectorProcessor processor(Path state) {
            VectorProcessor processor = new VectorProcessor(() -> KafScaleClients.consumer(bootstrap),
                    () -> KafScaleClients.producer(bootstrap), layout, state,
                    new VectorProcessor.Timing(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO,
                            Duration.ZERO, Duration.ofMillis(50), TIMEOUT));
            processor.sinkProvisioner(topic -> {
                try (Admin admin = KafScaleClients.admin(bootstrap)) {
                    layout.provisionTopic(admin, topic, (short) 1);
                }
            });
            return processor;
        }

        void append(SeriesKey series, int partition, long second, double... values) throws Exception {
            Episode episode = Episodes.regular(series, T0 + second * SECOND, SECOND, values)
                    .source("streaming-integration").createdAt(T0).build();
            producer.send(new ProducerRecord<>(layout.episodes(), partition, series.getSeriesId(), AVRO.encode(episode)))
                    .get(30, TimeUnit.SECONDS);
        }

        JsonNode deploy(String text) throws Exception {
            var response = DagRunEndpointTest.post(server, "/v1/dags/deploy", text, RetrievalServer.RUN_TOKEN_HEADER, token);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode result = EpisodeJson.JSON.readTree(response.body());
            assertEquals(0, result.path("skipped").size());
            return result;
        }

        void stop(String name) throws Exception {
            assertEquals(200, DagRunEndpointTest.post(server, "/v1/dags/stop?name=" + name, "",
                    RetrievalServer.RUN_TOKEN_HEADER, token).statusCode());
        }

        @Override
        public void close() {
            server.close();
            feed.close();
            reader.close();
            producer.close();
        }
    }

    private static String source(Installation installation, String name, String series) {
        return """
                dag: %s
                version: 1
                source:
                  kind: kafscale-broker
                  topics: [%s]
                  series: [%s]
                nodes:
                  - { id: decode, op: decode.avro-soe }
                """.formatted(name, installation.layout.episodes(), series);
    }

    private static void advance(VectorProcessor processor, BooleanSupplier complete) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        do {
            processor.step();
            if (complete.getAsBoolean()) {
                return;
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("streaming acceptance did not reach its expected result: " + processor.states());
    }

    @Test
    void httpDeploymentFollowsNewInputAndStopsItsVector(@TempDir Path state) throws Exception {
        try (Installation installation = new Installation()) {
            String name = "stream-vector";
            String topic = installation.layout.smartVectors("state");
            String spec = source(installation, name, SPEED.getSeriesId() + ", " + POWER.getSeriesId()) + """
                      - { id: vector, op: align.vector, in: [decode], params: { every: PT1S, key: turbine, agg: MEAN, emit: complete } }
                    sinks:
                      - { from: vector, topic: %s, schema: opentsx.vector.v1 }
                    """.formatted(topic);
            assertEquals(topic, installation.deploy(spec).path("sinks").get(0).path("topic").asText());
            installation.append(SPEED, 0, 0, 5, 5);
            installation.append(POWER, 1, 0, 10, 10);
            List<JsonNode> rows = new ArrayList<>();
            try (VectorProcessor processor = installation.processor(state)) {
                LogTail output = new LogTail(installation.reader, topic, TIMEOUT);
                advance(processor, () -> {
                    output.poll(record -> {
                        try {
                            rows.add(EpisodeJson.JSON.readTree(record.value()));
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
                    return rows.size() >= 2;
                });
                assertEquals(2, rows.size());
                assertEquals("[10.0,5.0]", rows.get(0).path("values").toString());
                assertEquals("opentsx.vector.v1", rows.get(0).path("schema").asText());
                assertEquals(name, rows.get(0).path("provenance").path("dag").asText());
                assertEquals(64, rows.get(0).path("provenance").path("specHash").asText().length());
                installation.append(SPEED, 0, 2, 6, 6);
                installation.append(POWER, 1, 2, 20, 20);
                advance(processor, () -> {
                    output.poll(record -> {
                        try {
                            rows.add(EpisodeJson.JSON.readTree(record.value()));
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
                    return rows.size() >= 4;
                });
                assertEquals(4, rows.size());
                assertEquals("[20.0,6.0]", rows.get(3).path("values").toString());
                advance(processor, () -> installation.feed.vectorTopics().contains(topic)
                        && installation.feed.dags().path("dags").get(0).path("sinks").size() == 1);
                JsonNode inventory = EpisodeJson.JSON.readTree(DagRunEndpointTest.get(installation.server, "/v1/inventory").body());
                boolean derived = false;
                for (JsonNode series : inventory.path("series")) {
                    derived |= series.path("labels").path("smartTopic").toString().contains(topic);
                }
                assertTrue(derived, inventory.toString());
                JsonNode listed = EpisodeJson.JSON.readTree(DagRunEndpointTest.get(installation.server, "/v1/dags").body());
                assertEquals("deployed", listed.path("dags").get(0).path("desired").asText());
                assertEquals("running", listed.path("dags").get(0).path("sinks").get(0).path("state").asText());
                installation.stop(name);
                advance(processor, () -> processor.states().isEmpty());
                installation.append(SPEED, 0, 4, 7, 7);
                installation.append(POWER, 1, 4, 30, 30);
                processor.step();
                assertEquals(0, output.poll(record -> rows.add(EpisodeJson.JSON.nullNode())));
                assertEquals(4, rows.size());
                assertEquals("stopped", installation.feed.dags().path("dags").get(0).path("desired").asText());
            }
        }
    }

    @Test
    void refusesPartialDeploymentBeforeWritingControlRecords() throws Exception {
        try (Installation installation = new Installation()) {
            String topic = installation.layout.smartVectors("state");
            String spec = source(installation, "invalid-stream", SPEED.getSeriesId()) + """
                      - { id: vector, op: align.vector, in: [decode], params: { every: PT1S, key: turbine, agg: MEAN } }
                    sinks:
                      - { from: vector, topic: %s, schema: opentsx.vector.v1 }
                      - { from: decode, topic: unsupported }
                    """.formatted(topic);
            var response = DagRunEndpointTest.post(installation.server, "/v1/dags/deploy", spec,
                    RetrievalServer.RUN_TOKEN_HEADER, installation.token);
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("unsupported sink"), response.body());
            LogTail control = new LogTail(installation.reader, installation.layout.dags(), TIMEOUT);
            assertEquals(0, control.poll(record -> { throw new AssertionError("invalid deployment wrote a control record"); }));
            assertEquals(0, installation.feed.dags().path("dags").size());
        }
    }

    @Test
    void cleaningResumesAnOpenWindowFromItsCheckpoint(@TempDir Path state) throws Exception {
        try (Installation installation = new Installation()) {
            String topic = installation.layout.clean();
            String spec = source(installation, "stream-clean", SPEED.getSeriesId()) + """
                      - { id: convert, op: convert.unit, in: [decode], params: { factor: 3.6, offset: 0, unit: km/h } }
                      - { id: sample, op: resample, in: [convert], params: { every: PT2S, agg: MEAN } }
                    sinks:
                      - { from: sample, topic: %s, schema: opentsx.episode.v2, signal: { system: lab, id: wind, grade: clean } }
                    """.formatted(topic);
            installation.deploy(spec);
            installation.append(SPEED, 0, 0, 1, 3, 5);
            List<Episode> rows = new ArrayList<>();
            LogTail output = new LogTail(installation.reader, topic, TIMEOUT);
            try (VectorProcessor processor = installation.processor(state)) {
                advance(processor, () -> {
                    output.poll(record -> rows.add(AVRO.decode(record.value(), Episode.class)));
                    return !rows.isEmpty();
                });
                assertEquals(1, rows.size());
                assertArrayEquals(new double[]{7.2}, Episodes.values(rows.get(0)), 1e-12);
            }
            try (var files = Files.list(state)) {
                assertTrue(files.anyMatch(file -> file.toString().endsWith(".json")));
            }
            installation.append(SPEED, 0, 3, 7, 9, 11);
            try (VectorProcessor restarted = installation.processor(state)) {
                advance(restarted, () -> {
                    output.poll(record -> rows.add(AVRO.decode(record.value(), Episode.class)));
                    return rows.size() >= 2;
                });
                assertEquals(2, rows.size());
                assertArrayEquals(new double[]{21.6, 36.0}, Episodes.values(rows.get(1)), 1e-12);
                assertEquals("km/h", rows.get(1).getSeries().getUnit());
                assertEquals("clean", rows.get(1).getSeries().getTags().get("grade"));
                assertFalse(rows.get(1).getLabels().isEmpty());
                assertEquals(0, output.poll(record -> rows.add(AVRO.decode(record.value(), Episode.class))));
            }
        }
    }
}
