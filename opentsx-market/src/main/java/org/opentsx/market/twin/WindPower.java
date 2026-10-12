package org.opentsx.market.twin;

/**
 * Available wind-park power from history, two ways (both {@code ESTIMATED}: a model on top of measured or reported
 * data):
 * <ul>
 *   <li>{@link #fromNational}: the national onshore generation (SMARD, REPORTED) as a capacity-factor profile,
 *       scaled so the period's mean capacity factor equals {@code meanCapacityFactor}; smoother than a single park
 *       (spatial averaging), but with the real weather situations, correlated with the real prices;</li>
 *   <li>{@link #fromStation}: a station's wind speed (DWD, MEASURED), extrapolated to hub height with the power law
 *       {@code v_hub = v · (h_hub / h_anemometer)^α}, through a generic 3 MW-class power curve (cut-in 3 m/s,
 *       rated 12.5 m/s, cut-out 25 m/s, cubic in between) and a park efficiency (wake, availability) of 0.88.</li>
 * </ul>
 */
public final class WindPower {

    public static final double CUT_IN = 3;
    public static final double RATED = 12.5;
    public static final double CUT_OUT = 25;
    public static final double PARK_EFFICIENCY = 0.88;

    private WindPower() {
    }

    public static double[] fromNational(double[] generationMw, double ratedKw, double meanCapacityFactor) {
        double sum = 0;
        int n = 0;
        for (double g : generationMw) {
            if (Double.isFinite(g)) {
                sum += g;
                n++;
            }
        }
        double mean = n == 0 ? Double.NaN : sum / n;
        double[] out = new double[generationMw.length];
        for (int i = 0; i < out.length; i++) {
            double g = generationMw[i];
            out[i] = Double.isFinite(g) && mean > 0
                    ? Math.min(ratedKw, Math.max(0, g / mean * meanCapacityFactor * ratedKw)) : Double.NaN;
        }
        return out;
    }

    public static double[] fromStation(double[] windMs, double ratedKw, double anemometerM, double hubM, double alpha) {
        double f = Math.pow(hubM / anemometerM, alpha);
        double[] out = new double[windMs.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Double.isFinite(windMs[i]) ? ratedKw * PARK_EFFICIENCY * curve(windMs[i] * f) : Double.NaN;
        }
        return out;
    }

    /** Generic power curve, share of rated power at hub-height wind speed {@code v}. */
    public static double curve(double v) {
        if (v < CUT_IN || v >= CUT_OUT) {
            return 0;
        }
        if (v >= RATED) {
            return 1;
        }
        double a = Math.pow(CUT_IN, 3);
        return (Math.pow(v, 3) - a) / (Math.pow(RATED, 3) - a);
    }
}
