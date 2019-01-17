package tsa.rng;

/**
 * Created by kamir on 25.05.17.
 */
abstract public class RNGModuleCUDA extends RNGModule {

    public void init( int n ) {

        super.init( n );

    }

    abstract public float[] createRandomSeries(int n, int seed);

}
