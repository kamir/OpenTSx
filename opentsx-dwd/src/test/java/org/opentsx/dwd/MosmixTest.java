package org.opentsx.dwd;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.twin.Origin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MosmixTest {

    static Mosmix.Forecast fixture() throws IOException {
        return Mosmix.read(new ByteArrayInputStream(Fixtures.bytes(Fixtures.KML)), null);
    }

    static WeatherSeries of(List<WeatherSeries> s, Variable v) {
        return s.stream().filter(x -> x.variable() == v).findFirst().orElseThrow();
    }

    @Test
    void parsesTheProductDefinitionAndThePlacemark() throws IOException {
        Mosmix.Forecast f = fixture();
        assertEquals("mosmix-l", f.product());
        assertEquals(Instant.parse("2026-10-12T09:00:00Z"), f.issueTime());
        assertEquals(List.of("ICON@2026-10-12T00:00:00Z", "ECMWF/IFS@2026-10-12T00:00:00Z"), f.models());
        assertEquals(6, f.timeSteps().length);
        assertEquals(Micros.of(Instant.parse("2026-10-12T10:00:00Z")), f.timeSteps()[0]);
        Mosmix.Placemark p = f.placemark("10578");
        assertEquals("FICHTELBERG", p.name());
        assertEquals(50.43, p.latitude(), 1e-9);
        assertEquals(12.95, p.longitude(), 1e-9);
        assertEquals(1213, p.elevation(), 1e-9);
        assertEquals(Set.of("PPPP", "TTT", "DD", "FF", "FX1", "N", "RR1c", "Rad1h"), p.elements().keySet());
        assertTrue(Double.isNaN(p.elements().get("FF")[2]));     // "-" = undefined
    }

    @Test
    void readsTheKmzLikeTheKml() throws IOException {
        byte[] kmz = Fixtures.zip("MOSMIX_L_2026101209_10578.kml", Fixtures.bytes(Fixtures.KML), false);
        Mosmix.Forecast z = Mosmix.read(new ByteArrayInputStream(kmz), null);
        Mosmix.Forecast k = fixture();
        assertEquals(k.issueTime(), z.issueTime());
        assertArrayEquals(k.placemark("10578").elements().get("TTT"), z.placemark("10578").elements().get("TTT"));
        // station filter (used for the all-stations file)
        assertEquals(0, Mosmix.read(new ByteArrayInputStream(kmz), Set.of("10579")).placemarks().size());
    }

    @Test
    void convertsToCanonicalUnitsAndStampsAggregatesAtIntervalStart() throws IOException {
        List<WeatherSeries> s = fixture().series("10578");
        assertEquals(7, s.size());   // FF DD TTT PPPP N Rad1h RR1c (FX1 is not converted)
        s.forEach(x -> {
            assertEquals(Origin.FORECAST, x.origin());
            assertEquals(Instant.parse("2026-10-12T09:00:00Z"), x.referenceTime());
            assertEquals(3_600_000_000L, x.intervalMicros());
        });
        WeatherSeries t = of(s, Variable.AIR_TEMPERATURE);
        assertArrayEquals(new double[]{3.9, 4.2, 4.5, 4.7, 4.8, 4.5}, t.values(), 1e-9);   // K -> °C
        assertNull(t.aggregation());
        assertEquals(Micros.of(Instant.parse("2026-10-12T10:00:00Z")), t.timestamps()[0]);

        WeatherSeries ghi = of(s, Variable.GLOBAL_IRRADIANCE);
        assertEquals("mean.PT1H", ghi.aggregation());
        assertEquals(200.0, ghi.values()[0], 1e-9);   // 720 kJ/m² in one hour = 200 W/m²
        assertEquals(250.0, ghi.values()[1], 1e-9);
        assertEquals(Micros.of(Instant.parse("2026-10-12T09:00:00Z")), ghi.timestamps()[0]);   // hour 09-10 UTC

        WeatherSeries rr = of(s, Variable.PRECIPITATION);
        assertEquals("sum.PT1H", rr.aggregation());
        assertEquals(0.3, rr.values()[2], 1e-9);

        WeatherSeries ff = of(s, Variable.WIND_SPEED);
        assertEquals(7.72, ff.values()[0], 1e-9);
        assertTrue(Double.isNaN(ff.values()[2]));
        assertEquals(5, ff.valid());
        assertEquals(0, of(s, Variable.PRESSURE).valid());
        assertEquals(89, of(s, Variable.CLOUD_COVER).values()[0], 1e-9);
        assertEquals(287, of(s, Variable.WIND_DIRECTION).values()[0], 1e-9);
    }

    @Test
    void seriesIdentityAndEpisodes() throws IOException {
        List<WeatherSeries> s = fixture().series("10578");
        WeatherSeries ff = of(s, Variable.WIND_SPEED);
        assertEquals(SeriesKeys.of("weather.wind_speed", Map.of("source", "dwd", "product", "mosmix-l", "station", "10578"), "m/s"),
                ff.seriesKey());
        assertEquals("mean.PT1H", of(s, Variable.GLOBAL_IRRADIANCE).seriesKey().getTags().get("aggregation"));

        List<Episode> eps = ff.toEpisodes(Duration.ofDays(1));
        assertEquals(1, eps.size());   // a forecast run is one episode
        Episode e = eps.get(0);
        Episodes.validate(e);
        assertEquals(6, e.getCount());
        assertEquals(3_600_000_000L, e.getIntervalMicros());
        assertEquals(Micros.of(Instant.parse("2026-10-12T16:00:00Z")), Micros.of(e.getTEnd()));
        assertEquals("2026-10-12T09:00:00Z", e.getLabels().get("forecast.reference_time"));
        assertEquals("FORECAST", e.getLabels().get("dwd.origin"));
        assertEquals("FF", e.getLabels().get("dwd.element"));
        assertEquals(SegmentationStrategy.MANUAL, e.getSegmentation().getStrategy());
        assertEquals(WeatherSeries.QUALITY_MISSING, e.getQuality().get(2));
        // deterministic ids: the same run collected twice gives the same episode
        assertEquals(e.getEpisodeId(), of(fixture().series("10578"), Variable.WIND_SPEED).toEpisodes(Duration.ofDays(1))
                .get(0).getEpisodeId());
        // only-missing series produce no episode
        assertTrue(of(s, Variable.PRESSURE).toEpisodes(Duration.ofDays(1)).isEmpty());
        assertFalse(of(s, Variable.AIR_TEMPERATURE).toEpisodes(Duration.ofDays(1)).get(0).getLabels().isEmpty());
    }

    @Test
    void rejectsBrokenInput() {
        assertThrows(IOException.class, () -> Mosmix.read(new ByteArrayInputStream("<kml/>".getBytes()), null));
        String bad = new String(Fixtures.bytes(Fixtures.KML), java.nio.charset.StandardCharsets.ISO_8859_1)
                .replace("277.05     277.35", "277.05");
        assertThrows(IllegalArgumentException.class, () -> Mosmix.read(new ByteArrayInputStream(bad.getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1)), null));
        assertEquals("https://opendata.dwd.de/weather/local_forecasts/mos/MOSMIX_L/single_stations/10578/kml/MOSMIX_L_LATEST_10578.kmz",
                Mosmix.latestUrl("10578"));
    }
}
