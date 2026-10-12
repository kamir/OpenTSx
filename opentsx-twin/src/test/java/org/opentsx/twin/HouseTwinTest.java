package org.opentsx.twin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Micros;
import org.opentsx.twin.demo.HouseDemo;
import org.opentsx.twin.house.HouseKpis;
import org.opentsx.twin.house.HouseScenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** House twin (P9.7): plan validation, conservation, determinism, the -1 K what-if, the empty-tank chain. */
class HouseTwinTest {

    private static TwinPlan plan;
    private static RunResult year;

    @BeforeAll
    static void runReferenceYear() {
        plan = HouseDemo.plan();
        year = new TwinRunner(plan).run(new TwinRunner.RunSpec(Mode.SHADOW, HouseDemo.observed(),
                HouseDemo.reference(), null, 0, null, null));
    }

    @Test
    void validatesThePlan() {
        assertEquals("haus-erzgebirge", plan.twin());
        assertEquals(8760, plan.steps());
        assertTrue(AtomCatalog.standard().types().containsAll(plan.atoms().stream().map(TwinPlan.AtomSpec::type).toList()));
        // oil flow (l/h) into a heat input (kW) is rejected, as is a heat flow into a temperature signal
        List<String> bad = new ArrayList<>(plan.wiring());
        bad.set(bad.indexOf("boiler.q_space_kw -> building.q_heater_kw"), "boiler.fuel_lph -> building.q_heater_kw");
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(new TwinPlan(plan.twin(), plan.start(),
                plan.baseStep(), 10, plan.atoms(), bad)));
        List<String> bad2 = new ArrayList<>(plan.wiring());
        bad2.add("stove.q_heat_kw -> boiler.q_req_kw");
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(new TwinPlan(plan.twin(), plan.start(),
                plan.baseStep(), 10, plan.atoms(), bad2)));
        // the thermostat request crosses the superstep boundary (FLOWS -> ASSETS)
        List<TwinPlan.Wire> wires = plan.resolve(plan.instantiate(AtomCatalog.standard()));
        assertTrue(wires.stream().filter(w -> w.toPort().equals("q_req_kw")).findFirst().orElseThrow().delayed());
        // malformed inventory
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(HouseScenario.withParams(plan,
                Map.of("appliances.inventory", List.of(Map.of("name", "tv", "w", 100, "weekday", List.of("23-18")))))));
        // an intervention on an unknown control
        Intervention nope = Intervention.set(plan.start(), "building", "nope", 1, "x", "y");
        assertThrows(IllegalArgumentException.class, () -> new TwinRunner(plan).run(
                new TwinRunner.RunSpec(Mode.SHADOW, null, List.of(nope), null, 1, null, null)));
    }

    @Test
    void heatBalanceOfTheBuildingCloses() {
        Map<String, Double> k = year.kpis();
        double in = k.get("building.heater_kwh") + k.get("building.stove_kwh") + k.get("building.gain_kwh")
                + k.get("building.solar_kwh");
        double stored = HouseScenario.param(plan, "building", "capacityKwhPerK", 14)
                * (k.get("building.t_in_c") - HouseScenario.param(plan, "building", "t0C", 20));
        assertEquals(stored, in - k.get("building.loss_kwh"), 1e-6 * in);
        // what the boiler and the stove deliver is what the building receives
        assertEquals(k.get("boiler.space_kwh"), k.get("building.heater_kwh"), 1e-6);
        assertEquals(k.get("stove.heat_kwh"), k.get("building.stove_kwh"), 1e-6);
        // boiler: fuel = heat + losses, losses include the standby loss
        assertTrue(k.get("boiler.fuel_kwh") > k.get("boiler.heat_kwh") + k.get("boiler.standby_kwh"));
        // "unmet" is the part of the thermostat request above the burner's rating, mostly the morning recovery
        // after the night setback (the deadbeat request asks for C·3 K within one hour); it stays a minor share
        assertTrue(k.get("boiler.unmet_kwh") / k.get("boiler.heat_kwh") < 0.3);
        // the thermostat holds the set-point on average (21 C by day, 18 C by night)
        double meanIn = k.get("building.t_in_sum") / k.get("building.hours");
        assertTrue(meanIn > 19.5 && meanIn < 22.5, "mean indoor " + meanIn);
    }

    @Test
    void oilWoodAndElectricityBalancesClose() {
        Map<String, Double> k = year.kpis();
        assertEquals(2000, k.get("tank.delivered_l"), 1e-9);
        assertEquals(k.get("boiler.fuel_l"), k.get("tank.consumed_l"), 1e-6);
        assertEquals(HouseScenario.param(plan, "tank", "level0L", 0) + k.get("tank.delivered_l")
                        - k.get("tank.consumed_l") + k.get("tank.shortfall_l") - k.get("tank.overfill_l"),
                k.get("tank.level_l"), 1e-6);
        assertEquals(1, year.events("delivery").stream().filter(e -> e.atom().equals("tank")).count());
        assertEquals(1, year.events("reorder_warning").size());

        assertEquals(k.get("stove.wood_ster"), k.get("woodstore.burned_ster"), 1e-9);
        assertEquals(4 + 4 - k.get("woodstore.burned_ster"), k.get("woodstore.stock_ster"), 1e-9);

        assertEquals(k.get("appliances.el_kwh") + k.get("boiler.el_kwh"), k.get("meter.import_kwh"), 1e-6);
        double items = k.entrySet().stream().filter(e -> e.getKey().matches("appliances\\.el_.+_kwh"))
                .mapToDouble(Map.Entry::getValue).sum();
        assertEquals(k.get("appliances.el_kwh"), items, 1e-6);

        // plausible magnitudes for the invented house (order of magnitude, not a claim about real houses)
        HouseKpis kpi = HouseKpis.of(plan, year, HouseDemo.HDD_REF);
        assertTrue(kpi.oilLitres() > 1000 && kpi.oilLitres() < 3500, "oil " + kpi.oilLitres());
        assertTrue(kpi.electricityKwh() > 1500 && kpi.electricityKwh() < 4500, "el " + kpi.electricityKwh());
        assertTrue(kpi.hddKd() > 3500 && kpi.hddKd() < 5500, "G20/15 " + kpi.hddKd());
        assertEquals(Origin.SYNTHETIC, kpi.origin());
    }

    @Test
    void isDeterministicAndContinuesExactlyFromASnapshot() {
        TwinPlan week = HouseDemo.winterWeekPlan();
        Twin twin = new Twin(week);
        RunResult a = twin.shadow(HouseDemo.observed(), HouseDemo.reference());
        RunResult b = twin.shadow(HouseDemo.observed(), HouseDemo.reference());
        assertEquals(a.hash(), b.hash());
        assertEquals(168, a.size());
        RunResult f = twin.forecast(a.snapshotAfter(71), HouseDemo.observed(), 168 - 72, Map.of(), List.of());
        assertEquals(a.last("tank.level_l"), f.last("tank.level_l"), 1e-9);
        assertEquals(a.last("building.t_in_c"), f.last("building.t_in_c"), 1e-9);
        assertEquals(a.kpis().get("boiler.fuel_kwh"), f.kpis().get("boiler.fuel_kwh"), 1e-9);
    }

    @Test
    void minusOneKelvinLowersHeatDemand() {
        HouseDemo.Alternative minus1 = HouseDemo.alternatives().get("minus-1K");
        RunResult alt = new TwinRunner(plan).run(new TwinRunner.RunSpec(Mode.WHAT_IF, HouseDemo.observed(),
                minus1.interventions(), null, 0, null, null));
        HouseKpis ref = HouseKpis.of(plan, year, HouseDemo.HDD_REF);
        Map<String, Double> d = HouseKpis.of(plan, alt, HouseDemo.HDD_REF).minus(ref);
        assertTrue(d.get("useful_heat_kwh") < 0, d.toString());
        assertTrue(d.get("oil_l") < 0, d.toString());
        assertEquals(0, d.get("wood_ster"), 1e-9);              // same wood schedule
        assertEquals(-1, d.get("mean_indoor_c"), 0.3);
        // rule of thumb: roughly 5-7 % of space heat per kelvin
        double rel = -d.get("useful_heat_kwh") / ref.usefulHeatKwh();
        assertTrue(rel > 0.03 && rel < 0.10, "relative saving " + rel);
        assertNotEquals(year.runId(), alt.runId());
    }

    @Test
    void emptyTankStopsTheBoiler() {
        TwinPlan week = HouseScenario.withParams(HouseDemo.winterWeekPlan(), Map.of("tank.level0L", 50));
        RunResult r = new Twin(week).shadow(HouseDemo.observed(), List.of());
        long empty = r.firstEvent("tank_empty").orElseThrow().tMicros();
        long out = r.firstEvent("fuel_out").orElseThrow().tMicros();
        assertTrue(empty < out, r.events().toString());   // the boiler sees the level one superstep later
        double[] heat = r.series("boiler.q_space_kw");
        int k = (int) ((out - Micros.of(week.start())) / week.baseStepMicros());
        assertEquals(0, heat[k], 1e-12);
        assertTrue(r.last("building.t_in_c") < 17, "the house cools down without oil");
        assertTrue(r.kpis().get("building.under_h") > 24);
    }

    @Test
    void measuredWeatherIsUsedAndLabelled() {
        TwinPlan week = HouseDemo.winterWeekPlan();
        SeriesInputs meas = new SeriesInputs().constant("weather.t_out_meas_c", -10, Origin.MEASURED);
        RunResult r = new Twin(week).shadow(meas, List.of());
        assertEquals(-10, r.series("weather.t_out_c")[5], 1e-12);
        assertEquals(Origin.MEASURED, r.origin("weather.t_out_c"));
        assertEquals(Origin.SYNTHETIC, r.origin("weather.ghi_w_m2"));
        RunResult synthetic = new Twin(week).shadow(HouseDemo.observed(), List.of());
        assertTrue(r.kpis().get("boiler.fuel_kwh") > synthetic.kpis().get("boiler.fuel_kwh"));
    }
}
