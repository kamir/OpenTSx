package org.opentsx.kafka;

import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicLayoutTest {

    private final TopicLayout layout = new TopicLayout("wind");

    @Test
    void aDomainOutsideTheTopicGrammarIsRefusedWithTheAllowedForm() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new TopicLayout("Wind"));
        assertEquals("domain: \"Wind\" is not allowed; expected lowercase letters, digits, _ and -, starting with a letter "
                + "or digit, such as wind", e.getMessage());
    }

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

    @Test
    void theEventTopicOfATwinRunIsNamedAndCreatedHere() throws Exception {
        String topic = TopicLayout.twinEvents("h2-chain", "h2-chain-32d17d33b237bf6b");
        assertEquals("twin.h2-chain.h2-chain-32d17d33b237bf6b.events", topic);
        assertTrue(TopicLayout.isTwinEvents(topic));
        assertFalse(TopicLayout.isTwinEvents(layout.episodes()));
        assertFalse(TopicLayout.isTwinEvents("twin.h2-chain.run.msgs"));
        assertFalse(TopicLayout.isTwinEvents("twin.h2-chain.../x.events"));
        assertFalse(TopicLayout.isTwinEvents("twin.demo." + "r".repeat(240) + ".events"));
        assertFalse(TopicLayout.isTwinEvents(null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TopicLayout.twinEvents("H2", "run-1"));
        assertEquals("twin event topic: \"twin.H2.run-1.events\" is not allowed; expected twin.<id>.<run>.events with an id "
                + "of lowercase letters, digits, _ and - and a run of letters, digits, _ and -, each starting with a letter or "
                + "digit, at most 249 characters, such as twin.h2-chain.run-1.events", e.getMessage());

        Node broker = new Node(0, "localhost", 9092);
        try (MockAdminClient admin = new MockAdminClient(List.of(broker), broker)) {
            TopicLayout.provisionTwinEvents(admin, topic, (short) 1);
            TopicLayout.provisionTwinEvents(admin, topic, (short) 1);
            assertEquals(1, admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions().size());
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
            assertEquals("delete", config.get("cleanup.policy").value());
            assertEquals("zstd", config.get("compression.type").value());
            assertThrows(IllegalArgumentException.class,
                    () -> TopicLayout.provisionTwinEvents(admin, layout.episodes(), (short) 1));
            assertFalse(admin.listTopics().names().get().contains(layout.episodes()));
        }
    }
}
