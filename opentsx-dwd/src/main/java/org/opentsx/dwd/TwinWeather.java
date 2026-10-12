package org.opentsx.dwd;

import org.opentsx.model.Micros;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds DWD series into a twin: resamples a {@link WeatherSeries} onto the plan grid and binds it to an input port
 * with the series' origin (MEASURED for CDC observations, FORECAST for MOSMIX).
 * <ul>
 *   <li>house, atom {@code weather}: air temperature → {@code t_out_meas_c}, global irradiance →
 *   {@code ghi_meas_w_m2}</li>
 *   <li>boat, atom {@code solar}: global irradiance → {@code ghi_wm2}</li>
 * </ul>
 * Values inside a plan step are averaged. A step without values holds the last value for up to one source interval
 * (an hourly forecast feeds four 15-minute steps); otherwise it stays missing and the atom falls back to its model
 * (origin SYNTHETIC).
 */
public final class TwinWeather {

    private TwinWeather() {
    }

    /** Port bindings of the house plan's {@code weather} atom. */
    public static Map<Variable, String> housePorts(String weatherAtom) {
        Map<Variable, String> m = new LinkedHashMap<>();
        m.put(Variable.AIR_TEMPERATURE, weatherAtom + ".t_out_meas_c");
        m.put(Variable.GLOBAL_IRRADIANCE, weatherAtom + ".ghi_meas_w_m2");
        return m;
    }

    /** Port binding of the boat plan's {@code solar} atom. */
    public static Map<Variable, String> boatSolarPorts(String solarAtom) {
        return Map.of(Variable.GLOBAL_IRRADIANCE, solarAtom + ".ghi_wm2");
    }

    /** Binds the first series of each mapped variable onto the grid of {@code plan}. */
    public static SeriesInputs bind(TwinPlan plan, List<WeatherSeries> series, Map<Variable, String> ports) {
        return bind(plan.start(), plan.baseStep(), plan.steps(), series, ports);
    }

    public static SeriesInputs bind(Instant start, Duration step, int steps, List<WeatherSeries> series,
                                    Map<Variable, String> ports) {
        SeriesInputs in = new SeriesInputs();
        long t0 = Micros.of(start);
        long dt = step.toNanos() / 1_000L;
        for (Map.Entry<Variable, String> p : ports.entrySet()) {
            series.stream().filter(s -> s.variable() == p.getKey()).findFirst().ifPresent(s ->
                    in.bind(p.getValue(), t0, dt, resample(s, t0, dt, steps), s.origin()));
        }
        return in;
    }

    /** Mean per step {@code [t0 + k·dt, t0 + (k+1)·dt)}; hold for up to one source interval; else NaN. */
    public static double[] resample(WeatherSeries s, long t0, long dt, int steps) {
        double[] out = new double[steps];
        long[] ts = s.timestamps();
        double[] v = s.values();
        int i = 0;
        int lastValid = -1;
        for (int k = 0; k < steps; k++) {
            long a = t0 + k * dt;
            long b = a + dt;
            while (i < ts.length && ts[i] < a) {
                if (!Double.isNaN(v[i])) {
                    lastValid = i;
                }
                i++;
            }
            double sum = 0;
            int n = 0;
            int j = i;
            while (j < ts.length && ts[j] < b) {
                if (!Double.isNaN(v[j])) {
                    sum += v[j];
                    n++;
                }
                j++;
            }
            if (n > 0) {
                out[k] = sum / n;
            } else if (lastValid >= 0 && a - ts[lastValid] < s.intervalMicros()) {
                out[k] = v[lastValid];
            } else {
                out[k] = Double.NaN;
            }
        }
        return out;
    }
}
