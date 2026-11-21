package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EpisodeContentDigestTest {

    private final SeriesKey key = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "ber-01"), "kW");

    @Test
    void contentDigestsMatchSharedVectors() {
        JsonNode vectors = TestVectors.load("episode-digest.json");
        assertEquals(5, vectors.size());
        for (JsonNode v : vectors) {
            assertEquals(v.get("contentDigest").asText(), Episodes.contentDigest(fromSpec(v.get("spec"))),
                    v.get("name").asText());
        }
        assertEquals(vectors.get(0).get("contentDigest"), vectors.get(1).get("contentDigest"));
        assertNotEquals(vectors.get(0).get("contentDigest"), vectors.get(2).get("contentDigest"));
    }

    @Test
    void everyNaNGivesTheSameDigest() {
        double[] payloads = {Double.longBitsToDouble(0x7ff0000000000001L), Double.longBitsToDouble(0xfff8000000000000L),
                Double.longBitsToDouble(0x7fffffffffffffffL)};
        String canonical = digest(new double[]{1.0, Double.NaN});
        for (double nan : payloads) {
            assertEquals(canonical, digest(new double[]{1.0, nan}), Long.toHexString(Double.doubleToRawLongBits(nan)));
        }
    }

    @Test
    void metadataDoesNotChangeTheDigest() {
        Episode plain = Episodes.regular(key, 0, 1_000, new double[]{1, 2}).createdAt(1).build();
        Episode decorated = Episodes.regular(key, 0, 1_000, new double[]{1, 2}).episodeId("other").label("a", "b")
                .bucketId("bucket").revision(3).source("elsewhere").pipeline("p").parentEpisodeId("parent")
                .createdAt(2).build();
        assertEquals(Episodes.contentDigest(plain), Episodes.contentDigest(decorated));
    }

    @Test
    void everyContentFieldChangesTheDigest() {
        double[] v = {1.0, 0.0};
        List<String> digests = new ArrayList<>();
        digests.add(Episodes.contentDigest(Episodes.regular(key, 0, 1_000, v).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(key, 0, 1_000, new double[]{1.0, -0.0}).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(key, 1, 1_000, v).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(key, 0, 1_001, v).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(key, 0, 1_000, v).tEnd(5_000).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(key, 0, 1_000, v).quality(new byte[2]).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(
                SeriesKeys.of(key.getMetric(), key.getTags(), "W"), 0, 1_000, v).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(
                SeriesKeys.of(key.getMetric(), key.getTags(), null), 0, 1_000, v).build()));
        digests.add(Episodes.contentDigest(Episodes.regular(
                SeriesKeys.of(key.getMetric(), Map.of("park", "ber-02"), "kW"), 0, 1_000, v).build()));
        assertEquals(digests.size(), digests.stream().distinct().count(), digests.toString());
    }

    @Test
    void rejectsInconsistentEpisodes() {
        Episode e = Episodes.regular(key, 0, 1_000, new double[]{1, 2}).build();
        e.setValues(List.of(1.0));
        assertEquals("count 2 != values 1",
                assertThrows(IllegalArgumentException.class, () -> Episodes.contentDigest(e)).getMessage());
    }

    private String digest(double[] values) {
        return Episodes.contentDigest(Episodes.regular(key, 0, 1_000, values).build());
    }

    private static Episode fromSpec(JsonNode s) {
        SeriesKey series = SeriesKeys.of(s.get("metric").asText(), TestVectors.map(s.get("tags")),
                s.get("unit").isNull() ? null : s.get("unit").asText());
        double[] values = TestVectors.numbers(s.get("values")).stream().mapToDouble(Double::doubleValue).toArray();
        Episodes.Draft d = "regular".equals(s.get("kind").asText())
                ? Episodes.regular(series, s.get("tStartUs").asLong(), s.get("intervalUs").asLong(), values)
                : Episodes.irregular(series, TestVectors.longs(s.get("timestampsUs")), values);
        if (!s.get("tEndUs").isNull()) {
            d.tEnd(s.get("tEndUs").asLong());
        }
        if (!s.get("qualityHex").isNull()) {
            d.quality(TestVectors.bytes(s.get("qualityHex").asText()));
        }
        TestVectors.map(s.get("labels")).forEach(d::label);
        return d.episodeId(s.get("episodeId").asText()).build();
    }
}
