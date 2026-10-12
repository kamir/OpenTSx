package org.opentsx.dwd;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.Value;
import org.opentsx.twin.boat.BoatData;
import org.opentsx.twin.demo.BoatDemo;
import org.opentsx.twin.house.BillCalibration;
import org.opentsx.twin.house.HouseScenario;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DWD series into the twin: resampling, port bindings with origin, the CSV formats the twin demos read. */
class TwinWeatherTest {

    private static final long MIN15 = 900_000_000L;

    @Test
    void resamplesByMeanAndHoldsForOneSourceInterval() throws IOException {
        WeatherSeries ghi = MosmixTest.of(MosmixTest.fixture().series(), Variable.GLOBAL_IRRADIANCE);
        long t0 = Micros.of(Instant.parse("2026-10-12T09:00:00Z"));
        // hourly forecast into 15-minute steps: each value feeds four steps, then the series ends
        double[] q = TwinWeather.resample(ghi, t0, MIN15, 28);
        assertEquals(200, q[0], 1e-9);
        assertEquals(200, q[3], 1e-9);
        assertEquals(250, q[4], 1e-9);
        assertEquals(510 / 3.6, q[23], 1e-3);
        assertTrue(Double.isNaN(q[24]));

        // 10-minute wind into hourly steps: mean of the valid values, NaN ignored
        WeatherSeries ff = MosmixTest.of(CdcObservations.parse(Fixtures.lines(Fixtures.WIND_10MIN)), Variable.WIND_SPEED);
        double[] h = TwinWeather.resample(ff, Micros.of(Instant.parse("2026-10-12T00:00:00Z")), 3_600_000_000L, 1);
        assertEquals((13.0 + 12.6 + 11.9 + 11.2) / 4, h[0], 1e-9);
    }

    @Test
    void feedsTheHouseWeatherAtomWithForecastOrigin() throws IOException {
        List<WeatherSeries> s = MosmixTest.fixture().series();
        Instant start = Instant.parse("2026-10-12T10:00:00Z");
        TwinPlan plan = HouseScenario.withParams(HouseScenario.plan(), Map.of(), start, 6);
        String weather = HouseScenario.id(plan, "weather");
        SeriesInputs in = TwinWeather.bind(plan, s, TwinWeather.housePorts(weather));
        assertEquals(java.util.Set.of(weather + ".t_out_meas_c", weather + ".ghi_meas_w_m2"), in.ports());
        Value v = in.value(weather + ".t_out_meas_c", Micros.of(start)).orElseThrow();
        assertEquals(new Value(3.9, Origin.FORECAST), v);

        RunResult r = new TwinRunner(plan).run(new TwinRunner.RunSpec(Mode.SHADOW, in, List.of(), null, 0, null, null));
        assertArrayEquals(new double[]{3.9, 4.2, 4.5, 4.7, 4.8, 4.5}, r.series(weather + ".t_out_c"), 1e-9);
        assertEquals(250, r.series(weather + ".ghi_w_m2")[0], 1e-9);   // hour 10-11 UTC = Rad1h at 11:00
        assertEquals(Origin.FORECAST, r.origin(weather + ".t_out_c"));

        // without DWD data the same plan runs on the synthetic climate
        RunResult syn = new TwinRunner(plan).run(new TwinRunner.RunSpec(Mode.SHADOW, null, List.of(), null, 0, null, null));
        assertEquals(Origin.SYNTHETIC, syn.origin(weather + ".t_out_c"));
    }

    @Test
    void dailyCsvIsTheHouseCalibrationWeatherFormat() throws IOException {
        List<WeatherSeries> s = CdcObservations.parse(Fixtures.lines(Fixtures.TU_HOURLY));
        StringWriter w = new StringWriter();
        assertEquals(2, WeatherCsv.writeDailyTemperatures(s, HouseScenario.ZONE, w));
        List<String> lines = w.toString().lines().toList();
        assertEquals("datum,t_mittel_c", lines.get(1));
        assertTrue(lines.get(2).startsWith("2026-01-14,-4"), lines.get(2));
        assertTrue(lines.get(3).startsWith("2026-01-15,1"), lines.get(3));
        // bin/opentsx house --calibrate bills.csv --weather <this file>
        SeriesInputs bound = BillCalibration.dailyTemperatures(lines, "weather", 5.0);
        assertEquals(Origin.MEASURED, bound.value("weather.t_out_meas_c",
                Micros.of(Instant.parse("2026-01-14T12:00:00Z"))).orElseThrow().origin());
    }

    @Test
    void boatCsvBindsTheSolarIrradiance() throws IOException {
        List<WeatherSeries> s = CdcObservations.parse(Fixtures.lines(Fixtures.SOLAR_10MIN));
        StringWriter w = new StringWriter();
        assertEquals(3, WeatherCsv.writeBoatIrradiance(s, w));
        BoatData d = BoatData.parse(w.toString().lines().toList(), BoatDemo.plan(), ZoneOffset.UTC);
        assertEquals(3, d.rows());
        assertTrue(d.coverage().keySet().stream().anyMatch(k -> k.endsWith(".ghi_wm2")), d.coverage().toString());
        assertTrue(d.ignored().isEmpty(), d.ignored().toString());
    }

    @Test
    void wideCsvHasOneRowPerTimeAndOneColumnPerVariable() throws IOException {
        StringWriter w = new StringWriter();
        WeatherCsv.writeWide(MosmixTest.fixture().series(), w);
        List<String> lines = w.toString().lines().toList();
        assertEquals("time_utc,station,product,origin,reference_time,lead_h,wind_speed_m_s,wind_direction_deg,"
                + "air_temperature_c,pressure_hpa,global_irradiance_w_m2,cloud_cover_pct,precipitation_mm", lines.get(1));
        assertEquals(1 + 1 + 7, lines.size());   // comment, header, 09:00 (irradiance hour 09-10) .. 15:00
        assertEquals("2026-10-12T09:00:00Z,10578,mosmix-l,FORECAST,2026-10-12T09:00:00Z,0,,,,,200,,0", lines.get(2));
        assertEquals("2026-10-12T12:00:00Z,10578,mosmix-l,FORECAST,2026-10-12T09:00:00Z,3,,292,4.5,,225,86,0", lines.get(5));
        assertEquals(Duration.ofHours(1).toNanos() / 1000, MosmixTest.fixture().series().get(0).intervalMicros());
    }
}
