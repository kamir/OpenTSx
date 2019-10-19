package org.opentsx.tsa.rng;

/**
 * This RNGModule is a wrapper around a variety of random number generators provided
 * in Java packages:
 *
 *    java.util.
 *    java.security.
 *    org.apache.commons.math3.random.RandomGenerator;
 *    org.apache.commons.math3.random.RandomGeneratorFactory;
 *
 */
abstract public class RNGModule {

    public float[] hostData = null;

    public float[] getRandomSeries() {
        return hostData;
    }

    /**
     * Defines the size of the time series which have to be created.
     *
     * This array will contain the random numbers.
     *
     * @param n
     */
    public void init(int n) {
        // Allocate n floats on host
        hostData = new float[n];
    };

    public abstract float[] createRandomSeries(int n, int seed);

}
