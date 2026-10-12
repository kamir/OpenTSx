package org.opentsx.twin;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shadow mode on scenario S1: the causal chain, conservation, provenance, determinism, message contract. */
class TwinShadowTest {

    private final Twin twin = new Twin(S1.plan());

    @Test
    void reproducesTheH2CausalChainInOrder() {
        RunResult r = twin.shadow(S1.observed(), S1.valveClosed());

        // steady state before the valve closes: production equals offtake
        assertEquals(30.0, r.series("line.p_bar")[59], 1e-9);

        long valve = r.firstEvent("intervention").orElseThrow().tMicros();
        long warn = r.firstEvent("time_to_limit_warning").orElseThrow().tMicros();
        long limit = r.firstEvent("pressure_limit").orElseThrow().tMicros();
        long trip = r.firstEvent("protective_shutdown").orElseThrow().tMicros();
        long deviation = r.firstEvent("schedule_deviation").orElseThrow().tMicros();
        assertEquals(S1.at(60), valve);
        assertTrue(valve < warn && warn < limit && limit <= trip && trip <= deviation, r.events().toString());

        // the buffer lasts about an hour and a half; the crossing time is exact inside the step
        assertEquals(S1.analyticLimitMinute(), (limit - S1.T0) / 60e6, 0.05);
        // warning when less than 60 minutes remain
        assertEquals(S1.analyticLimitMinute() - 60, (warn - S1.T0) / 60e6, 1.01);

        // the electrolyzer ramps to zero within 10 minutes (500 kW/min) and stays off
        double[] pEl = r.series("ely.p_el_kw");
        int k = (int) ((trip - S1.T0) / S1.MIN);
        assertEquals(0, pEl[k + 9], 1e-9);
        assertEquals(0, pEl[S1.STEPS - 1], 1e-9);
        assertEquals(1, r.kpis().get("ely.trips"));
        assertEquals(1, r.events("protective_shutdown").size());

        // the marketer has to sell the electrolyzer's power on top of the schedule
        double hoursAfter = (S1.at(S1.STEPS) - trip) / 3600e6;
        double extra = r.kpis().get("marketer.extra_kwh");
        assertTrue(extra > S1.ELY_KW * (hoursAfter - 10 / 60.0) && extra < S1.ELY_KW * hoursAfter, "extra " + extra);
        assertEquals(0, r.kpis().get("marketer.short_kwh"), 1e-9);
    }

    @Test
    void conservesPowerAndHydrogen() {
        RunResult r = twin.shadow(S1.observed(), S1.valveClosed());
        double[] gen = r.series("park.p_gen_kw");
        double[] ely = r.series("ely.p_el_kw");
        double[] bat = r.series("battery.p_bat_kw");
        double[] grid = r.series("grid.p_grid_kw");
        for (int i = 0; i < r.size(); i++) {
            assertEquals(0, gen[i] - ely[i] - bat[i] - grid[i], 1e-9, "power balance at step " + i);
        }
        double[] in = r.series("ely.m_h2_kgph");
        double[] out = r.series("offtake.m_out_kgph");
        double massIn = 0;
        for (int i = 0; i < r.size(); i++) {
            massIn += (in[i] - out[i]) / 60.0;
        }
        double mass0 = 30e5 * 157 / (4124.2 * 288);
        assertEquals(mass0 + massIn, r.last("line.state.mass_kg"), 1e-9);
        assertEquals(r.kpis().get("ely.h2_kg") - r.kpis().get("offtake.delivered_kg"), massIn, 1e-9);
    }

    @Test
    void outputsCarryTheWorstOriginOfTheirInputs() {
        RunResult r = twin.shadow(S1.observed(), S1.valveClosed());
        assertEquals(Origin.MEASURED, r.origin("park.p_gen_kw"));
        assertEquals(Origin.REPORTED, r.origin("ely.p_el_kw"));           // schedule
        assertEquals(Origin.REPORTED, r.origin("marketer.deviation_kw"));
        // feedback wires (line pressure -> electrolyzer) carry model state and do not taint
        assertEquals(Origin.REPORTED, r.origin("line.p_bar"));

        SeriesInputs noDemand = new SeriesInputs()
                .bind("park.p_avail_kw", S1.T0, S1.MIN, S1.constant(S1.STEPS, 8000), Origin.MEASURED);
        RunResult missing = twin.shadow(noDemand, List.of());
        assertEquals(Origin.SYNTHETIC, missing.origin("offtake.m_out_kgph"));   // default demand
    }

    @Test
    void sameInputsGiveABitIdenticalRun() {
        RunResult a = twin.shadow(S1.observed(), S1.valveClosed());
        RunResult b = twin.shadow(S1.observed(), S1.valveClosed());
        assertEquals(a.runId(), b.runId());
        assertEquals(a.hash(), b.hash());
        RunResult c = twin.shadow(S1.observed(), List.of());
        assertNotEquals(a.runId(), c.runId());
        assertNotEquals(a.hash(), c.hash());
    }

    @Test
    void emitsTheTopicContract() {
        MessageLog log = new MessageLog();
        RunResult r = twin.shadow(S1.observed(), S1.valveClosed(), log);
        Set<String> ids = new HashSet<>();
        log.messages().forEach(m -> assertTrue(ids.add(m.id()), "duplicate id " + m.id()));
        List<Barrier> commits = log.barriers().stream().filter(Barrier::isCommit).toList();
        assertEquals(S1.STEPS, commits.size());
        assertEquals(r.hash(), commits.get(commits.size() - 1).stateHash());
        // phases of a superstep arrive in order, and every phase barrier counts its messages
        int k = -1;
        Phase last = null;
        for (PortMessage m : log.messages()) {
            if (m.k() != k) {
                k = m.k();
                last = null;
            }
            assertTrue(last == null || last.compareTo(m.phase()) <= 0);
            last = m.phase();
        }
        int counted = log.barriers().stream().filter(b -> !b.isCommit()).mapToInt(Barrier::count).sum();
        assertEquals(log.messages().size(), counted);
        // the market steps every 15 minutes: no market messages in between
        assertEquals(S1.STEPS / 15, log.messages().stream().filter(m -> m.atom().equals("market")).count());
        double[] price = r.series("market.price_eur_mwh");
        for (int i = 0; i < price.length; i++) {
            assertEquals(price[i - i % 15], price[i], 0.0);
        }
    }

    @Test
    void exportsStateAndOutputSeriesAsEpisodes() {
        RunResult r = twin.shadow(S1.observed(), S1.valveClosed());
        List<Episode> eps = r.toEpisodes();
        assertEquals(r.keys().size(), eps.size());
        eps.forEach(Episodes::validate);
        Episode p = eps.stream().filter(e -> e.getSeries().getMetric().equals("twin.h2-line.p_bar")).findFirst()
                .orElseThrow();
        assertEquals("bar", p.getSeries().getUnit());
        assertEquals(S1.STEPS, p.getCount());
        assertEquals("REPORTED", p.getLabels().get("twin.origin"));
        assertEquals(r.runId(), p.getSeries().getTags().get("run"));
        assertEquals(eps.get(0).getEpisodeId(), r.toEpisodes().get(0).getEpisodeId());
        assertFalse(r.kpis().isEmpty());
    }
}
