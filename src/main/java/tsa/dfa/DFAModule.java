package tsa.dfa;

import tsa.rng.RNGModule;
import tsa.rng.RNGModuleJUR;

/**
 * Created by kamir on 25.05.17.
 */
abstract public class DFAModule {

    float[] hostData = null;

    public float[] getFluctuationFunction() {
        return hostData;
    }

    public void init(int n) {
        // Allocate n floats on host
        hostData = new float[n];
    };

    public abstract float[] calcDFA(int n, int seed);

    public abstract double[][] getF();

    public abstract String getMD();

    RNGModule rng = new RNGModuleJUR();

    public float[] createRandomSeries(int n, int seed) {
        return rng.createRandomSeries(n, seed);
    }
}
