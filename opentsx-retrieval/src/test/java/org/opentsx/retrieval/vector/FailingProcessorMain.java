package org.opentsx.retrieval.vector;

import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.List;
import java.util.Map;

/** Runs {@code vectors} with a consumer factory that fails after the processor started, and exits as main does. */
public final class FailingProcessorMain {

    private FailingProcessorMain() {
    }

    public static void main(String[] args) {
        Node broker = new Node(0, "127.0.0.1", 1);
        int code = VectorProcessorApp.run(List.of("--kafscale", "ks:9092", "--state", args[0]), Map.of(), System.out,
                System.err, b -> new MockAdminClient(List.of(broker), broker),
                b -> new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()), b -> {
                    throw new RuntimeException("consumer factory failed");
                });
        if (code != 0) {
            System.exit(code);
        }
    }
}
