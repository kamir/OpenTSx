package org.opentsx.twin.boat;

import org.opentsx.twin.Inputs;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.Mode;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calibrates usage and derating parameters of a twin against a measured series by least squares.
 * <p>
 * The parameters are controls ("knobs"), set by interventions at the start of the run, so the wiring plan stays
 * unchanged and the fitted values appear in the run id like any other decision. The fit is a bounded
 * Levenberg-Marquardt least-squares search: the Jacobian comes from forward differences (one twin run per knob),
 * steps are clamped to the bounds. No randomness, so the same data give the same fit. Knobs the data cannot see
 * (e.g. the usage of a device whose power is measured) keep their start value.
 * <p>
 * The objective is the sum of squared residuals {@code (model − measured) / sigma} over one or several targets
 * (e.g. the station SoC, the residual against the measured output power, the solar power), over the steps where
 * the measurement is present.
 */
public final class Calibration {

    /** A calibrated quantity that sets one or several controls {@code "atom.control"} to the same value. */
    public record Knob(String name, List<String> controls, double lo, double hi, double start) {
        public Knob {
            if (!(lo < hi) || start < lo || start > hi || controls.isEmpty()) {
                throw new IllegalArgumentException("knob " + name + " needs lo < hi, lo <= start <= hi and controls");
            }
            controls = List.copyOf(controls);
        }
    }

    /**
     * A measured series to match: {@code seriesKey} of the run against {@code measured} (one value per plan step, NaN
     * = not measured); residuals are divided by {@code sigma} (the expected error), so targets in different units
     * can be combined.
     */
    public record Target(String seriesKey, double[] measured, double sigma) {
        public Target {
            if (!(sigma > 0)) {
                throw new IllegalArgumentException("sigma must be positive");
            }
        }
    }

    /**
     * Result: fitted values; overall error in units of sigma before (at the start values) and after; RMSE per target
     * (in the target's unit) before and after; number of runs; the interventions that set the fitted values.
     */
    public record Fit(Map<String, Double> values, double rmseStart, double rmse, int evaluations,
                      List<Intervention> interventions, Map<String, Double> rmseStartByTarget,
                      Map<String, Double> rmseByTarget) {
    }

    private static final int MAX_EVALUATIONS = 400;

    private Calibration() {
    }

    /** Interventions at the plan start that set every knob's controls. */
    public static List<Intervention> interventions(TwinPlan plan, List<Knob> knobs, double[] x) {
        List<Intervention> out = new ArrayList<>();
        for (int i = 0; i < knobs.size(); i++) {
            for (String c : knobs.get(i).controls()) {
                int dot = c.indexOf('.');
                out.add(new Intervention(plan.startMicros(), c.substring(0, dot), c.substring(dot + 1), x[i],
                        "calibration", "fitted " + knobs.get(i).name()));
            }
        }
        return out;
    }

    /** RMSE over the steps where {@code measured} is not NaN; NaN if there are none. */
    public static double rmse(double[] model, double[] measured) {
        double sse = 0;
        int n = 0;
        for (int i = 0; i < Math.min(model.length, measured.length); i++) {
            if (!Double.isNaN(measured[i]) && !Double.isNaN(model[i])) {
                double d = model[i] - measured[i];
                sse += d * d;
                n++;
            }
        }
        return n == 0 ? Double.NaN : Math.sqrt(sse / n);
    }

    /** Fits the knobs so that {@code seriesKey} of the run matches {@code measured} (one value per plan step). */
    public static Fit fit(TwinPlan plan, Inputs inputs, List<Intervention> base, String seriesKey, double[] measured,
                          List<Knob> knobs) {
        return fit(plan, inputs, base, List.of(new Target(seriesKey, measured, 1)), knobs);
    }

