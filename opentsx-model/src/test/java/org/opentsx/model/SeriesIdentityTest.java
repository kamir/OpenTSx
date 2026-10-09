package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.SeriesKey;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
