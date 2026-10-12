package org.opentsx.windpark.sim;

import org.opentsx.windpark.catalog.Turbine;

import java.util.List;

/** Jensen (Park) wake model with root-sum-square superposition of single wakes. */
final class WakeModel {

    private WakeModel() {
    }

    /**
     * Velocity deficit (0..1) per turbine for flow coming from {@code fromDirectionDeg} (meteorological).
     *
     * @param ct thrust coefficient per turbine (0 for stopped turbines)
     */
    static double[] deficits(List<Turbine> turbines, double fromDirectionDeg, double[] ct, double wakeDecay) {
        double rad = Math.toRadians(fromDirectionDeg);
        double dx = -Math.sin(rad);  // flow direction (towards)
        double dy = -Math.cos(rad);
        int n = turbines.size();
        double[] sumSq = new double[n];
        for (int j = 0; j < n; j++) {
            if (ct[j] <= 0) {
                continue;
            }
            Turbine up = turbines.get(j);
            double d = up.type().rotorDiameterM();
            double a = 1 - Math.sqrt(1 - Math.min(ct[j], 0.99));
            for (int i = 0; i < n; i++) {
                if (i == j) {
                    continue;
                }
                Turbine down = turbines.get(i);
                double rx = down.xM() - up.xM();
                double ry = down.yM() - up.yM();
                double s = rx * dx + ry * dy;        // distance downstream
                if (s <= 0) {
                    continue;
                }
                double lateral = Math.hypot(rx - s * dx, ry - s * dy);
                double wakeRadius = d / 2 + wakeDecay * s;
                if (lateral < wakeRadius) {
                    double deficit = a * Math.pow(d / (d + 2 * wakeDecay * s), 2);
                    sumSq[i] += deficit * deficit;
                }
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.min(0.9, Math.sqrt(sumSq[i]));
        }
        return out;
    }
}
