package org.opentsx.dwd;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.twin.Origin;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdcObservationsTest {

    private static long t(String iso) {
        return Micros.of(Instant.parse(iso));
    }

    @Test
    void tenMinuteWindWithMissingValuesAndMissingRows() {
        List<WeatherSeries> s = CdcObservations.parse(Fixtures.lines(Fixtures.WIND_10MIN));
        assertEquals(2, s.size());   // FF_10, DD_10 (QN ignored)
        WeatherSeries ff = MosmixTest.of(s, Variable.WIND_SPEED);
        assertEquals("01358", ff.station());
        assertEquals("cdc-10min", ff.product());
        assertEquals(Origin.MEASURED, ff.origin());
        assertNull(ff.referenceTime());
        assertEquals("mean.PT10M", ff.aggregation());
        assertEquals(600_000_000L, ff.intervalMicros());
        // 00:00 .. 01:00 on a regular grid, mean over the 10 minutes BEFORE the stamp -> interval start
        assertEquals(7, ff.size());
        assertEquals(t("2026-10-11T23:50:00Z"), ff.timestamps()[0]);
        assertEquals(12.3, ff.values()[0], 1e-9);
        assertTrue(Double.isNaN(ff.values()[3]));   // -999
        assertTrue(Double.isNaN(ff.values()[5]));   // row 00:50 missing in the file
        assertEquals(11.2, ff.values()[6], 1e-9);
        assertEquals(5, ff.valid());
    }

    @Test
    void temperatureIsInstantaneousAndSolarSumsBecomeIrradiance() {
        List<WeatherSeries> tu = CdcObservations.parse(Fixtures.lines(Fixtures.TU_10MIN));
        WeatherSeries tt = MosmixTest.of(tu, Variable.AIR_TEMPERATURE);
        assertNull(tt.aggregation());
        assertEquals(t("2026-10-12T00:00:00Z"), tt.timestamps()[0]);
        assertEquals(2.3, tt.values()[0], 1e-9);
        assertTrue(Double.isNaN(tt.values()[3]));
        assertEquals("Cel", tt.seriesKey().getUnit());
        assertEquals(876.6, MosmixTest.of(tu, Variable.PRESSURE).values()[0], 1e-9);
        assertEquals(97.3, MosmixTest.of(tu, Variable.RELATIVE_HUMIDITY).values()[0], 1e-9);
        assertEquals(3, tu.size());   // PP_10, TT_10, RF_10 (TM5_10 and TD_10 are not mapped)

        List<WeatherSeries> sd = CdcObservations.parse(Fixtures.lines(Fixtures.SOLAR_10MIN));
        WeatherSeries gs = MosmixTest.of(sd, Variable.GLOBAL_IRRADIANCE);
        assertEquals(27.1 * 10_000 / 600, gs.values()[0], 1e-3);   // J/cm² in 10 min -> W/m²
        assertEquals(t("2026-10-12T09:50:00Z"), gs.timestamps()[0]);
        assertTrue(Double.isNaN(MosmixTest.of(sd, Variable.DIFFUSE_IRRADIANCE).values()[2]));
    }

    @Test
    void readsTheZipLikeTheDownload() throws IOException {
        byte[] zip = Fixtures.zip(Fixtures.WIND_10MIN, Fixtures.bytes(Fixtures.WIND_10MIN), true);
        List<WeatherSeries> z = CdcObservations.read(zip);
        assertEquals(CdcObservations.parse(Fixtures.lines(Fixtures.WIND_10MIN)).get(0).seriesKey(), z.get(0).seriesKey());
        assertThrows(IOException.class, () -> CdcObservations.read(Fixtures.zip("Metadaten_x.txt", new byte[1], false)));
    }

    @Test
    void hourlyTemperatureAndDailyEpisodes() {
        List<WeatherSeries> s = CdcObservations.parse(Fixtures.lines(Fixtures.TU_HOURLY));
        WeatherSeries tt = MosmixTest.of(s, Variable.AIR_TEMPERATURE);
        assertEquals("cdc-hourly", tt.product());
        assertEquals(48, tt.size());
        assertEquals(t("2026-01-13T23:00:00Z"), tt.timestamps()[0]);
        assertTrue(Double.isNaN(tt.values()[30]));

        List<Episode> eps = tt.toEpisodes(Duration.ofDays(1));
        assertEquals(3, eps.size());   // UTC days 13 (one hour), 14, 15
        eps.forEach(Episodes::validate);
        Episode day = eps.get(1);
        assertEquals(24, day.getCount());
        assertEquals(SegmentationStrategy.FIXED_DURATION, day.getSegmentation().getStrategy());
        assertEquals("MEASURED", day.getLabels().get("dwd.origin"));
        assertNull(day.getLabels().get("forecast.reference_time"));
        assertEquals(t("2026-01-15T00:00:00Z"), Micros.of(day.getTEnd()));
        assertEquals(WeatherSeries.QUALITY_MISSING, eps.get(2).getQuality().get(5));   // 05:00 UTC
    }

    @Test
    void tenMinuteDataBefore2000AreMez() {
        List<WeatherSeries> s = CdcObservations.parse(List.of("STATIONS_ID;MESS_DATUM;  QN;PP_10;TT_10;TM5_10;RF_10;TD_10;eor",
                "        183;199912311200;    1;-999;   1.0;-999;-999;-999;eor"));
        assertEquals(t("1999-12-31T11:00:00Z"), MosmixTest.of(s, Variable.AIR_TEMPERATURE).timestamps()[0]);
    }

    @Test
    void rejectsForeignFiles() {
        assertThrows(IllegalArgumentException.class, () -> CdcObservations.parse(List.of("a;b;c", "1;2;3")));
        assertThrows(IllegalArgumentException.class, () -> CdcObservations.parse(List.of(
                "STATIONS_ID;MESS_DATUM;  QN;FF_10;DD_10;eor",
                "       1358;202610120000;    2;  12.3; 290;eor",
                "        183;202610120010;    2;  13.0; 290;eor")));
        assertEquals("https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/10_minutes/air_temperature/now/10minutenwerte_TU_01358_now.zip",
                CdcObservations.nowUrl("air_temperature", "01358"));
    }

    @Test
    void hourlyWindIsAMeanOverTheHourBeforeTheStamp() {
        List<WeatherSeries> s = CdcObservations.parse(Fixtures.lines(Fixtures.FF_HOURLY));
        assertEquals(2, s.size());   // F, D (QN_3 ignored)
        WeatherSeries f = MosmixTest.of(s, Variable.WIND_SPEED);
        assertEquals("01759", f.station());
        assertEquals("cdc-hourly", f.product());
        assertEquals("mean.PT1H", f.aggregation());
        assertEquals("F", f.sourceElement());
        assertEquals(6, f.size());
        assertEquals(t("2025-09-30T23:00:00Z"), f.timestamps()[0]);   // stamp 2025100100 = hour 23-24 UTC
        assertEquals(3.6, f.values()[0], 1e-9);
        assertEquals(3.4, f.values()[5], 1e-9);
        WeatherSeries d = MosmixTest.of(s, Variable.WIND_DIRECTION);
        assertEquals("deg", d.seriesKey().getUnit());
        assertEquals(t("2025-09-30T23:00:00Z"), d.timestamps()[0]);
        assertEquals(140, d.values()[0], 1e-9);
    }

    @Test
    void hourlyGustPressureAndPrecipitation() {
        WeatherSeries fx = MosmixTest.of(CdcObservations.parse(Fixtures.lines(Fixtures.FX_HOURLY)), Variable.WIND_GUST);
        assertEquals("max.PT1H", fx.aggregation());
        assertEquals("FX_911", fx.sourceElement());
        assertEquals(t("2025-09-30T23:00:00Z"), fx.timestamps()[0]);
        assertEquals(5.8, fx.values()[0], 1e-9);

        List<WeatherSeries> p = CdcObservations.parse(Fixtures.lines(Fixtures.P0_HOURLY));
        assertEquals(1, p.size());   // P0 at station level; P (sea level) is not mapped
        WeatherSeries p0 = p.get(0);
        assertEquals(Variable.PRESSURE, p0.variable());
        assertNull(p0.aggregation());   // instantaneous: stays at its stamp
        assertEquals(t("2025-10-01T00:00:00Z"), p0.timestamps()[0]);
        assertEquals(1030.5, p0.values()[0], 1e-9);
        assertEquals("hPa", p0.seriesKey().getUnit());

        List<WeatherSeries> r = CdcObservations.parse(Fixtures.lines(Fixtures.RR_HOURLY));
        assertEquals(1, r.size());   // R1; RS_IND and WRTR are not mapped
        WeatherSeries r1 = r.get(0);
        assertEquals(Variable.PRECIPITATION, r1.variable());
        assertEquals("sum.PT1H", r1.aggregation());
        assertEquals(t("2025-09-30T23:00:00Z"), r1.timestamps()[0]);
        assertEquals(0.0, r1.values()[0], 1e-9);
        assertEquals(6, r1.valid());
    }

    @Test
    void hourlySolarIsStampedAtTheEndOfTheTrueSolarHour() {
        // MESS_DATUM carries minutes: the UTC moment at which the hour in true solar time (MESS_DATUM_WOZ) ends;
        // at Arkona on 2025-07-01 that is hh:10 UTC. It is rounded to the nearest full hour, then the hour before it.
        List<WeatherSeries> s = CdcObservations.parse(Fixtures.lines(Fixtures.ST_HOURLY));
        WeatherSeries g = MosmixTest.of(s, Variable.GLOBAL_IRRADIANCE);
        assertEquals("cdc-hourly", g.product());
        assertEquals("mean.PT1H", g.aggregation());
        assertEquals(10, g.size());
        assertEquals(t("2025-07-01T06:00:00Z"), g.timestamps()[0]);   // 07:10 -> 07:00 end -> 06:00 start
        assertEquals(178.0 * 10_000 / 3600, g.values()[0], 1e-3);     // J/cm² per hour -> W/m²
        // the brightest hours sit around solar noon (11:10 UTC there): 10-11 and 11-12 UTC
        assertEquals(t("2025-07-01T10:00:00Z"), g.timestamps()[4]);
        assertEquals(313.0 * 10_000 / 3600, g.values()[4], 1e-3);
        assertEquals(314.0 * 10_000 / 3600, g.values()[5], 1e-3);
        assertEquals(27.0 * 10_000 / 3600, MosmixTest.of(s, Variable.DIFFUSE_IRRADIANCE).values()[4], 1e-3);
        // a stamp just before the full hour belongs to that hour, not to the one before
        List<WeatherSeries> late = CdcObservations.parse(List.of(
                "STATIONS_ID;MESS_DATUM;QN_592;ATMO_LBERG;FD_LBERG;FG_LBERG;SD_LBERG;ZENIT;MESS_DATUM_WOZ;eor",
                "        183;2025100100:56;    1;  121.0;    0.0;    0.0;   0;   125.38;2025100102:00;eor",
                "        183;2025100101:56;    1;  121.0;    0.0;    0.0;   0;   120.10;2025100103:00;eor"));
        assertEquals(t("2025-10-01T00:00:00Z"), MosmixTest.of(late, Variable.GLOBAL_IRRADIANCE).timestamps()[0]);
    }
}
