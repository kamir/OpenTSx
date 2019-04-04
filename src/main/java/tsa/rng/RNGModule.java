package tsa.rng;

/**
 * Created by kamir on 25.05.17.
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
