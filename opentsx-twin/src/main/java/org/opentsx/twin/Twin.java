package org.opentsx.twin;

import java.util.List;
import java.util.Map;

/**
 * The three operating modes over one wiring plan. The atoms are the same in every mode; only the binding of inputs,
 * the intervention log and the policies differ.
 */
public final class Twin {

    private final TwinRunner runner;

    public Twin(TwinPlan plan) {
        this(new TwinRunner(plan));
    }

    public Twin(TwinRunner runner) {
        this.runner = runner;
    }

    /** Shadow: observations (and the real decision log) drive the twin over the plan's horizon. */
    public RunResult shadow(Inputs observed, List<Intervention> log) {
        return shadow(observed, log, MessageSink.NONE);
    }

    public RunResult shadow(Inputs observed, List<Intervention> log, MessageSink sink) {
        return runner.run(new TwinRunner.RunSpec(Mode.SHADOW, observed, log, null, 0, Map.of(), sink));
    }

    /** What-if: the same history with the baseline log and with an alternative log. */
    public WhatIf whatIf(Inputs history, List<Intervention> baseline, List<Intervention> alternative) {
        RunResult b = runner.run(new TwinRunner.RunSpec(Mode.WHAT_IF, history, baseline, null, 0, Map.of(), null));
        RunResult a = runner.run(new TwinRunner.RunSpec(Mode.WHAT_IF, history, alternative, null, 0, Map.of(), null));
        return new WhatIf(b, a);
    }

    /**
     * Forecast: continue from a snapshot (usually the end of a shadow run) with forecast inputs, planned
     * interventions and optionally replaced policies, e.g. {@code Map.of("ely", "pressure-aware")}.
     */
    public RunResult forecast(Snapshot from, Inputs forecast, int horizonSteps, Map<String, String> policies,
                              List<Intervention> planned) {
        return runner.run(new TwinRunner.RunSpec(Mode.FORECAST, forecast, planned, from, horizonSteps, policies, null));
    }

    /** Baseline and alternative of a what-if comparison. */
    public record WhatIf(RunResult baseline, RunResult alternative) {

        /** KPI differences, alternative minus baseline. */
        public Map<String, Double> kpiDelta() {
            Map<String, Double> b = baseline.kpis();
            Map<String, Double> out = new java.util.LinkedHashMap<>();
            alternative.kpis().forEach((k, v) -> out.put(k, v - b.getOrDefault(k, Double.NaN)));
            return out;
        }
    }
}
