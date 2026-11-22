package org.opentsx.kafka;

import org.apache.kafka.common.internals.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicLayoutTwinEventsTest {

    private static final String RUN = "h2-chain-6734f91571b28b83";

    @Test void eventTopicOfATwinRunIsNamedInTheDomain() {
        var layout = new TopicLayout("demo");
        String topic = layout.twinEvents("h2-chain", RUN);
        assertEquals("tsx.demo.twin.h2-chain.h2-chain-6734f91571b28b83.events.v1", topic);
        assertDoesNotThrow(() -> Topic.validate(topic));
        assertTrue(layout.isTwinEvents(topic));
        var other = new TopicLayout("other");
        assertNotEquals(topic, other.twinEvents("h2-chain", RUN), "two domains on one broker write two topics");
        assertFalse(other.isTwinEvents(topic));
    }

    @Test void onlyEventTopicsOfTwinRunsOfTheDomainAreRecognised() {
        var layout = new TopicLayout("demo");
        for (String topic : List.of("twin.h2-chain." + RUN + ".events", "tsx.demo.episodes.v2", "tsx.demo.signals.v1",
                "tsx.demo.smart.h2-chain.vectors.v1", "tsx.demo.twin.h2-chain.events.v1", "tsx.demo.twin.events.v1",
                "tsx.demo.twin.a.b.c.events.v1", "tsx.demo.twin..run.events.v1", "tsx.demo.twin.h2-chain.Run.events.v1",
                "tsx.demo.twin.h2-chain.run.msgs.v1", "tsx.demo.twin.h2-chain.run.events.v2", "")) {
            assertFalse(layout.isTwinEvents(topic), topic);
        }
        assertFalse(layout.isTwinEvents(null));
    }

    @Test void twinAndRunIdsUseTheLowerCaseNameGrammar() {
        var layout = new TopicLayout("demo");
        for (String invalid : List.of("", "Upper", "under_score", "a.b", "a/b", "a b", "-a", "é")) {
            assertThrows(IllegalArgumentException.class, () -> layout.twinEvents(invalid, RUN), invalid);
            assertThrows(IllegalArgumentException.class, () -> layout.twinEvents("h2-chain", invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> layout.twinEvents(null, RUN));
        assertThrows(IllegalArgumentException.class, () -> layout.twinEvents("h2-chain", null));
    }

    @Test void eventTopicsStayWithinTheKafka249ByteLimit() {
        var layout = new TopicLayout("demo");
        int fixed = layout.twinEvents("t", "r").length() - 2;
        String twin = "t".repeat(100), run = "r".repeat(249 - fixed - 100);
        String longest = layout.twinEvents(twin, run);
        assertEquals(249, longest.length());
        assertDoesNotThrow(() -> Topic.validate(longest));
        assertTrue(layout.isTwinEvents(longest));
        assertThrows(IllegalArgumentException.class, () -> layout.twinEvents(twin, run + "r"));
        assertFalse(layout.isTwinEvents(longest.replace(".events.v1", "r.events.v1")));
    }
}
