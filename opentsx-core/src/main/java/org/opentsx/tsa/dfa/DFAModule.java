package org.opentsx.tsa.dfa;

import org.opentsx.tsa.rng.RNGModule;
import org.opentsx.tsa.rng.RNGModuleACMS;

/**
 * Created by kamir on 25.05.17.
 */
abstract public class DFAModule {

    RNGModule rng = null;

    public DFAModule() {

    }

    float[] hostData = null;

    public float[] getFluctuationFunction() {
        return hostData;
    }

    public void init(int n) {
        rng = new RNGModuleACMS();
        // Allocate n floats on host
        hostData = new float[n];
    };

    public abstract float[] calcDFA(int n, int seed);

    public abstract double[][] getF();

    public abstract String getMD();

    public float[] createRandomSeries(int n, int seed) {
        return rng.createRandomSeries(n, seed);
    }
}
