package org.apache.hadoopts.data;


public class RNGWrapper {



    public static void init() {
        stdlib.StdRandom.initRandomGen(1);
    }

    public static double getStdRandomUniform(double d, double d0) {
        return stdlib.StdRandom.uniform(d, d0);
    }

    public static double getStdRandomGaussian() {
        return stdlib.StdRandom.gaussian();
    }

    public static double getStdRandomGaussian( double mw, double std ) {
        return stdlib.StdRandom.gaussian(mw,std);
    }

    public static double getStdRandomExp( double mw ) {
        return stdlib.StdRandom.exp(mw);
    }

    public static double getStdRandomPareto( double mw ) {
        return stdlib.StdRandom.pareto(mw);
    }

    public static double getStdRandomGeometric( double mw ) {
        return stdlib.StdRandom.geometric(mw);
    }

    public static boolean getStdRandomBernoulli() {
        return stdlib.StdRandom.bernoulli();
    }

    public static double getStdRandomCauchy() { return stdlib.StdRandom.cauchy(); }

    public static double getStdPoisson(double v) { return stdlib.StdRandom.poisson(1.0); }



}

