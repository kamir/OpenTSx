package org.opentsx.wayang;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.LogReader;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

/** Data W of TASK-020 on the broker named by KAFSCALE_BOOTSTRAP (data K). */
final class KafScaleFixture {

    static final Path SCENARIO = Path.of("..", "opentsx-windpark", "scenarios", "ostsee-demo.yaml");
    static final Set<String> EPISODE_ONLY = Set.of(XxHash64.hex(OpenTsxAvro.fingerprint(Episode.getClassSchema())));

    final String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
    final TopicLayout layout = new TopicLayout("wayang" + UUID.randomUUID().toString().substring(0, 8));

    KafScaleFixture() throws Exception {
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            layout.provision(admin, 3, (short) 1);
            layout.provisionTopic(admin, layout.clean(), (short) 1);
        }
    }

    static List<Episode> scenarioW() {
        List<Episode> out = new ArrayList<>();
        new WindparkSimulator(Scenario.load(SCENARIO), ChannelCatalog.standard()).run(out::add);
        return out;
    }

    /** Writes the records with the sink's own encoding and key rule; returns the payloads by episode id. */
    Map<String, byte[]> write(String topic, List<? extends SpecificRecord> records) throws Exception {
        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, byte[]> payloads = new HashMap<>();
        List<Future<?>> sent = new ArrayList<>();
        try (KafkaProducer<String, byte[]> p = KafScaleClients.producer(bootstrap)) {
            for (SpecificRecord r : records) {
                var rec = JavaTsxTopicSink.toRecord(avro, topic, r);
                if (r instanceof Episode e) {
                    payloads.put(e.getEpisodeId(), rec.value());
                }
                sent.add(p.send(rec));
            }
            p.flush();
            for (Future<?> f : sent) {
                f.get();
            }
        }
        return payloads;
    }

    /** Everything in the topic now. */
    List<ConsumerRecord<byte[], byte[]>> readAll(String topic) {
        List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> c = KafScaleClients.consumer(bootstrap)) {
            List<TopicPartition> tps = c.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                    .map(i -> new TopicPartition(topic, i.partition())).toList();
            Map<TopicPartition, Long> begin = c.beginningOffsets(tps);
            Map<TopicPartition, Long> end = c.endOffsets(tps);
            Map<TopicPartition, long[]> ranges = new HashMap<>();
            tps.forEach(tp -> ranges.put(tp, new long[]{begin.get(tp), end.get(tp)}));
            LogReader.read(c, ranges, out::add, Duration.ofMinutes(5));
        }
        return out;
    }

    TsxIncrement.Config config(Path checkpoint, Set<String> fingerprints) {
        return new TsxIncrement.Config(bootstrap, "it", 1, "a".repeat(64), layout.episodes(), fingerprints, "earliest",
                checkpoint);
    }
}
