package org.opentsx.kafka;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicLayoutFederationTest {

    @Test void federationNamesUseTheirSpecifiedWireVersions() {
        var layout = new TopicLayout("wind_park");
        assertEquals("tsx.wind_park.aggregates.v2", layout.aggregates());
        assertEquals("tsx.wind_park.signals.v1", layout.signals());
        assertEquals("tsx.wind_park.repartition.wind-flow.align-1.v2", layout.repartition("wind-flow", "align-1"));
    }

    @Test void standardProvisioningRetainsItsFiveExistingTopics() {
        var layout = new TopicLayout("wind");
        assertEquals(List.of("tsx.wind.episodes.v2", "tsx.wind.buckets.v2", "tsx.wind.series.v2",
                "tsx.wind.observations.v2", "tsx.wind.series-stats.v2"), layout.all());
        assertFalse(layout.all().contains(layout.aggregates()));
        assertFalse(layout.all().contains(layout.signals()));
        assertFalse(layout.all().contains(layout.repartition("dag", "node")));
    }

    @Test void repartitionIdentifiersUseTheExistingLowerCaseNameGrammar() {
        var layout = new TopicLayout("wind");
        for (String invalid : List.of("", "Upper", "under_score", "a.b", "a/b", "a b", "-a", "\u00e9")) {
            assertThrows(IllegalArgumentException.class, () -> layout.repartition(invalid, "node"), invalid);
            assertThrows(IllegalArgumentException.class, () -> layout.repartition("dag", invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> layout.repartition(null, "node"));
        assertThrows(IllegalArgumentException.class, () -> layout.repartition("dag", null));
    }

    @Test void everyNewTopicKindEnforcesTheKafka249ByteLimit() {
        int aggregateFixed = new TopicLayout("a").aggregates().length() - 1;
        int signalFixed = new TopicLayout("a").signals().length() - 1;
        int repartitionFixed = new TopicLayout("a").repartition("dag", "node").length() - 1;
        assertEquals(249, new TopicLayout("a".repeat(249 - aggregateFixed)).aggregates().length());
        assertEquals(249, new TopicLayout("a".repeat(249 - signalFixed)).signals().length());
        assertEquals(249, new TopicLayout("a".repeat(249 - repartitionFixed)).repartition("dag", "node").length());
        assertThrows(IllegalArgumentException.class, () -> new TopicLayout("a".repeat(250 - aggregateFixed)).aggregates());
        assertThrows(IllegalArgumentException.class, () -> new TopicLayout("a".repeat(250 - signalFixed)).signals());
        assertThrows(IllegalArgumentException.class, () -> new TopicLayout("a".repeat(250 - repartitionFixed)).repartition("dag", "node"));
        assertThrows(IllegalArgumentException.class, () -> new TopicLayout("wind").repartition("a".repeat(249), "node"));
        assertThrows(IllegalArgumentException.class, () -> new TopicLayout("wind").repartition("dag", "a".repeat(249)));
    }
}
