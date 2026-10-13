package org.opentsx.twin.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The log operations the event topic needs, against a real broker (KafScale in CI, Apache Kafka works too).
 * Run with {@code KAFSCALE_BOOTSTRAP=host:port mvn -pl opentsx-twin-live test}.
 */
@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class KafkaTopicsBrokerTest {

    private final String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");

    @Test
    void appendsAndReadsFromStartFromOffsetAndFromTheEnd() throws Exception {
        String topic = EventTopic.name("brokertest", "run-" + System.currentTimeMillis());
        try (KafkaTopics topics = new KafkaTopics(bootstrap)) {
            assertThrows(Topics.NoSuchTopicException.class, () -> topics.open(topic, Topics.EARLIEST));
            topics.create(topic);
            try (Topics.Cursor empty = topics.open(topic, Topics.EARLIEST)) {
                assertEquals(0, empty.poll(Duration.ofMillis(800)).size(), "the topic exists and is empty");
            }
            for (int i = 0; i < 3; i++) {
                topics.append(topic, "atom-" + i, ("{\"n\":" + i + "}").getBytes(StandardCharsets.UTF_8), EventTopic.SCHEMA);
            }
            try (Topics.Cursor all = topics.open(topic, Topics.EARLIEST)) {
                List<Topics.Rec> recs = read(all, 3);
                assertEquals(List.of(0L, 1L, 2L), recs.stream().map(Topics.Rec::offset).toList());
                assertEquals("atom-1", recs.get(1).key());
                assertEquals("{\"n\":2}", new String(recs.get(2).value(), StandardCharsets.UTF_8));
            }
            try (Topics.Cursor from2 = topics.open(topic, 2)) {
                assertEquals(List.of(2L), read(from2, 1).stream().map(Topics.Rec::offset).toList());
            }
            try (Topics.Cursor live = topics.open(topic, Topics.LATEST)) {
                assertEquals(0, live.poll(Duration.ofMillis(800)).size(), "nothing from the past");
                topics.append(topic, "atom-3", "{\"n\":3}".getBytes(StandardCharsets.UTF_8), EventTopic.SCHEMA);
                assertEquals(List.of(3L), read(live, 1).stream().map(Topics.Rec::offset).toList());
            }
        }
    }

    private static List<Topics.Rec> read(Topics.Cursor c, int n) {
        List<Topics.Rec> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (out.size() < n && System.currentTimeMillis() < deadline) {
            out.addAll(c.poll(Duration.ofMillis(500)));
        }
        return out;
    }
}
