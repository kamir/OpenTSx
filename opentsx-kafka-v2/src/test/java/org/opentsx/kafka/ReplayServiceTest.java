package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayServiceTest {

    private final TopicLayout layout = new TopicLayout("unit");
    private final List<Episode> written = WindData.episodes(1, List.of("rpm"), 4, 10, 24); // tStart 0 s, 10 s, 20 s, 30 s
    private final InMemoryEpisodeIndex index = new InMemoryEpisodeIndex();
    private final MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    private long now;
    private final List<Long> sleeps = new ArrayList<>();

    /** The four episodes at offsets 0 to 3 of partition 0, indexed; a replay of the series reads them. */
    private ReplayService replayService() {
        TopicPartition tp = new TopicPartition(layout.episodes(), 0);
        consumer.updateBeginningOffsets(Map.of(tp, 0L));
        OpenTsxAvro avro = new OpenTsxAvro();
        for (int i = 0; i < written.size(); i++) {
            Episode e = written.get(i);
            index.add(new IndexEntry(e.getEpisodeId(), e.getSeries().getSeriesId(), Micros.of(e.getTStart()),
                    Micros.of(e.getTEnd()), "paced", tp.topic(), tp.partition(), i));
        }
        consumer.schedulePollTask(() -> {
            for (int i = 0; i < written.size(); i++) {
                consumer.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), i, new byte[0], avro.encode(written.get(i))));
            }
        });
        return new ReplayService(consumer, layout, index, Duration.ofSeconds(5), () -> now, nanos -> {
            sleeps.add(nanos);
            now += nanos;
        });
    }

    private ReplayRequest all() {
        return ReplayRequest.range(Set.of(written.get(0).getSeries().getSeriesId()), WindData.T0, WindData.T0 + 40_000_000L);
    }

    @Test
    void pacedReplaySleepsTheEventTimeDividedByTheSpeed() {
        List<Long> deliveredAt = new ArrayList<>();
        ReplayResult r = replayService().replay(all().withSpeed(60.0), e -> deliveredAt.add(now));

        assertEquals(4, r.delivered());
        assertEquals(List.of(166_666_666L, 166_666_667L, 166_666_667L), sleeps, "10 s of event time at 60x, each");
        assertEquals(List.of(0L, 166_666_666L, 333_333_333L, 500_000_000L), deliveredAt, "30 s of event time in 0.5 s");
    }

    @Test
    void anEpisodeThatIsAlreadyDueIsDeliveredWithoutSleeping() {
        ReplayResult r = replayService().replay(all().withSpeed(60.0), e -> now += Duration.ofSeconds(1).toNanos());

        assertEquals(4, r.delivered());
        assertEquals(List.of(), sleeps, "the sink takes 1 s per episode, longer than 10 s of event time at 60x");
    }

    @Test
    void speedZeroReplaysWithoutSleeping() {
        ReplayResult r = replayService().replay(all(), e -> { });

        assertEquals(4, r.delivered());
        assertEquals(List.of(), sleeps);
    }
}
