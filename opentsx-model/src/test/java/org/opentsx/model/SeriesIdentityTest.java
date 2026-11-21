package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.SeriesKey;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeriesIdentityTest {

    @Test
    void xxHash64MatchesReferenceVectors() {
        for (JsonNode v : TestVectors.load("xxh64.json")) {
            byte[] input = v.get("input").asText().getBytes(StandardCharsets.UTF_8);
            assertEquals(v.get("hex").asText(), XxHash64.hex(XxHash64.hash(input)), "input: " + v.get("input"));
        }
    }

    @Test
    void canonicalFormAndSeriesIdMatchSharedVectors() {
        for (JsonNode v : TestVectors.load("series-id.json")) {
            String metric = v.get("metric").asText();
            Map<String, String> tags = TestVectors.map(v.get("tags"));
            assertEquals(v.get("canonical").asText(), SeriesKeys.canonical(metric, tags));
            assertEquals(v.get("seriesId").asText(), SeriesKeys.seriesId(metric, tags));
        }
    }

    @Test
    void unitIsNotPartOfTheIdentity() {
        SeriesKey kw = SeriesKeys.of("wind.turbine.power_active", Map.of("turbine", "T07"), "kW");
        SeriesKey w = SeriesKeys.of("wind.turbine.power_active", Map.of("turbine", "T07"), "W");
        assertEquals(kw.getSeriesId(), w.getSeriesId());
    }

    @Test
    void verifyRejectsTamperedKey() {
        SeriesKey key = SeriesKeys.of("m", Map.of("a", "1"));
        key.setTags(Map.of("a", "2"));
        assertThrows(IllegalArgumentException.class, () -> SeriesKeys.verify(key));
    }

    @Test
    void rejectsEmptyMetricAndKeys() {
        assertThrows(IllegalArgumentException.class, () -> SeriesKeys.seriesId("", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SeriesKeys.seriesId("m", Map.of("", "v")));
    }

    @Test
    void ulidIsSortableAndWellFormed() {
        String a = Ulid.of(1_700_000_000_000L, new java.util.Random(1));
        String b = Ulid.of(1_700_000_000_001L, new java.util.Random(1));
        assertEquals(26, a.length());
        assertEquals(-1, Integer.signum(a.compareTo(b)));
        assertEquals("01HF7YAT00", a.substring(0, 10)); // same time prefix as the Python implementation
    }

    @Test
    void bucketChecksumMatchesSharedVectors() {
        for (JsonNode v : TestVectors.load("bucket-checksum.json")) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            v.get("episodeIds").forEach(n -> ids.add(n.asText()));
            assertEquals(v.get("checksum").asText(), Buckets.checksum(ids));
        }
    }

    @Test
    void bucketContentChecksumMatchesSharedVectors() {
        JsonNode vectors = TestVectors.load("bucket-content-checksum.json");
        assertEquals(5, vectors.size());
        for (JsonNode v : vectors) {
            assertEquals(v.get("checksum").asText(), Buckets.contentChecksum(
                    TestVectors.strings(v.get("episodeIds")), TestVectors.strings(v.get("contentDigests"))),
                    v.get("name").asText());
        }
        assertNotEquals(vectors.get(1).get("checksum"), vectors.get(2).get("checksum"));
        assertEquals(vectors.get(1).get("episodeIds"), vectors.get(2).get("episodeIds"));
    }

    @Test
    void bucketContentChecksumRejectsInconsistentInput() {
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        assertMessage("1 content digests for 2 episode ids",
                () -> Buckets.contentChecksum(java.util.List.of("e1", "e2"), java.util.List.of(a)));
        assertMessage("episode e1 appears with two contents",
                () -> Buckets.contentChecksum(java.util.List.of("e1", "e1"), java.util.List.of(a, b)));
        assertMessage("content digest of episode e1 is not 64 lower-case hex characters",
                () -> Buckets.contentChecksum(java.util.List.of("e1"), java.util.List.of("A".repeat(64))));
        assertMessage("episode ids must not be empty",
                () -> Buckets.contentChecksum(java.util.List.of(""), java.util.List.of(a)));
    }

    private static void assertMessage(String message, org.junit.jupiter.api.function.Executable call) {
        assertEquals(message, assertThrows(IllegalArgumentException.class, call).getMessage());
    }
}
