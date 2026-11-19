package org.opentsx.retrieval.server;

import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.retrieval.vector.VectorProcessor;
import org.opentsx.retrieval.vector.VectorProcessorTest;
import org.opentsx.retrieval.vector.VectorProcessorTest.FakeBroker;
import org.opentsx.store.InMemorySeriesStore;

import java.nio.file.Path;
import java.util.List;

import static org.opentsx.retrieval.vector.VectorProcessorTest.LAYOUT;
import static org.opentsx.retrieval.vector.VectorProcessorTest.POWER;
import static org.opentsx.retrieval.vector.VectorProcessorTest.SPEED;

/**
 * Runs the KafScale feed and the vector processor over a broker in memory through the lines they log: a load, a
 * refresh, topics that cannot be read, a deployment, a refusal and a stop. The level comes from OPENTSX_LOG_LEVEL
 * through {@link OpenTsxConf#load}, as for the commands. Prints nothing itself.
 */
public final class FeedLogMain {

    private FeedLogMain() {
    }

    public static void main(String[] args) {
        OpenTsxConf.load(List.of("retrieval-server"), System.getenv(), null);
        FakeBroker broker = KafScaleFeedTest.broker();
        broker.append(LAYOUT.episodes(), 0, KafScaleFeedTest.episode(0));
        broker.unreadable.put(KafScaleFeedTest.VECTORS, new org.apache.kafka.common.errors.TimeoutException(
                "Timeout expired while fetching topic metadata"));
        broker.unreadable.put(LAYOUT.dags(), new IllegalStateException("timed out reading [tsx.wind.dags.v1-0]"));
        try (KafScaleFeed feed = new KafScaleFeed("ks:9092", "wind", broker.consumer(),
                b -> new MockAdminClient(List.of(KafScaleFeedTest.NODE), KafScaleFeedTest.NODE),
                b -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), new InMemorySeriesStore(), 100,
                () -> { })) {
            feed.load();
            broker.append(LAYOUT.episodes(), 0, KafScaleFeedTest.episode(1));
            feed.refresh();
        }

        try (VectorProcessor p = new VectorProcessor(broker::consumer,
                () -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), LAYOUT, Path.of(args[0]),
                VectorProcessorTest.FAST)) {
            p.step();
            broker.unreadable.remove(LAYOUT.dags());
            String good = VectorProcessorTest.goodDag();
            String refused = VectorProcessorTest.dag("\"" + SPEED.getSeriesId() + "\", \"" + POWER.getSeriesId() + "\"",
                    "  - id: n0\n    op: norm.zscore\n    in: [decode]\n", "n0").replace("dag: wind-exp2-smart", "dag: wind-bad");
            broker.append(LAYOUT.dags(), 0, VectorProcessor.deployRecord("wind-exp2-smart", good, "test"));
            broker.append(LAYOUT.dags(), 0, VectorProcessor.deployRecord("wind-bad", refused, "test"));
            p.step();
            p.step();
            broker.append(LAYOUT.dags(), 0, VectorProcessor.stopRecord("wind-exp2-smart", "test"));
            p.step();
        }
    }
}
