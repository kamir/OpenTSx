package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentsx.retrieval.system.SystemsTest.SYSTEMS;
import static org.opentsx.retrieval.system.SystemsTest.series;
import static org.opentsx.retrieval.system.SystemsTest.texts;
import static org.opentsx.retrieval.system.SystemsTest.windFarm;

/** What the analyses of the decision h2-dispatch may claim, against inventories in different states (D59). */
class DecisionsTest {

    static JsonNode byId(JsonNode array, String key, String id) {
        for (JsonNode n : array) {
            if (id.equals(n.path(key).asText())) {
                return n;
            }
        }
        throw new AssertionError("no " + id);
    }

    /** A clean series of a signal of h2-chain as a flow writes it. */
    static ObjectNode clean(ObjectNode inv, String id, String signal, String metric, double min, double max, String origin, String madeOf) {
        ObjectNode s = series(inv, id, metric, min, max);
        ((ObjectNode) s.get("tags")).put("system", "h2-chain").put("signal", signal);
        ObjectNode labels = (ObjectNode) s.get("labels");
        labels.putArray("grade").add("clean");
        labels.putArray("origin").add(origin);
        if (madeOf != null) {
            labels.putArray("madeOf").add(madeOf);
        }
        return s;
    }

    static JsonNode dispatch(ObjectNode inv) {
        JsonNode st = SYSTEMS.decisions("h2-chain", inv, List.of());
        assertEquals("opentsx.decision-status.v1", st.path("schema").asText());
        assertEquals(1, st.path("decisions").size());
        return st.path("decisions").get(0);
    }

    @Test
    void whatIsMissingBlocksTheAnalysesThatNeedIt() {
        JsonNode d = dispatch(windFarm());
        assertEquals("h2-dispatch", d.path("id").asText());
        assertTrue(d.path("example").asBoolean());
        assertEquals(2, d.path("levers").size());

        JsonNode avail = byId(d.path("needs"), "signal", "park.p_avail_kw");
        assertFalse(avail.path("ok").asBoolean());
        assertTrue(texts(avail.path("reasons")).get(0).startsWith("park.p_avail_kw has no clean data"));

        // both analyses need the available power and the demand; only one needs the price
        JsonNode hold = byId(d.path("analyses"), "id", "hold");
        assertEquals("blocked", hold.path("status").asText());
        assertFalse(String.join(" ", texts(hold.path("reasons"))).contains("market.price_eur_mwh"));
        JsonNode follow = byId(d.path("analyses"), "id", "price-following");
        assertEquals("blocked", follow.path("status").asText());
        assertTrue(String.join(" ", texts(follow.path("reasons"))).contains("market.price_eur_mwh"));
        // the generated power is optional: that it is missing blocks nothing
        assertFalse(String.join(" ", texts(follow.path("reasons"))).contains("park.p_gen_kw"));

        assertEquals("blocked", byId(d.path("alternatives"), "id", "hold-setpoint").path("status").asText());
    }

    @Test
    void cleanInputsAreAConditionAndNotAProof() {
        ObjectNode inv = windFarm();
        clean(inv, "p1", "park.p_avail_kw", "h2-chain.park.p_avail_kw", 0, 9000, "ESTIMATED", "SYNTHETIC");
        clean(inv, "p2", "offtake.demand_kgph", "h2-chain.offtake.demand_kgph", 20, 80, "REPORTED", null);
        JsonNode d = dispatch(inv);
        assertTrue(byId(d.path("needs"), "signal", "park.p_avail_kw").path("ok").asBoolean());
        assertTrue(byId(d.path("needs"), "signal", "park.p_avail_kw").path("containsSynthetic").asBoolean());

        // the inputs are there, but the estimate was made of simulated wind, and the rule was only checked on a scenario
        JsonNode hold = byId(d.path("analyses"), "id", "hold");
        assertEquals("restricted", hold.path("status").asText());
        String why = String.join(" | ", texts(hold.path("reasons")));
        assertTrue(why.contains("made of generated data"), why);
        assertTrue(why.contains("checked on generated data only"), why);

        // the price is still missing, and that blocks only the analysis that needs it
        assertEquals("blocked", byId(d.path("analyses"), "id", "price-following").path("status").asText());
    }

    @Test
    void anOriginTheDecisionDoesNotAcceptBlocks() {
        ObjectNode inv = windFarm();
        clean(inv, "p1", "park.p_avail_kw", "h2-chain.park.p_avail_kw", 0, 9000, "ESTIMATED", null);
        clean(inv, "p2", "offtake.demand_kgph", "h2-chain.offtake.demand_kgph", 20, 80, "SYNTHETIC", null);
        JsonNode demand = byId(dispatch(inv).path("needs"), "signal", "offtake.demand_kgph");
        assertFalse(demand.path("ok").asBoolean());
        assertEquals("offtake.demand_kgph is synthetic; the decision accepts reported, forecast", texts(demand.path("reasons")).get(0));
    }

    @Test
    void anInputThatLagsBehindTheOthersIsTooOld() {
        ObjectNode inv = windFarm();
        clean(inv, "p1", "park.p_avail_kw", "h2-chain.park.p_avail_kw", 0, 9000, "ESTIMATED", null).put("to", "2026-10-01T05:00:00Z");
        clean(inv, "p2", "offtake.demand_kgph", "h2-chain.offtake.demand_kgph", 20, 80, "REPORTED", null);
        JsonNode st = SYSTEMS.decisions("h2-chain", inv, List.of());
        assertEquals("2026-10-01T06:00:00Z", st.path("asOf").asText());
        JsonNode avail = byId(st.path("decisions").get(0).path("needs"), "signal", "park.p_avail_kw");
        assertEquals(3600, avail.path("found").path("ageSeconds").asLong());
        assertEquals("park.p_avail_kw ends 60 min before the newest data of the system; allowed are 5 min", texts(avail.path("reasons")).get(0));
    }

    @Test
    void valuesOutsideTheCheckedRangeRestrict() {
        ObjectNode inv = windFarm();
        clean(inv, "p1", "park.p_avail_kw", "h2-chain.park.p_avail_kw", 0, 13000, "ESTIMATED", null);
        clean(inv, "p2", "offtake.demand_kgph", "h2-chain.offtake.demand_kgph", 20, 80, "REPORTED", null);
        JsonNode follow = byId(dispatch(inv).path("analyses"), "id", "price-following");
        JsonNode range = byId(follow.path("validity"), "signal", "park.p_avail_kw");
        assertFalse(range.path("inside").asBoolean());
        assertEquals(13000.0, range.path("observedMax").asDouble());
        assertTrue(String.join(" | ", texts(follow.path("reasons"))).contains("outside the range the analysis was checked in"));
    }
}
