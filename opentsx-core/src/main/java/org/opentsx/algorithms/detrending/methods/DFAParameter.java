package org.opentsx.algorithms.detrending.methods;


public class DFAParameter {
    
    double q = 1; // plain DFA; q != 1 => MFDFA

    // order of the trend polynomial
    private int gradeOfPolynom = 1;

    // number of values of the series
    private int N = 0;

    // number of window sizes s
    private int zSValues = 10;

    public static double logScaleFactor = 1.1;
    public static int log_start = 10;

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("\norder          =" + gradeOfPolynom+";");
        sb.append("\nnrOfS          =" + zSValues+";");
        sb.append("\nN              =" + N+";");
        sb.append("\nlogScaleFactor =" + logScaleFactor+";");
        return sb.toString();
    };

    public int getGradeOfPolynom() {
        return gradeOfPolynom;
    }

    public void setGradeOfPolynom(int gradeOfPolynom) {
        this.gradeOfPolynom = gradeOfPolynom;
    }

    /**
     * Sets the number of values of the series.
     *
     * @param N
     */
    public void setN(int N) {
        this.N = N;
    }

    public int getN() {
        return N;
    }

    public void setzSValues(int zSValues) {
        this.zSValues = zSValues;
    }
    /**
     * Returns the number of window sizes s for which F(s) is computed.
     **/
    public int getzSValues() {
        return zSValues;
    }

    public void setQ(double d) {
        this.q = d;
    }

}
