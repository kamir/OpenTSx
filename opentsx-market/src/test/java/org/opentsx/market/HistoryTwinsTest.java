package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.twin.BoatHistory;
import org.opentsx.market.twin.H2History;
import org.opentsx.market.twin.HistoryInputs;
import org.opentsx.market.twin.HistorySites;
import org.opentsx.market.twin.HouseHistory;
import org.opentsx.market.twin.WindPower;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.demo.BoatDemo;
import org.opentsx.twin.house.HouseKpis;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentsx.market.ParsersTest.t;

class HistoryTwinsTest {

    static Path archiveDir() {
        try {
            return Path.of(HistoryTwinsTest.class.getResource("/market/history").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static HistoryInputs history() {
        return new HistoryInputs(new Archive(archiveDir()));
    }

    @Test
    void resampleAveragesFinerHoldsCoarserAndLeavesUncoveredStepsEmpty() {
        long t0 = t("2024-01-01T00:00:00Z");
        List<Point> qh = List.of(new Point(t0, Micro.QUARTER, 1), new Point(t0 + Micro.QUARTER, Micro.QUARTER, 2),
                new Point(t0 + 2 * Micro.QUARTER, Micro.QUARTER, 3), new Point(t0 + 3 * Micro.QUARTER, Micro.QUARTER, 6),
                new Point(t0 + 4 * Micro.QUARTER, Micro.QUARTER, 8));
        double[] h = HistoryInputs.resample(qh, t0, Micro.HOUR, 2);
        assertEquals(3.0, h[0], 1e-12);
        assertTrue(Double.isNaN(h[1]));   // one quarter of the second hour is not enough
        double[] q = HistoryInputs.resample(List.of(new Point(t0, Micro.HOUR, 42)), t0, Micro.QUARTER, 5);
        assertEquals(42, q[3]);
        assertTrue(Double.isNaN(q[4]));
        assertEquals(0.8, HistoryInputs.coverage(q), 1e-12);
    }

    @Test
    void windPowerModels() {
        assertEquals(0, WindPower.curve(2.9));
        assertEquals(1, WindPower.curve(13));
        assertEquals(0, WindPower.curve(25));
        assertTrue(WindPower.curve(8) > 0.2 && WindPower.curve(8) < 0.3);
        double[] p = WindPower.fromNational(new double[]{1000, 3000, Double.NaN, 2000}, 12_000, 0.3);
        assertEquals(1800, p[0], 1e-9);   // 1000 / mean 2000 * 0.3 * 12 MW
        assertTrue(Double.isNaN(p[2]));
        double[] s = WindPower.fromStation(new double[]{10}, 12_000, 10, 120, 0.11);
        assertTrue(s[0] > 0.88 * 12_000 * 0.9);   // 10 m/s at 10 m is above 13 m/s at 120 m
    }

    @Test
    void h2ChainOnRealPricesAndWindAcrossTheDstSwitch() {
        HistorySites.Site park = HistorySites.get(null, HistorySites.PARK);
        H2History s = new H2History(history(), park, 0.3);
        TwinPlan plan = H2History.plan(Instant.parse("2024-03-30T00:00:00Z"), 2 * 96);
        H2History.Run always = s.inputs(plan, "smard", 1.0);
        assertEquals(Map.of("price", 1.0, "forecast", 1.0, "wind", 1.0), always.coverage());
        RunResult r = H2History.run(always, Mode.SHADOW);
        assertEquals(Origin.REPORTED, r.origin("market.price_eur_mwh"));
        assertEquals(Origin.ESTIMATED, r.origin("park.p_gen_kw"));
        Map<String, Double> k = H2History.kpis(always, r);
        assertTrue(k.get("h2_kg") > 0);
        assertEquals(0, k.get("trips"));
        assertTrue(k.get("p_max_bar") < 40);

        H2History.Run cheap = s.inputs(plan, "smard", 0.2);
        RunResult rc = H2History.run(cheap, Mode.WHAT_IF);
        double[] ely = rc.series("ely.p_el_kw");
        for (int i = 0; i < ely.length; i++) {
            if (cheap.price()[i] > cheap.priceLimit()) {
                assertEquals(0, ely[i], 1e-9, "electrolyzer off above the price limit, step " + i);
            }
        }
        Map<String, Double> kc = H2History.kpis(cheap, rc);
        assertTrue(kc.get("h2_kg") < k.get("h2_kg"));
        assertTrue(kc.get("power_cost_eur_per_kg") < k.get("power_cost_eur_per_kg"));
        // the same plan on station wind through the power curve
        H2History.Run dwd = s.inputs(plan, "dwd", 1.0);
        assertEquals(1.0, dwd.coverage().get("wind"));
        assertTrue(H2History.kpis(dwd, H2History.run(dwd, Mode.WHAT_IF)).get("park_mwh") > 0);
    }

    @Test
    void houseOnMeasuredTemperatures() {
        HouseHistory hh = new HouseHistory(history(), HistorySites.get(null, HistorySites.HOUSE));
        HouseHistory.WeatherYear w = hh.window(2024, LocalDate.of(2024, 1, 15), 72);
        assertEquals(71 / 72.0, w.temperatureCoverage(), 1e-12);   // one hour missing in the fixture
        RunResult r = HouseHistory.shadow(w);
        double[] t = r.series("weather.t_out_c");
        Origin[] o = r.origins("weather.t_out_c");
        List<Point> fixture = w.plan() == null ? List.of() : new Archive(archiveDir())
                .read(MarketCatalog.dwd("03166", "air_temperature"), 2024);
        assertEquals(fixture.get(0).value(), t[0], 1e-12);
        assertEquals(Origin.MEASURED, o[0]);
        assertEquals(Origin.SYNTHETIC, o[40]);   // the missing hour falls back to the synthetic climate
        HouseKpis k = HouseKpis.of(w.plan(), r, Double.NaN);
        assertTrue(k.hddKd() > 3 * 20 && k.hddKd() < 3 * 30);    // three cold days, G20/15
        assertTrue(k.oilLitres() > 0);
        assertTrue(HouseHistory.meanTemperature(w) < -5);
    }

    @Test
    void boatOnMeasuredIrradiance() {
        BoatHistory bh = new BoatHistory(history(), HistorySites.get(null, HistorySites.BOAT));
        assertEquals(LocalDate.of(2024, 7, 6), BoatHistory.defaultStart(2024));
        BoatHistory.Trip trip = bh.trip(BoatHistory.defaultStart(2024), 2);
        assertEquals(1.0, trip.radiationCoverage(), 1e-12);
        assertEquals(Instant.parse("2024-07-05T22:00:00Z"), trip.plan().start());
        Twin twin = new Twin(trip.plan());
        RunResult real = twin.shadow(trip.inputs(), BoatDemo.reference());
        RunResult synth = twin.shadow(trip.logbookOnly(), BoatDemo.reference());
        // worst origin of the step's inputs: measured irradiance, reported logbook mode; synthetic without data
        assertEquals(Origin.REPORTED, real.origins("solar.ghi_wm2")[50]);
        assertEquals(Origin.SYNTHETIC, synth.origins("solar.ghi_wm2")[50]);
        assertNotEquals(BoatDemo.kpis(real).get("solar_yield_wh"), BoatDemo.kpis(synth).get("solar_yield_wh"));
        double[] ghi = real.series("solar.ghi_wm2");
        assertEquals(0, ghi[0]);              // local midnight
        assertTrue(ghi[13 * 4] > 500);        // 13:00 local on the clear day
    }

    @Test
    void commandLineRunsOnTheFixtureArchiveAndExplainsMissingData() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(b, true, StandardCharsets.UTF_8);
        java.lang.reflect.Method run = Class.forName("org.opentsx.market.twin.HistoryTwins")
                .getDeclaredMethod("run", String.class, Map.class, PrintStream.class);
        run.setAccessible(true);
        String dir = archiveDir().toString();
        assertEquals(0, run.invoke(null, "twin", Map.of("history", dir, "year", "2024", "from", "2024-03-30",
                "days", "2"), out));
        assertEquals(0, run.invoke(null, "boat", Map.of("history", dir, "weather-year", "2024", "days", "2"), out));
        String s = b.toString(StandardCharsets.UTF_8);
        assertTrue(s.contains("power_cost_eur_per_kg"));
        assertTrue(s.contains("measured irradiance"));
        Exception e = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> run.invoke(null, "twin", Map.of("history", dir, "year", "2019"), out));
        assertTrue(e.getCause().getMessage().contains("market backfill"));
    }
}
