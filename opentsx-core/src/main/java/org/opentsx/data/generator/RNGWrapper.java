package org.opentsx.data.generator;

import java.util.Random;

public class RNGWrapper {

    public static final long DEFAULT_SEED = 1;

    private static Random random = new Random(DEFAULT_SEED);

    public static void init() {
        init(DEFAULT_SEED);
    }

    public static void init(long seed) {
        random = new Random(seed);
    }

    public static double getStdRandomUniform(double d, double d0) {
        return d + random.nextDouble() * (d0 - d);
    }

    public static double getStdRandomGaussian() {
        return random.nextGaussian();
    }

    public static double getStdRandomGaussian( double mw, double std ) {
        return mw + std * random.nextGaussian();
    }

    public static double getStdRandomExp( double mw ) {
        return random.nextExponential() / mw;
    }

    public static double getStdRandomPareto( double mw ) {
        return Math.pow(1 - random.nextDouble(), -1.0 / mw) - 1.0;
    }

    public static double getStdRandomGeometric( double mw ) {
        return (int) Math.ceil(Math.log(random.nextDouble()) / Math.log(1.0 - mw));
    }

    public static boolean getStdRandomBernoulli() {
        return random.nextBoolean();
    }

    public static double getStdRandomCauchy() { return Math.tan(Math.PI * (random.nextDouble() - 0.5)); }

    public static double getStdPoisson(double v) {
        double limit = Math.exp(-v);
        double product = random.nextDouble();
        int k = 0;
        while (product >= limit) {
            product *= random.nextDouble();
            k++;
        }
        return k;
    }
}
