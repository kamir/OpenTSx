package tsa.rng;

import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.RandomGeneratorFactory;

/**
 * This RNGModule provides random numbers created by the built in RNG.
 *
 * Created by kamir on 25.05.17.
 */
public class RNGModuleACMS extends RNGModule {

    RandomGenerator rg = null;

    public void init( int n ) {

        super.init( n );

        rg = RandomGeneratorFactory.createRandomGenerator( new java.security.SecureRandom() );

    }



    @Override
    public float[] createRandomSeries(int n, int seed) {

        rg.setSeed( seed );

        int z = hostData.length;

        int i = 0;
        while ( i < z ) {
            hostData[i] = rg.nextFloat();
            i++;
        }

        return hostData;

    }
}
