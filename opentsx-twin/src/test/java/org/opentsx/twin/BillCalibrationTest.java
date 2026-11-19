package org.opentsx.twin;

import org.junit.jupiter.api.Test;
import org.opentsx.twin.boat.Calibration;
import org.opentsx.twin.demo.HouseDemo;
import org.opentsx.twin.house.BillCalibration;
import org.opentsx.twin.house.BillCalibration.Bill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Calibration of the house twin against bills: CSV format and identifiability. */
class BillCalibrationTest {

    @Test
    void readsTheTemplateAndGermanSpreadsheetExports() throws IOException {
        List<Bill> template;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("twin/abrechnungen-vorlage.csv")) {
            template = BillCalibration.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        }
        assertEquals(5, template.size());
        assertTrue(template.get(0).hasElectricity());
        assertFalse(template.get(0).hasOil());
        Bill oil = template.get(2);
        assertTrue(oil.oilFromLevels());
        assertEquals(2200 + 1500 - 1650, oil.oilConsumedL(), 1e-9);   // delivery + start - end
        assertEquals(5, oil.woodSter(), 1e-9);
        assertEquals(365, oil.days());

        List<Bill> de = BillCalibration.parse(List.of(
                "zeitraum_von;zeitraum_bis;strom_kwh;heizoel_liter;notiz",
                "01.01.2024;31.12.2024;2.880,5;1.800;\"delivery; no tank level\""));
        assertEquals(2880.5, de.get(0).electricityKwh(), 1e-9);
        assertEquals(1800, de.get(0).oilConsumedL(), 1e-9);         // delivery as proxy
        assertFalse(de.get(0).oilFromLevels());
        assertEquals("delivery; no tank level", de.get(0).note());

