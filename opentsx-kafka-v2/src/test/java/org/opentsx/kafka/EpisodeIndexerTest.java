package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EpisodeIndexerTest {

    private final TopicLayout layout = new TopicLayout("unit");
    private final OpenTsxAvro avro = new OpenTsxAvro();

    /** One partition holding the given record values at offsets 0, 1, ... */
    private MockConsumer<byte[], byte[]> log(List<byte[]> values) {
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition(layout.episodes(), 0);
        Node node = new Node(0, "localhost", 9092);
        consumer.updatePartitions(layout.episodes(),
                List.of(new PartitionInfo(tp.topic(), 0, node, new Node[]{node}, new Node[]{node})));
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        consumer.updateEndOffsets(Map.of(tp, (long) values.size()));
        consumer.schedulePollTask(() -> {
            for (int i = 0; i < values.size(); i++) {
                consumer.addRecord(new ConsumerRecord<>(tp.topic(), 0, i, new byte[0], values.get(i)));
            }
        });
        return consumer;
    }

    @Test
    void aRecordThatIsNoEpisodeIsSkippedAndCounted() {
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 2, 30, 11);
        List<byte[]> values = new ArrayList<>();
        values.add(avro.encode(written.get(0)));
        values.add("not avro".getBytes(StandardCharsets.UTF_8));
        values.add(avro.encode(written.get(1)));
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        EpisodeIndexer.Result r = EpisodeIndexer.rebuild(log(values), layout, index, Duration.ofSeconds(5));
        assertEquals(new EpisodeIndexer.Result(2, 1, 0), r);
        assertEquals(2, index.size());
    }

    @Test
    void onlyTheFirstUndecodableRecordOfAPartitionIsReported() {
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 1, 30, 15);
        List<byte[]> values = List.of(avro.encode(written.get(0)), "not avro".getBytes(StandardCharsets.UTF_8),
                "also not avro".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        EpisodeIndexer.Result r;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            r = EpisodeIndexer.rebuild(log(values), layout, new InMemoryEpisodeIndex(), Duration.ofSeconds(5));
        } finally {
            System.setErr(stderr);
        }
        assertEquals(new EpisodeIndexer.Result(1, 2, 0), r);
        assertEquals(layout.episodes() + "-0 offset 1: undecodable record (unrecognised payload framing); skipped and counted"
                + " in skipped, later undecodable records of this partition are only counted" + System.lineSeparator(),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anInconsistentEpisodeIsCountedAsInvalidReportedOncePerPartitionAndSkipped() {
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 2, 30, 13);
        List<byte[]> values = List.of(avro.encode(written.get(0)), avro.encode(countMismatch(written.get(0), "bad-1")),
                avro.encode(written.get(1)), avro.encode(countMismatch(written.get(1), "bad-2")));
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        EpisodeIndexer.Result r;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            r = EpisodeIndexer.rebuild(log(values), layout, index, Duration.ofSeconds(5));
        } finally {
            System.setErr(stderr);
        }
        assertEquals(new EpisodeIndexer.Result(2, 0, 2), r);
        assertEquals(List.of(written.get(0).getEpisodeId(), written.get(1).getEpisodeId()),
                index.find(null, Long.MIN_VALUE, Long.MAX_VALUE).stream().map(IndexEntry::episodeId).toList());
        assertEquals(layout.episodes() + "-0 offset 1: invalid episode (count: \"3\" differs from the length 2 of values;"
                + " expected the number of values); skipped and counted in invalid, later invalid episodes of this partition"
                + " are only counted" + System.lineSeparator(),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aRegularEpisodeWithoutAnIntervalIsCountedAsInvalid() {
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 2, 30, 14);
        List<byte[]> values = List.of(avro.encode(written.get(0)),
                avro.encode(Episode.newBuilder(written.get(1)).setIntervalMicros(null).build()));
        InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        EpisodeIndexer.Result r;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            r = EpisodeIndexer.rebuild(log(values), layout, index, Duration.ofSeconds(5));
        } finally {
            System.setErr(stderr);
        }
        assertEquals(new EpisodeIndexer.Result(1, 0, 1), r);
        assertEquals(layout.episodes() + "-0 offset 1: invalid episode (intervalMicros: missing in a REGULAR episode; expected"
                + " a positive number of microseconds); skipped and counted in invalid, later invalid episodes of this"
                + " partition are only counted" + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
    }

    private static Episode countMismatch(Episode e, String episodeId) {
        Episode two = Episodes.regular(e.getSeries(), Micros.of(e.getTStart()), 1_000_000L, new double[]{1, 2})
                .episodeId(episodeId).source("test").build();
        return Episode.newBuilder(two).setCount(3).build();
    }

    @Test
    void anIndexFailureStopsTheRebuild() {
        List<Episode> written = WindData.episodes(1, List.of("wind_speed"), 2, 30, 12);
        UncheckedIOException full = new UncheckedIOException(new IOException("No space left on device"));
        EpisodeIndex failing = new InMemoryEpisodeIndex() {
            @Override
            public synchronized void add(IndexEntry entry) {
                throw full;
            }
        };
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> EpisodeIndexer.rebuild(
                log(written.stream().map(avro::encode).toList()), layout, failing, Duration.ofSeconds(5)));
        assertSame(full, e);
    }
}
