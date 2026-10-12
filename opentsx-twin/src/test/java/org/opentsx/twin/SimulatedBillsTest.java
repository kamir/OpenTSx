package org.opentsx.twin;

import org.junit.jupiter.api.Test;
import org.opentsx.twin.house.BillCalibration;
import org.opentsx.twin.house.BillCalibration.Bill;
import org.opentsx.twin.house.HouseScenario;
import org.opentsx.twin.house.SimulatedBills;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled simulated bills: reproducible, marked as simulated, and the calibration finds the hidden house. */
class SimulatedBillsTest {

    @Test
    void bundledFilesAreReproducibleAndMarkedAsSimulated() {
        SimulatedBills.Data d = SimulatedBills.generate();
        assertEquals(SimulatedBills.bundledBills(), d.bills(), "regenerate with SimulatedBills.main");
        assertEquals(SimulatedBills.bundledTemperatures(), d.temperatures());
        assertTrue(d.bills().get(0).contains("SIMULIERT") && d.temperatures().get(0).contains("SIMULIERT"));

        List<Bill> bills = BillCalibration.parse(d.bills());
        assertTrue(bills.stream().allMatch(b -> b.note().startsWith("SIMULIERT")));
        // the imperfections are there: a missing bill, deliveries without tank levels, round litres, wood in 0.5 Ster
        assertTrue(bills.stream().anyMatch(b -> !b.hasElectricity() && !b.hasOil() && Double.isNaN(b.woodSter())));
        assertTrue(bills.stream().anyMatch(b -> b.hasOil() && !b.oilFromLevels()));
        assertTrue(bills.stream().filter(b -> b.oilDeliveredL() > 0).allMatch(b -> b.oilDeliveredL() % 500 == 0));
        assertTrue(bills.stream().filter(b -> !Double.isNaN(b.woodSter())).allMatch(b -> b.woodSter() * 2 % 1 == 0));
        assertTrue(bills.stream().filter(Bill::hasOil).allMatch(b -> b.oilConsumedL() > 0));
    }

    /**
     * Calibration on the simulated data with tank readings (April 2024 to December 2025; the full set is what
     * {@code bin/opentsx house --simulated} uses). The fit cannot know the true boiler efficiency (0.86 vs. the
     * assumed 0.90), so it finds H scaled by eta_assumed / eta_true. The appliance scale comes back directly. Hot
     * water stays undetermined, because the gauge-reading error (±25 l) is larger than its monthly signal, and the
     * fit says so.
     */
    @Test
    void calibrationFindsTheHiddenHouseAndFlagsWhatTheBillsCannotTell() {
        TwinPlan plan = HouseScenario.plan();
        SeriesInputs weather = BillCalibration.dailyTemperatures(SimulatedBills.bundledTemperatures(),
                HouseScenario.id(plan, "weather"), 5.0);
        List<Bill> bills = BillCalibration.parse(SimulatedBills.bundledBills()).stream()
                .filter(b -> !b.from().isBefore(LocalDate.of(2024, 4, 21))).toList();
        assertTrue(bills.stream().filter(Bill::hasOil).allMatch(Bill::oilFromLevels));

        BillCalibration.Result r = new BillCalibration(plan, weather).fit(bills);
        Map<String, Double> truth = SimulatedBills.truth();
        double hOverEta = truth.get("building.lossWPerK") * HouseScenario.param(plan, "boiler", "eta", 0.9)
                / truth.get("boiler.eta");
        assertEquals(hOverEta, r.parameters().get("building.lossWPerK"), 0.06 * hOverEta, r.parameters().toString());
        assertEquals(truth.get("appliances.scale"), r.parameters().get("appliances.scale"), 0.05, r.parameters().toString());
        assertTrue(r.relStdErr().get("building.lossWPerK") < BillCalibration.MAX_REL_STD_ERR);
        assertTrue(r.relStdErr().get("appliances.scale") < BillCalibration.MAX_REL_STD_ERR);

        assertTrue(r.relStdErr().get("dhw.scale") > BillCalibration.MAX_REL_STD_ERR, r.relStdErr().toString());
        assertFalse(r.identifiable());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("poorly determined") && w.contains("dhw.scale")),
                r.warnings().toString());
        assertTrue(r.cvRmse().get("strom_kwh") < 0.05);
    }
}
