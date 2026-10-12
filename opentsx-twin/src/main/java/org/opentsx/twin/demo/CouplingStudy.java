package org.opentsx.twin.demo;

import org.opentsx.twin.LooseRunner;
import org.opentsx.twin.LooseRunner.Coupling;
import org.opentsx.twin.Mode;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Research question RQ1 of the twin concept: when does loose coupling (variant L) give the same result as tight
 * coupling (variant T), and what does it buy? For every scenario the reference run is repeated under a grid of
 * staleness bounds and transport latencies; the study reports simulated wall time, stale reads, the deviation of
 * the scenario's compare series and KPIs from variant T, and how many events (trips, alarms) differ.
 *
 * <pre>bin/opentsx twin-coupling [--csv out.csv] [--scenario id]</pre>
 */
public final class CouplingStudy {

    /** One cell of the grid. */
    public record Row(String scenario, String staleness, boolean cycles, int lag, int jitter, long ticks, double speedup,
                      double staleShare, int maxAge, double maxNrmse, String worstKey, double maxKpiRel,
                      String worstKpi, int eventDelta) {
    }

    private CouplingStudy() {
    }

    public static void main(String[] args) throws IOException {
        Path csv = null;
        String only = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--csv" -> csv = Path.of(args[++i]);
                case "--scenario" -> only = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        List<Row> rows = new ArrayList<>();
        for (StudioScenario s : Scenarios.all()) {
            if (only == null || only.equals(s.id())) {
                rows.addAll(study(s));
            }
        }
        print(rows, System.out);
        if (csv != null) {
            Files.writeString(csv, csv(rows), StandardCharsets.UTF_8);
        }
    }

    /** Staleness bounds and latencies of the grid; {@code ASYNC} last. */
    static List<Coupling> grid() {
        List<Coupling> out = new ArrayList<>();
        for (int lag : new int[]{0, 2}) {
            for (int s : new int[]{0, 1, 2, 4, 8, Coupling.ASYNC}) {
                Coupling c = new Coupling(s, lag, Map.of(), 0, 0, Map.of(), Map.of());
                out.add(c);
                if (lag > 0) {
                    out.add(c.withJitter(2, 17));
                }
            }
        }
        // hybrid: feedback cycles in one processor, loose between them
        for (int s : new int[]{0, 2, Coupling.ASYNC}) {
            out.add(new Coupling(s, 2, Map.of(), 0, 0, Map.of(), Map.of()).colocatingCycles());
        }
        return out;
    }

    public static List<Row> study(StudioScenario s) {
        TwinRunner tight = new TwinRunner(s.plan());
        LooseRunner loose = new LooseRunner(tight);
        TwinRunner.RunSpec spec = new TwinRunner.RunSpec(Mode.SHADOW, s.observed(), s.reference(), null, 0, null, null);
        RunResult t = tight.run(spec);
        Map<String, Double> tKpi = kpis(s, t);

        Map<String, Long> bspTicks = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        for (Coupling c : grid()) {
            LooseRunner.LooseRun l = loose.run(spec, c);
            String key = c.defaultLag() + "/" + c.jitter() + "/" + c.colocateCycles();
            if (c.staleness() == 0 && !c.colocateCycles()) {
                bspTicks.put(key, l.stats().ticks());
            }
            double maxN = 0;
            String worst = "-";
            for (String k : s.compareKeys()) {
                double e = nrmse(t.series(k), l.result().series(k));
                if (e > maxN) {
                    maxN = e;
                    worst = k;
                }
            }
            double maxK = 0;
            String worstK = "-";
            Map<String, Double> lKpi = kpis(s, l.result());
            for (Map.Entry<String, Double> e : tKpi.entrySet()) {
                double ref = e.getValue();
                double v = lKpi.getOrDefault(e.getKey(), Double.NaN);
                double rel = Math.abs(v - ref) / Math.max(Math.abs(ref), 1e-9);
                if (Double.isFinite(rel) && rel > maxK) {
                    maxK = rel;
                    worstK = e.getKey();
                }
            }
            long reads = l.stats().reads();
            rows.add(new Row(s.id(), c.isAsync() ? "async" : Integer.toString(c.staleness()), c.colocateCycles(),
                    c.defaultLag(),
                    c.jitter(), l.stats().ticks(), (double) bspTicks.get(c.defaultLag() + "/" + c.jitter() + "/false")
                            / l.stats().ticks(),
                    reads == 0 ? 0 : (double) l.stats().staleReads() / reads, l.stats().maxAge(), maxN, worst, maxK,
                    worstK, eventDelta(t, l.result())));
        }
        return rows;
    }

    private static Map<String, Double> kpis(StudioScenario s, RunResult r) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Double> fin = r.kpis();
        for (StudioScenario.Kpi k : s.kpis()) {
            out.put(k.key(), fin.getOrDefault(k.key(), Double.NaN));
        }
        out.putAll(s.derivedKpis(r));
        return out;
    }

    /** RMS deviation normalised by the range of the reference series (0 if the reference is constant and equal). */
    static double nrmse(double[] ref, double[] x) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < Math.min(ref.length, x.length); i++) {
            if (Double.isNaN(ref[i]) || Double.isNaN(x[i])) {
                continue;
            }
            lo = Math.min(lo, ref[i]);
            hi = Math.max(hi, ref[i]);
            double d = x[i] - ref[i];
            sum += d * d;
            n++;
        }
        if (n == 0) {
            return 0;
        }
        double rms = Math.sqrt(sum / n);
        double range = hi - lo;
        return range > 1e-12 ? rms / range : rms;
    }

    /** Events (by type, excluding interventions) that occur in one run but not the other. */
    static int eventDelta(RunResult a, RunResult b) {
        Map<String, Integer> count = new LinkedHashMap<>();
        a.events().stream().filter(e -> !e.type().equals("intervention"))
                .forEach(e -> count.merge(e.atom() + ":" + e.type(), 1, Integer::sum));
        b.events().stream().filter(e -> !e.type().equals("intervention"))
                .forEach(e -> count.merge(e.atom() + ":" + e.type(), -1, Integer::sum));
        return count.values().stream().mapToInt(Math::abs).sum();
    }

    static void print(List<Row> rows, PrintStream out) {
        out.println("| scenario | staleness | cycles colocated | lag | jitter | ticks | speed-up vs BSP | stale reads | max age "
                + "| max NRMSE (series) | max KPI dev. | events differing |");
        out.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (Row r : rows) {
            out.printf(Locale.ROOT, "| %s | %s | %s | %d | %d | %d | %.2f | %.1f %% | %d | %.4f (%s) | %.2f %% (%s) | %d |%n",
                    r.scenario(), r.staleness(), r.cycles() ? "yes" : "no", r.lag(), r.jitter(), r.ticks(), r.speedup(),
                    100 * r.staleShare(),
                    r.maxAge(), r.maxNrmse(), r.worstKey(), 100 * r.maxKpiRel(), r.worstKpi(), r.eventDelta());
        }
    }

    static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("scenario,staleness,cycles,lag,jitter,ticks,speedup,stale_share,max_age,"
                + "max_nrmse,worst_series,max_kpi_rel,worst_kpi,event_delta\n");
        for (Row r : rows) {
            sb.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%.4f,%.4f,%d,%.6f,%s,%.6f,%s,%d%n", r.scenario(),
                    r.staleness(), r.cycles(), r.lag(), r.jitter(), r.ticks(), r.speedup(), r.staleShare(), r.maxAge(),
                    r.maxNrmse(), r.worstKey(), r.maxKpiRel(), r.worstKpi(), r.eventDelta()));
        }
        return sb.toString();
    }
}
