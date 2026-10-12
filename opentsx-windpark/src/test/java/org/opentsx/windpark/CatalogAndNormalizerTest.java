package org.opentsx.windpark;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.ChannelDef;
import org.opentsx.windpark.catalog.ChannelKind;
import org.opentsx.windpark.catalog.IecMapping;
import org.opentsx.windpark.catalog.Park;
import org.opentsx.windpark.catalog.SourceMapping;
import org.opentsx.windpark.catalog.SourceSystem;
import org.opentsx.windpark.catalog.TurbineType;
import org.opentsx.windpark.catalog.Units;
import org.opentsx.windpark.normalize.NormalizationReport;
import org.opentsx.windpark.normalize.Normalizer;
import org.opentsx.windpark.normalize.RawReading;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogAndNormalizerTest {

    private final ChannelCatalog catalog = ChannelCatalog.standard();
    private final Park park = Park.grid("ost-01", "demo", 54.4, 13.9, TurbineType.GENERIC_3MW, 1, 3, 5, 7);

    @Test
    void standardCatalogIsVersionedAndMappedToIec() {
        assertEquals("opentsx-windpark-datapoints", catalog.name());
        assertEquals("1.0.0", catalog.version());
        assertTrue(catalog.iecEdition().startsWith("IEC 61400-25-2"));
        assertEquals(Map.of(0, "GOOD", 1, "IMPLAUSIBLE", 2, "MISSING", 3, "SUBSTITUTED", 4, "QUESTIONABLE"), catalog.qualityCodes());
        assertEquals(10, catalog.channels().size());

        Pattern openTsdbSafe = Pattern.compile("[a-z0-9._-]+");
        HashSet<String> iecRefs = new HashSet<>();
        for (ChannelDef c : catalog.channels()) {
            assertTrue(openTsdbSafe.matcher(c.metric()).matches(), c.metric());
            assertTrue(iecRefs.add(c.iecReference()), "duplicate IEC reference " + c.iecReference());
            assertEquals(c.id(), c.iecReference());
            assertTrue(c.description() != null && !c.description().isBlank());
            assertTrue(c.sampling() != null);
        }
        ChannelDef state = catalog.channel("WTUR.TurSt");
        assertEquals(ChannelKind.STATUS, state.kind());
        assertTrue(state.vendorSpecific() && state.notes().contains("extension"));
        assertEquals("FAULT", state.statusCodes().get(4));
        assertEquals("T07/WROT.RotSpd.mag.f", catalog.channel("wind.rotor.speed").iec().reference("T07"));
    }

    @Test
    void catalogRejectsInconsistentDefinitions() {
        ChannelDef c = catalog.channel("WTUR.W");
        assertThrows(IllegalArgumentException.class, () -> new ChannelCatalog("x", "1", "e", Map.of(), List.of(c, c), List.of()));
        SourceMapping broken = new SourceMapping("P", "WXYZ.Nope", "kW", null, null, false, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new ChannelCatalog("x", "1", "e", Map.of(), List.of(c),
                List.of(new SourceSystem("s", null, null, List.of(broken)))));
        assertThrows(IllegalArgumentException.class, () -> new SourceMapping("P", "WTUR.W", "kW", null, null, false,
                new SourceMapping.Aggregation(SourceMapping.AggregationFunction.MEAN, Duration.ofMinutes(10)),
                SourceMapping.TimestampReference.INSTANT, null, null, null));
        assertEquals("ld/WTUR.W", new IecMapping("ld", "WTUR", "W", null, "MV").reference("T01"));
    }

    @Test
    void unitConversions() {
        assertEquals(10.0, Units.convert(36, "km/h", "m/s"), 1e-9);
        assertEquals(1500.0, Units.convert(1_500_000, "W", "kW"), 1e-9);
        assertEquals(2500.0, Units.convert(2.5, "MW", "kW"), 1e-9);
        assertEquals(100.0, Units.convert(212, "°F", "Cel"), 1e-9);
        assertEquals(0.0, Units.convert(273.15, "K", "Cel"), 1e-9);
        assertEquals(60.0 / (2 * Math.PI), Units.convert(1, "rad/s", "rpm"), 1e-9);
        assertEquals("Cel", Units.canonical("°C"));
        assertThrows(IllegalArgumentException.class, () -> Units.convert(1, "kW", "m/s"));
        assertThrows(IllegalArgumentException.class, () -> Units.canonical("furlong"));
    }

    @Test
    void vendorTenMinuteExportIsNormalizedThroughTheMappings() {
        Normalizer n = new Normalizer(catalog, park, Duration.ofMinutes(10), "example-vendor-scada-10min");
        // 12:10 local (CEST, UTC+2) stamps the END of the interval 10:00-10:10 UTC
        LocalDateTime local = LocalDateTime.of(2026, 7, 1, 12, 10);
        Normalizer.Result r = n.normalize(List.of(
                n.local("T01", "WindSpeed_Avg", local, 8.2, null),
                n.local("T01", "WindSpeed_Max", local, 13.4, null),
                n.local("T01", "Grid_Power_Avg", local, -1_500_000, null),   // export negative, in W
                n.local("T01", "RotorSpeed_Avg", local, 1.2, null),          // rad/s, PROVISIONAL mapping
                n.local("T01", "Gen_Temp_Avg", local, 150, null),            // °F
                n.local("T01", "Nacelle_Vibration", local, 0.3, "mm/s"),     // not in the catalog
                n.local("T99", "WindSpeed_Avg", local, 8.0, null)));         // unknown turbine

        NormalizationReport rep = r.report();
        assertEquals(7, rep.readings());
        assertEquals(5, rep.accepted());
        assertEquals(1, rep.unmapped());
        assertEquals(Set("Nacelle_Vibration"), rep.unmappedNames());
        assertEquals(1, rep.unknownTurbine());
        assertEquals(1, rep.provisional());
        assertEquals(3, rep.converted()); // W->kW, rad/s->rpm, °F->Cel

        long intervalStart = Micros.of(Instant.parse("2026-07-01T10:00:00Z"));
        Episode avg = find(r.episodes(), "wind.met.wind_speed", "mean.PT10M");
        assertEquals(intervalStart, Micros.of(avg.getTStart()));
        assertArrayEquals(new double[]{8.2}, Episodes.values(avg));
        assertEquals("CONFIRMED", avg.getLabels().get("mapping.status"));
        assertEquals("WMET.HorWdSpd", avg.getLabels().get("iec"));
        assertEquals("opentsx-windpark-datapoints@1.0.0", avg.getLabels().get("catalog"));
        assertEquals("WindSpeed_Avg", avg.getLabels().get("source.channel"));

        assertArrayEquals(new double[]{13.4}, Episodes.values(find(r.episodes(), "wind.met.wind_speed", "max.PT10M")));
        assertArrayEquals(new double[]{1500.0}, Episodes.values(find(r.episodes(), "wind.turbine.power_active", "mean.PT10M")));
        Episode rotor = find(r.episodes(), "wind.rotor.speed", "mean.PT10M");
        assertArrayEquals(new double[]{11.46}, Episodes.values(rotor));
        assertEquals("PROVISIONAL", rotor.getLabels().get("mapping.status"));
        assertArrayEquals(new double[]{65.6}, Episodes.values(find(r.episodes(), "wind.generator.temperature", "mean.PT10M")));
    }

    @Test
    void rawOneHertzDataGetsQualityCodesAndAlignedWindows() {
        Normalizer n = new Normalizer(catalog, park, Duration.ofMinutes(10), "opentsx-simulator");
        Instant t0 = Instant.parse("2026-10-01T00:09:58Z");
        Normalizer.Result r = n.normalize(List.of(
                new RawReading("T02", "WMET.HorWdSpd", t0, 7.123, null),
                new RawReading("T02", "wind.met.wind_speed", t0.plusSeconds(1), 80.0, null),       // implausible
                new RawReading("T02", "wind.met.wind_speed", t0.plusSeconds(2), Double.NaN, null), // missing, next window
                new RawReading("T02", "wind.met.wind_speed", t0.plusSeconds(3), 36.0, "km/h"),
                new RawReading("T02", "wind.met.wind_speed", t0.plusSeconds(3), 36.0, "km/h"))); // duplicate
        assertEquals(2, r.episodes().size());
        Episode first = r.episodes().get(0);
        assertArrayEquals(new double[]{7.12, 80.0}, Episodes.values(first));
        assertEquals(Micros.of(Instant.parse("2026-10-01T00:10:00Z")), Micros.of(first.getTEnd()));
        byte[] q = new byte[2];
        first.getQuality().duplicate().get(q);
        assertArrayEquals(new byte[]{Normalizer.QUALITY_GOOD, Normalizer.QUALITY_IMPLAUSIBLE}, q);
        Episode second = r.episodes().get(1);
        assertEquals(10.0, Episodes.values(second)[1]);
        assertEquals(1, r.report().duplicates());
        assertEquals(1, r.report().missing());
        assertEquals("CANONICAL", second.getLabels().get("mapping.status"));
        assertThrows(IllegalArgumentException.class, () -> new Normalizer(catalog, park, Duration.ofMinutes(10), "nope"));
    }

    private Episode find(List<Episode> episodes, String metric, String aggregation) {
        return episodes.stream().filter(e -> e.getSeries().getMetric().equals(metric)
                        && aggregation.equals(e.getSeries().getTags().get("aggregation")))
                .findFirst().orElseThrow(() -> new AssertionError(metric + " " + aggregation));
    }

    private static java.util.Set<String> Set(String... s) {
        return new java.util.TreeSet<>(List.of(s));
    }
}
