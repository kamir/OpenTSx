package org.opentsx.kafka;

import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicLayoutTest {

    private final TopicLayout layout = new TopicLayout("wind");

    @Test
    void anInterruptedProvisioningThrowsAndKeepsTheInterrupt() {
        // MockAdminClient completes its own futures at once, and a completed future does not look at the interrupt
        try (MockAdminClient admin = new MockAdminClient() {
            @Override
            public synchronized CreateTopicsResult createTopics(Collection<NewTopic> topics, CreateTopicsOptions options) {
                Map<String, KafkaFuture<CreateTopicsResult.TopicMetadataAndConfig>> unanswered = new HashMap<>();
                topics.forEach(t -> unanswered.put(t.name(), new KafkaFutureImpl<>()));
                return new CreateTopicsResult(unanswered) {
                };
            }
        }) {
            List<Executable> calls = List.of(() -> layout.provision(admin, 6, (short) 1),
                    () -> layout.provisionDagTopics(admin, (short) 1),
                    () -> layout.provisionTopic(admin, layout.clean(), (short) 1));
            for (Executable call : calls) {
                Thread.currentThread().interrupt();
                IllegalStateException e = assertThrows(IllegalStateException.class, call);
                assertTrue(Thread.interrupted(), "the interrupt is kept for the caller: " + e.getMessage());
                assertInstanceOf(InterruptedException.class, e.getCause());
            }
        }
    }
}
