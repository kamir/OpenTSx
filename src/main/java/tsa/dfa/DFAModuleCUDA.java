package tsa.dfa;

/**
 * Created by kamir on 01.08.17.
 */
abstract class DFAModuleCUDA extends DFAModule {

    @Override
    public float[] calcDFA(int n, int seed) {
        return new float[0];
    }

    @Override
    public double[][] getF() {
        return new double[0][];
    }

    @Override
    public String getMD() {
        return null;
    }

}
