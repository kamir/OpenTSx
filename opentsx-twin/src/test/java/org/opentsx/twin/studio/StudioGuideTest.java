package org.opentsx.twin.studio;

import org.junit.jupiter.api.Test;
import org.opentsx.twin.TwinPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A guide must explain its plan completely and nothing else. */
class StudioGuideTest {

    @Test
    void everyGuideMatchesItsPlan() {
        for (StudioScenario s : Scenarios.all()) {
            if (!s.guide().isEmpty()) {
                assertEquals(List.of(), s.guide().problems(s.plan()), s.id());
            }
        }
    }

    @Test
    void houseGuideExplainsEveryWire() {
        StudioScenario house = new HouseStudioScenario();
        assertFalse(house.guide().isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> wires = (Map<String, Object>) house.guide().content().get("wires");
        assertEquals(house.plan().wiring().size(), wires.size());
    }

    @Test
    void reportsWhatIsMissingAndWhatIsUnknown() {
        StudioScenario house = new HouseStudioScenario();
        TwinPlan plan = house.plan();
        Map<String, Object> content = new LinkedHashMap<>(house.guide().content());
        @SuppressWarnings("unchecked")
        Map<String, Object> wires = new LinkedHashMap<>((Map<String, Object>) content.get("wires"));
        wires.remove("boiler.fuel_lph -> tank.fuel_lph");
        wires.put("tank.level_l -> stove.season", "no such wire");
        content.put("wires", wires);
        List<String> problems = new StudioGuide(content).problems(plan);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.contains("wire boiler.fuel_lph -> tank.fuel_lph is not explained"));
        assertTrue(problems.contains("guide explains unknown wire tank.level_l -> stove.season"));
    }

    @Test
    void headlineAndLimitsPointAtExistingKeys() {
        for (StudioScenario s : Scenarios.all()) {
            Map<String, Object> m = StudioExport.export(s);
            List<String> kpis = s.kpis().stream().map(StudioScenario.Kpi::key).toList();
            assertTrue(kpis.containsAll(s.headline()), s.id() + " headline " + s.headline());
            Map<?, ?> series = (Map<?, ?>) ((Map<?, ?>) m.get("reference")).get("series");
            assertTrue(series.keySet().containsAll(s.limits().keySet()), s.id() + " limits " + s.limits().keySet());
        }
    }

    @Test
    void exportCarriesGuideAndPlan() {
        Map<String, Object> m = StudioExport.export(new HouseStudioScenario());
        assertTrue(m.get("guide") instanceof Map<?, ?> g && g.containsKey("wires"));
        assertEquals("haus-erzgebirge", ((Map<?, ?>) m.get("plan")).get("twin"));
    }
}
