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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // the second form of the plan at a 15-minute step: the buffer stays inside its range and the pipeline
        // near its set point; the buyer gets what the plan produces
        assertTrue(k.get("p_max_bar") < 200);
        assertTrue(k.get("buffer_min_bar") > 50);
        assertTrue(k.get("line_min_bar") > 20 && k.get("line_max_bar") < 40, k.toString());
        assertEquals(0, k.get("h2_shortfall_kg"), 1e-6);
        assertTrue(k.get("compressor_mwh") > 0);

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

    record Result(int code, String out, String err) {
    }

    /** {@code bin/opentsx twin|house|boat --history ...}: the arguments as bin/opentsx passes them to HistoryTwins. */
    static Result twins(String... args) throws ReflectiveOperationException {
        java.lang.reflect.Method run = Class.forName("org.opentsx.market.twin.HistoryTwins")
                .getDeclaredMethod("run", List.class, Map.class, PrintStream.class, PrintStream.class);
        run.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = (int) run.invoke(null, List.of(args), Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void commandLineRunsOnTheFixtureArchiveAndExplainsMissingData() throws Exception {
        String dir = archiveDir().toString();
        Result twin = twins("twin", "--history", dir, "--year", "2024", "--from", "2024-03-30", "--days", "2");
        assertEquals(0, twin.code(), twin.toString());
        assertTrue(twin.out().contains("power_cost_eur_per_kg"));
        Result boat = twins("boat", "--history", dir, "--weather-year", "2024", "--days", "2");
        assertEquals(0, boat.code(), boat.toString());
        assertTrue(boat.out().contains("measured irradiance"));
        assertEquals(new Result(2, "", "opentsx: smard/day_ahead_price: no archived data in 2019; run: bin/opentsx market "
                + "backfill --archive " + dir + " --series smard/day_ahead_price\n"), twins("twin", "--history", dir, "--year", "2019"));
    }

    @Test
    void aMisspelledOptionOfAHistoryTwinExitsWith2() throws Exception {
        assertEquals(new Result(2, "", "opentsx: --yaer: unknown option of history-twin twin; did you mean --year?\n"),
                twins("twin", "--history", archiveDir().toString(), "--yaer", "2024", "--from", "2024-03-30", "--days", "2"));
    }

    @Test
    void anUnknownWindInputExitsWith2AndListsTheInputs() throws Exception {
        assertEquals(new Result(2, "", "opentsx: --wind: \"dwdd\" is not allowed; expected one of smard, dwd\n"),
                twins("twin", "--history", archiveDir().toString(), "--year", "2024", "--from", "2024-03-30", "--days",
                        "2", "--wind", "dwdd"));
        H2History noArchive = new H2History(new HistoryInputs(null), HistorySites.get(null, HistorySites.PARK), 0.3);
        assertEquals("wind: \"dwdd\" is not allowed; expected smard or dwd", assertThrows(IllegalArgumentException.class,
                () -> noArchive.inputs(H2History.plan(Instant.parse("2024-03-30T00:00:00Z"), 96), "dwdd", 1.0))
                .getMessage());
    }

    @Test
    void leversFalsePrintsNoWhatIfAndAMalformedValueNamesItsOption() throws Exception {
        String dir = archiveDir().toString();
        Result house = twins("house", "--history", dir, "--weather-year", "2024", "--levers", "false");
        assertEquals(0, house.code(), house.toString());
        assertFalse(house.out().contains("What-if"), house.out());
        assertEquals(new Result(2, "", "opentsx: --weather-year: \"2025-2022\" is not a year or a range of years; expected "
                + "YYYY or YYYY-YYYY such as 2022-2025\n"), twins("house", "--history", dir, "--weather-year", "2025-2022"));
        assertEquals(new Result(2, "", "opentsx: --start: \"2024-07-32\" is not a day; expected a UTC day YYYY-MM-DD\n"),
                twins("boat", "--history", dir, "--weather-year", "2024", "--start", "2024-07-32"));
        assertEquals(new Result(2, "", "opentsx: --days: \"0\" is not allowed; expected 1 to 366\n"),
                twins("boat", "--history", dir, "--weather-year", "2024", "--days", "0"));
    }

    @Test
    void moreDaysThanARunOfTheTwinOrTheBoatHoldsAreRefusedWithTheOption() throws Exception {
        String dir = archiveDir().toString();
        for (String days : List.of("367", "22369622")) {
            assertEquals(new Result(2, "", "opentsx: --days: \"" + days + "\" is not allowed; expected 1 to 366\n"),
                    twins("twin", "--history", dir, "--year", "2024", "--from", "2024-03-30", "--days", days));
            assertEquals(new Result(2, "", "opentsx: --days: \"" + days + "\" is not allowed; expected 1 to 366\n"),
                    twins("boat", "--history", dir, "--weather-year", "2024", "--days", days));
        }
    }

    @Test
    void theHelpOfAHistoryTwinComesFromTheRegistry() throws Exception {
        assertEquals(new Result(0, org.opentsx.conf.Registry.shipped().help("history-twin twin"), ""),
                twins("twin", "--history", archiveDir().toString(), "--help"));
    }
}
