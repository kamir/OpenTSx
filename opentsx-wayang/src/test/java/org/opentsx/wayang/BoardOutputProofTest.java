package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.wayang.board.BoardChains;
import org.opentsx.wayang.board.BoardFeed;
import org.opentsx.wayang.board.BoardOutputs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The output proof of one B4.2 condition: the run's output topic holds, for every series of the inventory, exactly the
 * first records of the reference output of the same feed ({@link BoardReference}). Both tracks are held to the same
 * reference, so passing conditions of both tracks produced identical output records. Only after every comparison passed,
 * the test writes the JUnit XML named by {@code opentsx.board.proof}, with the binding properties
 * {@code opentsx.sourceCommit}, {@code opentsx.conditionId}, {@code opentsx.artifactSha256} and {@code opentsx.inputSha256}
 * and nothing else from the environment.
 */
@EnabledIfSystemProperty(named = "opentsx.board.proof", matches = ".+")
public class BoardOutputProofTest {

    static final List<String> BINDING = List.of("opentsx.sourceCommit", "opentsx.conditionId", "opentsx.artifactSha256",
            "opentsx.inputSha256");

    static String property(String name) {
        String value = System.getProperty(name, "");
        if (value.isBlank()) throw new IllegalArgumentException("missing system property " + name);
        return value;
    }

    @Test
    void conditionOutputEqualsTheReference() throws Exception {
        long started = System.nanoTime();
        Path proof = Path.of(property("opentsx.board.proof"));
        String chain = property("opentsx.board.chain");
        TopicLayout layout = new TopicLayout(property("opentsx.board.domain"));
        BoardFeed feed = new BoardFeed(Integer.parseInt(property("opentsx.board.series")), Long.parseLong(property("opentsx.board.seed")));
        long blocks = Long.parseLong(property("opentsx.board.blocks"));
        Map<String, String> binding = new LinkedHashMap<>();
        for (String name : BINDING) binding.put(name, property(name));
        assertEquals(feed.digest(layout.episodes(), blocks), binding.get("opentsx.inputSha256"),
                "inputSha256 is not the digest of this feed");

        List<SpecificRecord> run = read(property("opentsx.board.bootstrap"), BoardChains.output(chain, layout),
                chain.equals("stats") ? SeriesStats.class : Episode.class);
        List<SpecificRecord> reference = BoardReference.output(chain, layout, feed, blocks, Path.of("..", "systems", "processors"));
        BoardOutputs.Comparison comparison = BoardOutputs.compare(BoardOutputs.bySeries(reference), BoardOutputs.bySeries(run));

        Map<String, String> properties = new LinkedHashMap<>(binding);
        properties.put("opentsx.board.chain", chain);
        properties.put("opentsx.board.comparedSeries", Integer.toString(comparison.series()));
        properties.put("opentsx.board.comparedRecords", Long.toString(comparison.records()));
        Files.writeString(proof, BoardOutputs.proof(getClass().getName(), "conditionOutputEqualsTheReference", properties,
                (System.nanoTime() - started) / 1e9), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** Every record of the topic; the Kafka key must be the record's series id. */
    public static List<SpecificRecord> read(String bootstrap, String topic, Class<? extends SpecificRecord> type) {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<SpecificRecord> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                    .map(i -> new TopicPartition(topic, i.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long end = consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
            long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
            long seen = 0;
            while (seen < end) {
                if (System.nanoTime() > deadline) throw new AssertionError("read " + seen + " of " + end + " output records");
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    SpecificRecord record = avro.decode(r.value(), type);
                    String series = record instanceof Episode e ? e.getSeries().getSeriesId() : ((SeriesStats) record).getSeriesId();
                    assertEquals(series, new String(r.key(), StandardCharsets.UTF_8), "output key differs from the series id");
                    out.add(record);
                    seen++;
                }
            }
        }
        return out;
    }
}