        assertThrows(IllegalArgumentException.class, () -> BillCalibration.parse(List.of(
                "zeitraum_von,zeitraum_bis,gas_kwh", "2024-01-01,2024-12-31,1")));
        assertThrows(IllegalArgumentException.class, () -> BillCalibration.parse(List.of(
                "zeitraum_von,zeitraum_bis", "2024-12-31,2024-01-01")));
    }

    @Test
    void readsEnglishColumnsAndTheGermanNamesAsAliases() {
        List<Bill> english = BillCalibration.parse(List.of(
                "period_from,period_to,electricity_kwh,heating_oil_l,tank_start_l,tank_end_l,wood_ster,cost_eur,note",
                "2024-01-01,2024-12-31,2880,1800,520,1390,2,1890,delivery in September"));
        List<Bill> german = BillCalibration.parse(List.of(
                "zeitraum_von,zeitraum_bis,strom_kwh,heizoel_liter,tank_start_liter,tank_ende_liter,holz_ster,kosten_eur,notiz",
                "2024-01-01,2024-12-31,2880,1800,520,1390,2,1890,delivery in September"));
        assertEquals(new Bill(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), 2880, 1800, 520, 1390, 2, 1890,
                "delivery in September"), english.get(0));
        assertEquals(english, german);
        assertEquals(List.of("period_from", "period_to", "electricity_kwh", "heating_oil_l", "tank_start_l",
                "tank_end_l", "wood_ster", "cost_eur", "note"), BillCalibration.COLUMNS);

        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> BillCalibration.parse(
                List.of("period_from,period_to,electricity_kwh,strom_kwh", "2024-01-01,2024-12-31,1,2")));
        assertEquals("strom_kwh: names the column electricity_kwh a second time; expected each column once",
                twice.getMessage());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> BillCalibration.parse(
                List.of("von,bis", "2024-01-01,2024-12-31")));
        assertEquals("missing column period_from; expected [period_from, period_to, electricity_kwh, heating_oil_l, "
                + "tank_start_l, tank_end_l, wood_ster, cost_eur, note]", missing.getMessage());
    }

    @Test
    void billsAndTemperaturesSpanAtMost3653DaysAndARunAtMostMaxSteps() {
        assertEquals(3653, BillCalibration.parse(List.of("period_from,period_to", "2020-01-01,2029-12-31")).get(0).days());
        IllegalArgumentException bills = assertThrows(IllegalArgumentException.class, () -> BillCalibration.parse(
                List.of("period_from,period_to", "2020-01-01,2030-01-01")));
        assertEquals("line 2: period_to 2030-01-01 lies 3653 days or more after the earliest period_from (line 2); "
                + "expected bills that span at most 3653 days (10 years)", bills.getMessage());

        BillCalibration.dailyTemperatures(List.of("date,t_mean_c", "2020-01-01,3.5", "2029-12-31,4.5"), "weather", 5);
        IllegalArgumentException temps = assertThrows(IllegalArgumentException.class, () -> BillCalibration
                .dailyTemperatures(List.of("date,t_mean_c", "2020-01-01,3.5", "2030-01-01,4.5"), "weather", 5));
        assertEquals("line 3: date 2030-01-01 lies 3653 days or more after the earliest date (line 2); expected "
                + "temperatures that span at most 3653 days (10 years)", temps.getMessage());

        TwinPlan plan = HouseDemo.plan();
        TwinPlan seconds = new TwinPlan(plan.twin(), plan.start(), java.time.Duration.ofSeconds(1), plan.steps(),
                plan.atoms(), plan.wiring());
        List<Bill> year = BillCalibration.parse(List.of("period_from,period_to,electricity_kwh",
                "2024-01-01,2024-12-31,3000"));
        IllegalArgumentException steps = assertThrows(IllegalArgumentException.class,
                () -> new BillCalibration(seconds).fit(year));
        assertEquals("period_from 2024-01-01 to period_to 2024-12-31 needs 31622400 plan steps of PT1S; expected at "
                + "most 87673 plan steps, from a longer baseStep or bills that span less time", steps.getMessage());
    }

    /**
     * A run steps from local midnight to local midnight in Europe/Berlin: the end of October 2024 has one hour more,
     * and ten years with one more autumn than spring clock change take 87673 hourly steps.
     */
    @Test
    void theStepLimitCountsTheStepsBetweenLocalMidnights() {
        TwinPlan plan = HouseDemo.plan();
        TwinPlan seconds = new TwinPlan(plan.twin(), plan.start(), java.time.Duration.ofSeconds(1), plan.steps(),
                plan.atoms(), plan.wiring());
        List<Bill> autumn = BillCalibration.parse(List.of("period_from,period_to,electricity_kwh",
                "2024-10-01,2024-12-31,800"));
        IllegalArgumentException steps = assertThrows(IllegalArgumentException.class,
                () -> BillCalibration.checkSteps(autumn, seconds));
        assertEquals("period_from 2024-10-01 to period_to 2024-12-31 needs 7952400 plan steps of PT1S; expected at "
                + "most 87673 plan steps, from a longer baseStep or bills that span less time", steps.getMessage());

        List<Bill> tenYears = BillCalibration.parse(List.of("period_from,period_to,electricity_kwh",
                "2016-10-30,2026-10-30,30000"));
        assertEquals(3653, tenYears.get(0).days());
        assertEquals(tenYears, BillCalibration.checkSteps(tenYears, plan));
        assertEquals(87673, BillCalibration.MAX_STEPS);
    }

    /**
     * Identifiability: bills generated by the model itself with known parameters (monthly electricity readings and
     * tank readings from July to December, wood in the fourth quarter) are fitted from the plan's default parameters; the
     * known values come back, and the fit reports itself as identifiable. Months with different degree days separate
     * the heat-loss coefficient from the (weather-independent) hot water. Half a year keeps the test short.
     */
    @Test
    void recoversKnownParametersFromMonthlySyntheticBills() {
        TwinPlan plan = HouseDemo.plan();
        BillCalibration cal = new BillCalibration(plan);
        List<Bill> template = new ArrayList<>();
        for (int m = 0; m < 6; m++) {
            LocalDate from = LocalDate.of(2025, 7, 1).plusMonths(m);
            template.add(new Bill(from, from.plusMonths(1).minusDays(1), 1, 0, 1, 1, Double.NaN, Double.NaN, ""));
        }
        template.add(new Bill(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, 2.0, Double.NaN, "wood Q4"));
        Map<String, Double> truth = Map.of("building.lossWPerK", 165.0, "dhw.scale", 1.3, "appliances.scale", 0.85);
        List<Bill> bills = cal.synthesize(template, truth);

        BillCalibration.Result r = cal.fit(bills);
        truth.forEach((k, v) -> assertEquals(v, r.parameters().get(k), 0.02 * v, k + " in " + r.parameters()));
        assertTrue(r.identifiable(), "cond " + r.conditionNumber() + " corr " + r.maxCorrelation());
        assertTrue(r.cvRmse().get("electricity_kwh") < 0.01 && r.cvRmse().get("heating_oil_l") < 0.02,
                r.cvRmse().toString());
        assertEquals(12, r.periods().size());
        assertEquals(7, r.runs(), 3);
        assertEquals(165.0, ((Number) r.calibratedPlan().atoms().stream().filter(a -> a.id().equals("building"))
                .findFirst().orElseThrow().params().get("lossWPerK")).doubleValue(), 3.3);
    }

    /** One electricity total and one oil total cannot pin down three parameters, and the fit says so. */
    @Test
    void flagsAnnualTotalsAsNotIdentifiable() {
        TwinPlan plan = HouseDemo.plan();
        List<Bill> bills = List.of(
                new Bill(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 3, 31), 1600, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, ""),
                new Bill(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 3, 31), Double.NaN, 1500, Double.NaN,
                        Double.NaN, 4, Double.NaN, "delivery only"));
        BillCalibration.Result r = new BillCalibration(plan).fit(bills);
        assertFalse(r.identifiable());
        assertEquals(List.of("oil 2025-10-01..2026-03-31: delivery used as consumption (tank levels missing), weighted "
                        + "0.2; merge several years or add gauge readings",
                "2 observed period totals for 3 parameters: not identifiable, the fit is one of many equally good "
                        + "solutions; add monthly meter or tank readings",
                "weather is synthetic; for real bills bind daily mean temperatures of a nearby station (--weather), "
                        + "otherwise H absorbs the weather difference of the years",
                "fixed assumptions (not fitted): boiler efficiency, standby loss, wood energy per Ster, heating oil 10 "
                        + "kWh/l; from period totals only H/eta is identified"), r.warnings());
        assertTrue(r.periods().get(1).proxy());
    }

    /**
     * Monthly electricity and two months of tank readings cannot separate the heat loss from the hot water, and a hot
     * water use below the lower bound of its scale ends the fit at that bound; the fit says both.
     */
    @Test
    void flagsHardlySeparableParametersAndAParameterAtItsBound() {
        TwinPlan plan = HouseDemo.plan();
        BillCalibration cal = new BillCalibration(plan);
        List<Bill> template = new ArrayList<>();
        for (int m = 0; m < 6; m++) {
            LocalDate from = LocalDate.of(2025, 7, 1).plusMonths(m);
            boolean tank = m >= 4;
            template.add(new Bill(from, from.plusMonths(1).minusDays(1), 1, tank ? 0 : Double.NaN,
                    tank ? 1 : Double.NaN, tank ? 1 : Double.NaN, Double.NaN, Double.NaN, ""));
        }
        List<Bill> bills = cal.synthesize(template, Map.of("building.lossWPerK", 165.0, "dhw.scale", 0.2,
                "appliances.scale", 0.85));

        BillCalibration.Result r = cal.fit(bills);
        assertEquals(List.of("parameters hardly separable (condition 2.5e+05, max correlation 0.994); periods with "
                        + "different weather (winter vs. summer readings) help",
                "weather is synthetic; for real bills bind daily mean temperatures of a nearby station (--weather), "
                        + "otherwise H absorbs the weather difference of the years",
                "dhw.scale at its bound 0.3; check the data or the fixed assumptions",
                "fixed assumptions (not fitted): boiler efficiency, standby loss, wood energy per Ster, heating oil 10 "
                        + "kWh/l; from period totals only H/eta is identified"), r.warnings());
    }

    /** The house and the boat calibration solve with one solver, which gives NaN for a singular system. */
    @Test
    void bothCalibrationsSolveWithOneSolverThatGivesNaNForASingularSystem() {
        double[][] singular = {{1, 2}, {2, 4}};
        double[] x = Gauss.solve(singular, new double[]{1, 2});
        assertTrue(Double.isNaN(x[0]) && Double.isNaN(x[1]), Arrays.toString(x));
        assertEquals(List.of(1.0, 2.0, 2.0, 4.0), List.of(singular[0][0], singular[0][1], singular[1][0], singular[1][1]));
        assertArrayEquals(new double[]{1, 1}, Gauss.solve(new double[][]{{2, 1}, {1, 3}}, new double[]{3, 4}), 1e-12);
        for (Class<?> c : List.of(BillCalibration.class, Calibration.class)) {
            assertFalse(Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> m.getName().equals("solve")), c.getName());
        }
    }
}
