package org.opentsx.windpark.sim;

import org.opentsx.windpark.catalog.TurbineType;

/** Idealised power curve: cubic between cut-in and rated wind, flat to cut-out, zero above. */
final class PowerCurve {

    private PowerCurve() {
    }

    static double powerKw(TurbineType t, double windMs) {
        if (windMs < t.cutInMs() || windMs > t.cutOutMs()) {
            return 0;
        }
        if (windMs >= t.ratedWindMs()) {
            return t.ratedPowerKw();
        }
        double ci3 = Math.pow(t.cutInMs(), 3);
        return t.ratedPowerKw() * (Math.pow(windMs, 3) - ci3) / (Math.pow(t.ratedWindMs(), 3) - ci3);
    }

    /** Thrust coefficient, used by the wake model. */
    static double thrustCoefficient(TurbineType t, double windMs, boolean producing) {
        if (!producing) {
            return 0;
        }
        return windMs <= t.ratedWindMs() ? 0.8 : Math.max(0.1, 0.8 * Math.pow(t.ratedWindMs() / windMs, 3));
    }
}