    /**
     * Fits the knobs so that the run matches all targets at once, by bounded Levenberg-Marquardt least squares on the
     * residuals {@code (model − measured) / sigma} with forward-difference Jacobians.
     */
    public static Fit fit(TwinPlan plan, Inputs inputs, List<Intervention> base, List<Target> targets,
                          List<Knob> knobs) {
        TwinRunner runner = new TwinRunner(plan);
        int n = knobs.size();
        int[] evals = {0};
        int[][] idxs = new int[targets.size()][];
        int total = 0;
        for (int t = 0; t < targets.size(); t++) {
            double[] m = targets.get(t).measured();
            idxs[t] = java.util.stream.IntStream.range(0, m.length).filter(i -> !Double.isNaN(m[i])).toArray();
            total += idxs[t].length;
        }
        if (total == 0) {
            throw new IllegalArgumentException("no measured values to calibrate against");
        }
        int size = total;
        java.util.function.Function<double[], double[]> residuals = x -> {
            evals[0]++;
            List<Intervention> log = new ArrayList<>(base);
            log.addAll(interventions(plan, knobs, x));
            RunResult r = runner.run(new TwinRunner.RunSpec(Mode.SHADOW, inputs, log, null, 0, Map.of(), null));
            double[] res = new double[size];
            int j = 0;
            for (int t = 0; t < targets.size(); t++) {
                Target tg = targets.get(t);
                double[] model = r.series(tg.seriesKey());
                for (int i : idxs[t]) {
                    double m = i < model.length ? model[i] : Double.NaN;
                    res[j++] = Double.isNaN(m) ? 0 : (m - tg.measured()[i]) / tg.sigma();
                }
            }
            return res;
        };
        double[] lo = new double[n];
        double[] hi = new double[n];
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            lo[i] = knobs.get(i).lo();
            hi[i] = knobs.get(i).hi();
            x[i] = knobs.get(i).start();
        }
        double[] r = residuals.apply(x);
        double[] r0 = r;
        double f = sse(r);
        double f0 = f;
        double lambda = 1e-2;
        boolean converged = false;
        for (int iter = 0; iter < 60 && !converged && evals[0] < MAX_EVALUATIONS; iter++) {
            // Jacobian by forward differences (backwards at the upper bound)
            double[][] jac = new double[n][];
            for (int i = 0; i < n; i++) {
                double h = (hi[i] - lo[i]) * 1e-4;
                double[] y = x.clone();
                y[i] = x[i] + h <= hi[i] ? x[i] + h : x[i] - h;
                double[] ry = residuals.apply(y);
                double d = y[i] - x[i];
                jac[i] = new double[r.length];
                for (int j = 0; j < r.length; j++) {
                    jac[i][j] = (ry[j] - r[j]) / d;
                }
            }
            double[][] jtj = new double[n][n];
            double[] jtr = new double[n];
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    double s = 0;
                    for (int j = 0; j < r.length; j++) {
                        s += jac[a][j] * jac[b][j];
                    }
                    jtj[a][b] = s;
                }
                double s = 0;
                for (int j = 0; j < r.length; j++) {
                    s += jac[a][j] * r[j];
                }
                jtr[a] = s;
            }
            boolean accepted = false;
            while (!accepted && lambda < 1e10 && evals[0] < MAX_EVALUATIONS) {
                double[][] m = new double[n][n];
                for (int a = 0; a < n; a++) {
                    m[a] = jtj[a].clone();
                    m[a][a] += lambda * Math.max(jtj[a][a], 1e-12);
                }
                double[] delta = solve(m, negate(jtr));
                double[] y = new double[n];
                boolean moved = false;
                for (int i = 0; i < n; i++) {
                    y[i] = Math.max(lo[i], Math.min(hi[i], x[i] + delta[i]));
                    moved |= Math.abs(y[i] - x[i]) > (hi[i] - lo[i]) * 1e-9;
                }
                if (!moved) {
                    break;
                }
                double[] ry = residuals.apply(y);
                double fy = sse(ry);
                if (fy < f) {
                    converged = f - fy < 1e-9 * f;
                    x = y;
                    r = ry;
                    f = fy;
                    lambda = Math.max(1e-7, lambda / 3);
                    accepted = true;
                } else {
                    lambda *= 4;
                }
            }
            if (!accepted) {
                break;
            }
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            values.put(knobs.get(i).name(), x[i]);
        }
        return new Fit(values, Math.sqrt(f0 / size), Math.sqrt(f / size), evals[0], interventions(plan, knobs, x),
                byTarget(targets, idxs, r0), byTarget(targets, idxs, r));
    }

    private static Map<String, Double> byTarget(List<Target> targets, int[][] idxs, double[] r) {
        Map<String, Double> out = new LinkedHashMap<>();
        int j = 0;
        for (int t = 0; t < targets.size(); t++) {
            double s = 0;
            for (int k = 0; k < idxs[t].length; k++, j++) {
                s += r[j] * r[j];
            }
            out.put(targets.get(t).seriesKey(), idxs[t].length == 0 ? Double.NaN
                    : targets.get(t).sigma() * Math.sqrt(s / idxs[t].length));
        }
        return out;
    }

    private static double sse(double[] r) {
        double s = 0;
        for (double v : r) {
            s += v * v;
        }
        return s;
    }

    private static double[] negate(double[] v) {
        double[] o = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            o[i] = -v[i];
        }
        return o;
    }

    /** Gaussian elimination with partial pivoting (small systems). */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[p][c])) {
                    p = r;
                }
            }
            double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            double tb = b[c];
            b[c] = b[p];
            b[p] = tb;
            if (Math.abs(a[c][c]) < 1e-300) {
                continue;
            }
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k < n; k++) {
                    a[r][k] -= f * a[c][k];
                }
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int c = n - 1; c >= 0; c--) {
            double s = b[c];
            for (int k = c + 1; k < n; k++) {
                s -= a[c][k] * x[k];
            }
            x[c] = Math.abs(a[c][c]) < 1e-300 ? 0 : s / a[c][c];
        }
        return x;
    }
}
