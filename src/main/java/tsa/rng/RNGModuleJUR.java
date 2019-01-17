package tsa.rng;

/**
 * This RNGModule provides random numbers created by the built in RNG implemented in class Random in package java.util.
 *
 * Created by kamir on 25.05.17.
 */
public class RNGModuleJUR extends RNGModule {

    java.util.Random rng = new java.util.Random();

    public void init( int n ) {

        super.init( n );
        rng = new java.util.Random();

    }

        @Override
    public float[] createRandomSeries(int n, int seed) {

        rng.setSeed( seed );

        int z = hostData.length;

        int i = 0;
        while ( i < z ) {
            hostData[i] = rng.nextFloat();
            i++;
        }

        return hostData;
    }
}
