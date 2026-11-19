package org.opentsx.retrieval.vector;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opentsx.conf.OpenTsxConf;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.opentsx.retrieval.vector.VectorProcessorTest.LAYOUT;

/**
 * Runs one sink that fails the same way three times, a minute apart on the clock of the processor. The level comes
 * from OPENTSX_LOG_LEVEL through {@link OpenTsxConf#load}, as for the command vectors.
 */
public final class FailingSinkMain {

    private FailingSinkMain() {
    }

    public static void main(String[] args) {
        OpenTsxConf.load(List.of("vectors"), System.getenv(), null);
        VectorProcessorTest.FakeBroker broker = new VectorProcessorTest.FakeBroker();
        broker.append(LAYOUT.dags(), 0, VectorProcessor.deployRecord("wind-exp2-smart", VectorProcessorTest.goodDag(), "test"));
        long[] now = {0};
        VectorProcessor.Timing timing = new VectorProcessor.Timing(Duration.ZERO, Duration.ofHours(1), Duration.ZERO,
                Duration.ofSeconds(60), Duration.ofMillis(1), Duration.ofSeconds(30));
        try (VectorProcessor p = new VectorProcessor(broker::consumer,
                () -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), LAYOUT, Path.of(args[0]),
                timing, () -> now[0])) {
            p.sinkProvisioner(topic -> {
                throw new NullPointerException(VectorProcessorTest.NPE);
            });
            for (int i = 0; i < 3; i++) {
                p.step();
                now[0] += Duration.ofSeconds(60).toNanos();
            }
        }
    }
}
