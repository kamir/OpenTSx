package org.opentsx.wayang.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Each chain runs on Track A and on Track B over the same 70 seconds of feed on KafScale. Both tracks report the start,
 * the first fetch and the first output in that order, and write the same number of records. Track B needs the
 * {@code opentsx.rust.b3.runner} executable that the KafScale conformance job builds.
 */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class BoardWorkloadIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int SERIES = 20;
    static final long BLOCKS = 7;

    @ParameterizedTest
    @ValueSource(strings = {"passthrough", "clean", "stats"})
    void bothTracksRunEveryChainAndReportTheirTimings(String chain, @TempDir Path dir) throws Exception {
        String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        String runner = System.getProperty("opentsx.rust.b3.runner", "");
        assumeTrue(!runner.isBlank() && Files.isExecutable(Path.of(runner)), "Track B needs opentsx.rust.b3.runner");

        TopicLayout a = feed(bootstrap);
        ByteArrayOutputStream aLines = new ByteArrayOutputStream();
        BoardTrackA.run(bootstrap, a, chain, SERIES, 7, Path.of("..", "systems", "processors"), dir.resolve("a.json"),
                Duration.ofSeconds(2), Duration.ofSeconds(6), events(aLines));

        TopicLayout b = feed(bootstrap);
        ByteArrayOutputStream bLines = new ByteArrayOutputStream();
        Files.createDirectories(dir.resolve("state"));
        BoardTrackB.run(Path.of(runner), "127.0.0.1:" + freePort(), dir.resolve("state"), "measurement", bootstrap, b,
                chain, SERIES, 7, Duration.ofSeconds(2), Duration.ofSeconds(6), dir.resolve("runner.log"), events(bLines));

        List<ConsumerRecord<byte[], byte[]>> outA = readAll(bootstrap, BoardChains.output(chain, a));
        List<ConsumerRecord<byte[], byte[]>> outB = readAll(bootstrap, BoardChains.output(chain, b));
        assertFalse(outA.isEmpty(), chain + " on Track A wrote nothing");
        assertEquals(outA.size(), outB.size(), chain + ": Track B wrote " + outB.size() + " records, Track A " + outA.size());
        for (ByteArrayOutputStream lines : List.of(aLines, bLines)) {
            List<JsonNode> events = new ArrayList<>();
            for (String line : lines.toString(StandardCharsets.UTF_8).lines().toList()) events.add(JSON.readTree(line));
            double fetch = time(events, "firstFetch");
            double output = time(events, "firstOutput");
            assertTrue(fetch <= output, chain + ": first fetch after first output");
            assertTrue(events.stream().anyMatch(e -> e.path("event").asText().equals("stopped")), chain + " did not stop");
        }
        assertTrue(bLines.toString(StandardCharsets.UTF_8).contains("\"pid\""), "Track B reports the runner pid");
    }

    static BoardEvents events(ByteArrayOutputStream lines) {
        return new BoardEvents(new PrintStream(lines, true, StandardCharsets.UTF_8), System.nanoTime());
    }

    /** A new domain with the whole feed written at once; pacing does not change the records. */
    static TopicLayout feed(String bootstrap) throws Exception {
        TopicLayout layout = new TopicLayout("board" + UUID.randomUUID().toString().substring(0, 8));
        BoardTrackA.provision(bootstrap, layout, 1);
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            List<Future<?>> sent = new ArrayList<>();
            for (ProducerRecord<String, byte[]> r : new BoardFeed(SERIES, 7).records(layout.episodes(), BLOCKS)) sent.add(producer.send(r));
            producer.flush();
            for (Future<?> f : sent) f.get();
        }
        return layout;
    }

    static double time(List<JsonNode> events, String name) {
        return events.stream().filter(e -> e.path("event").asText().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " event")).path("seconds").asDouble();
    }

    static List<ConsumerRecord<byte[], byte[]>> readAll(String bootstrap, String topic) {
        List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                    .map(i -> new TopicPartition(topic, i.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long end = consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (out.size() < end && System.nanoTime() < deadline) consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        return out;
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
