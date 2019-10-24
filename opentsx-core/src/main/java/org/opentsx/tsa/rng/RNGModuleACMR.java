package org.opentsx.tsa.rng;

import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.RandomGeneratorFactory;

/**
 * This RNGModuleACMR provides random numbers created by the built in RNG.
 */
public class RNGModuleACMR extends RNGModule {

    RandomGenerator rg = null;

    public void init( int n ) {

        super.init( n );

        // the built in RNG from package java.util.Random() is used.
        rg = RandomGeneratorFactory.createRandomGenerator( new java.util.Random() );

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
