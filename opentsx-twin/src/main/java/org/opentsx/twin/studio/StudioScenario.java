package org.opentsx.twin.studio;

import org.opentsx.twin.Inputs;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinPlan;

import java.util.List;
import java.util.Map;

/**
 * A modelled system as the Smart Topics Studio shows it: the wiring plan, the observed inputs and decisions of the
 * reference run, named what-if alternatives, forecasts from snapshots, the series worth showing first and an
 * optional {@link StudioGuide} that explains the plan. Every
 * scenario (H2 chain, house, boat, ...) is an ordinary twin; the Studio has no special cases.
 */
public interface StudioScenario {

    String id();

    String title();

    /** One or two sentences: what is modelled and what the reference run shows. */
    String description();

    TwinPlan plan();

    /** Inputs of the reference (shadow) run. */
    Inputs observed();

    /** Decisions of the reference run. */
    List<Intervention> reference();

    /** Named alternatives, compared against the reference with the same inputs unless they replace some. */
    List<Alternative> alternatives();

    /** Forecasts from snapshots of the reference run; may be empty. */
    default List<Forecast> forecasts() {
        return List.of();
    }

    /** Output keys shown first ("smart metrics"), e.g. {@code line.ttl_min}. */
    List<String> smartMetrics();

    /** Up to three output keys charted when comparing runs. */
    List<String> compareKeys();

    /** KPI rows (final state values, {@code atom.var}). */
    List<Kpi> kpis();

    /** Human-readable labels for series keys; missing keys show the key itself. */
    default Map<String, String> labels() {
        return Map.of();
    }

    /** KPIs computed from a run beyond the final states (keys should start with {@code kpi.}). */
    default Map<String, Double> derivedKpis(RunResult run) {
        return Map.of();
    }

    /** Prose for the plan (atoms, ports, parameters, wires, references); empty if the scenario has none. */
    default StudioGuide guide() {
        return StudioGuide.EMPTY;
    }

    /** Event types drawn as markers in charts. */
    default List<String> markerEvents() {
        return List.of("intervention");
    }

    record Alternative(String id, String name, List<Intervention> interventions, Map<String, String> policies,
                       Inputs inputs) {
        public Alternative {
            policies = policies == null ? Map.of() : Map.copyOf(policies);
        }
    }

    /** Forecast from the snapshot after step {@code nowStep - 1}, inputs re-labelled as forecasts. */
    record Forecast(String id, String name, int nowStep, Map<String, String> policies) {
        public Forecast {
            policies = policies == null ? Map.of() : Map.copyOf(policies);
        }
    }

    /** {@code better}: +1 if higher is better, -1 if lower is better, 0 neutral. */
    record Kpi(String key, String label, String unit, int decimals, int better) {
    }
}
