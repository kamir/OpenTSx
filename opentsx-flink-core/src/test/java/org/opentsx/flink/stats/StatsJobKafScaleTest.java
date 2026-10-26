package org.opentsx.flink.stats;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentsx.kafka.BucketWriter;
import org.opentsx.kafka.InMemoryEpisodeIndex;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.SeriesStatsLog;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Episodes written to KafScale come back as SeriesStats on the series-stats topic, as the processor writes them. */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class StatsJobKafScaleTest {

    @Test
    void boundedJobWritesTheStatisticsOfTheProcessor() throws Exception {
        String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        TopicLayout layout = new TopicLayout("flinkstats" + UUID.randomUUID().toString().substring(0, 8));
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            layout.provision(admin, 3, (short) 1);
        }
        List<Episode> episodes = StatsData.episodes("kafscale-flink-stats", Duration.ofMinutes(30), 2);
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap);
             BucketWriter w = BucketWriter.open(producer, layout, new InMemoryEpisodeIndex(), "flink-stats-it", Map.of())) {
            episodes.forEach(w::append);
        }

        EngineSpec spec = EngineSpec.defaults();
        StatsJob.execute(new StatsJob.Options(bootstrap, layout, spec, false, true, Duration.ZERO, Duration.ofSeconds(5)));

        List<SeriesStats> written;
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            written = SeriesStatsLog.readLatest(consumer, layout, Duration.ofMinutes(1));
        }
        assertEquals(StatsData.direct(episodes, spec), StatsData.sorted(written));
    }
}
