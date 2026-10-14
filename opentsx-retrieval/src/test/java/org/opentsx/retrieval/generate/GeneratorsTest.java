package org.opentsx.retrieval.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Demo generators: the same request gives the same series, in one piece or in several, and stays in its range. */
class GeneratorsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long MINUTE = 60_000_000L;
    static final long T0 = Micros.of(Instant.parse("2026-10-01T00:00:00Z"));

    static JsonNode params(String json) throws Exception {
        return JSON.readTree(json);
    }

    static Generators.Target price() {
        return new Generators.Target("h2-chain", "market.price_eur_mwh", "h2-chain.market.price_eur_mwh", "EUR/MWh", "gen.ou", 7,
                -500.0, 3000.0, "abc");
    }

    @Test
    void theSameSeedGivesTheSameValuesAndAnotherSeedOthers() throws Exception {
        JsonNode p = params("{\"mean\":62.8,\"sigma\":9.0,\"reversion\":0.08}");
        double[] a = Generators.values("gen.ou", p, 7, 15 * MINUTE, 1000, 200, null, null);
        assertArrayEquals(a, Generators.values("gen.ou", p, 7, 15 * MINUTE, 1000, 200, null, null));
        assertNotEquals(a[0], Generators.values("gen.ou", p, 8, 15 * MINUTE, 1000, 200, null, null)[0]);
        double mean = java.util.Arrays.stream(a).average().orElseThrow();
        assertTrue(Math.abs(mean - 62.8) < 30, "wanders around its mean: " + mean);
    }

    @Test
    void aValueDoesNotDependOnWhereTheRunBegan() throws Exception {
        JsonNode p = params("{\"mean\":10,\"sigma\":2,\"reversion\":0.2}");
        double[] whole = Generators.values("gen.ou", p, 3, MINUTE, 5000, 100, null, null);
        double[] tail = Generators.values("gen.ou", p, 3, MINUTE, 5060, 40, null, null);
        // what came before the start has faded below a thousandth of the spread
        for (int i = 0; i < 40; i++) {
            assertEquals(whole[60 + i], tail[i], 0.05, "step " + i);
        }
        JsonNode s = params("{\"mean\":100,\"amplitude\":50,\"period\":\"PT1H\",\"sigma\":0}");
        double[] sine = Generators.values("gen.sine", s, 1, MINUTE, 0, 61, null, null);
        assertEquals(100, sine[0], 1e-9);
        assertEquals(150, sine[15], 1e-9);
        assertEquals(50, sine[45], 1e-9);
        assertEquals(sine[0], sine[60], 1e-9);
    }

    @Test
    void valuesStayInThePlausibleRangeOfTheSignal() throws Exception {
        JsonNode p = params("{\"mean\":0,\"sigma\":100,\"reversion\":0.5}");
        for (double v : Generators.values("gen.ou", p, 1, MINUTE, 0, 500, -20.0, 20.0)) {
            assertTrue(v >= -20 && v <= 20);
        }
    }

    @Test
    void episodesAreCutOnAGridSoTheSamePeriodAlwaysGivesTheSameEpisodes() throws Exception {
        JsonNode p = params("{\"mean\":62.8,\"sigma\":9.0,\"reversion\":0.08}");
        // six hours of a price every quarter of an hour: 24 values
        List<Episode> six = Generators.episodes(price(), p, 15 * MINUTE, T0, T0 + 360 * MINUTE);
        assertEquals(24, six.stream().mapToInt(Episode::getCount).sum());
        Episode e = six.get(0);
        assertEquals("h2-chain.market.price_eur_mwh", e.getSeries().getMetric());
        java.util.Map<String, String> tags = new java.util.TreeMap<>();
        e.getSeries().getTags().forEach((k, v) -> tags.put(k.toString(), v.toString()));
        assertEquals("raw", tags.get("grade"));
        assertEquals("market.price_eur_mwh", tags.get("signal"));
        assertTrue(tags.get("run").startsWith("gen-"), tags.toString());
        assertEquals("EUR/MWh", e.getSeries().getUnit());
        assertEquals("SYNTHETIC", e.getLabels().get("origin"));
        assertEquals("gen.ou", e.getLabels().get("generator"));
        assertEquals("7", e.getLabels().get("seed"));
        assertEquals("demo", e.getLabels().get("fidelity"));
        assertEquals(T0, Micros.of(six.get(0).getTStart()));
        Episodes.validate(e);
        // generated again, and as two halves: the same pieces with the same identity
        List<Episode> again = Generators.episodes(price(), p, 15 * MINUTE, T0, T0 + 360 * MINUTE);
        assertEquals(six.stream().map(Episode::getEpisodeId).toList(), again.stream().map(Episode::getEpisodeId).toList());
        long chunk = Generators.CHUNK * 15 * MINUTE;
        long boundary = Math.floorDiv(T0, chunk) * chunk + chunk;
        if (boundary < T0 + 360 * MINUTE) {
            List<Episode> first = Generators.episodes(price(), p, 15 * MINUTE, T0, boundary);
            List<Episode> second = Generators.episodes(price(), p, 15 * MINUTE, boundary, T0 + 360 * MINUTE);
            assertEquals(six.size(), first.size() + second.size());
        }
        // another period is another run, and another series: two runs never mix in one series (D85)
        List<Episode> later = Generators.episodes(price(), p, 15 * MINUTE, T0 + 1440 * MINUTE, T0 + 1800 * MINUTE,
                java.time.Instant.parse("2026-10-14T12:00:00Z"));
        assertNotEquals(e.getSeries().getSeriesId(), later.get(0).getSeries().getSeriesId());
        assertEquals("2026-10-14T12:00:00Z", later.get(0).getLabels().get("generatedAt"));
    }

    @Test
    void aRequestThatCannotBeServedSaysWhy() throws Exception {
        JsonNode ok = params("{\"mean\":1,\"sigma\":1}");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Generators.values("gen.magic", ok, 1, MINUTE, 0, 1, null, null))
                .getMessage().contains("gen.magic is not available"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Generators.values("gen.ou", params("{\"sigma\":1}"), 1, MINUTE, 0, 1, null, null))
                .getMessage().contains("needs the parameter mean"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Generators.values("gen.ou", params("{\"mean\":1,\"sigma\":1,\"reversion\":2}"), 1, MINUTE, 0, 1, null, null))
                .getMessage().contains("reversion"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Generators.episodes(price(), ok, MINUTE, T0, T0))
                .getMessage().contains("ends before it begins"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Generators.episodes(price(), ok, 1000, T0, T0 + 3_000_000_000_000L))
                .getMessage().contains("at most"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Generators.episodes(price(), ok, 0, T0, T0 + MINUTE))
                .getMessage().contains("sampling interval"));
    }
}
