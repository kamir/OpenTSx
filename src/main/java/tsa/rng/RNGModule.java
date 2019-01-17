package tsa.rng;

/**
 * Created by kamir on 25.05.17.
 */
abstract public class RNGModule {

    float[] hostData = null;

    public float[] getRandomSeries() {
        return hostData;
    }

    public void init(int n) {
        // Allocate n floats on host
        hostData = new float[n];
    };

    public abstract float[] createRandomSeries(int n, int seed);

}
