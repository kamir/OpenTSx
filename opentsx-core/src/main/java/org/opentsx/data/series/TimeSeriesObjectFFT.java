/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.data.series;

import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.jfree.data.xy.XYSeries;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;


public class TimeSeriesObjectFFT extends TimeSeriesObject {
    
    public TimeSeriesObjectFFT getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV( ) {

        System.out.println( ">>> phase_randomized_MULTIPLY_random" );

        TimeSeriesObjectFFT aThis = this;
        
        TimeSeriesObjectFFT rFFT = new TimeSeriesObjectFFT();
        rFFT.setLabel( aThis.getLabel() + " (phase_randomized_MULTIPLY_random)");
        
        double[] data = aThis.getYData();
        
        int N_Original = data.length;
        
        data = extendToPowerOf2(data);
        
        FastFourierTransformer fft = new FastFourierTransformer( DftNormalization.STANDARD );

        // TRANSFORMED by FFT
        Complex[] c = fft.transform(data, TransformType.FORWARD );
        
        // MODIFIED => for back transformation  by FFT
        Complex[] mod = new Complex[c.length];

        double N = c.length;

        double c0_R = c[0].getReal();
        double c0_I = c[0].getImaginary();

        double cf_R = c[(data.length - 1)].getReal();
        double cf_I = c[(data.length - 1)].getImaginary();

//        double fmin = 0;
//        double fmax = 0.5D * samplingRate;
//
//        double df = fmax / ( N * 0.5 );
        
        
        double f0 = c0_R;
        double df = N / 6283.1853071795858D;

        int MAX = (c.length / 2);
        System.out.println( ">> LENGTH " + c.length);
        System.out.println( ">>    MAX " + MAX );

        
        // We have to handle symetrie here ...
        
        /**
         * Randomize by multipling complex part with a random number
         */
        for (int i = 1; i <= MAX ; i++) {
            
            double factor = Math.random() * 1.0;
            
//            Complex c1 = new Complex( c[i].getReal(), c[i].getImaginary() * factor);
//            Complex c2 = new Complex( c[(int)N-i].getReal(), c[i].getImaginary() * factor);
//            
            Complex c1 = c[i].multiply( factor );
            Complex c2 = c[(int)N-i].multiply( factor );
            
            mod[i] = c1;
            mod[c.length - i] = c2;
        
//            System.out.print( ">> " + (i+1) );
//            System.out.println( "  >> " + (c.length - 1 - i) + " => " +coeff );
//            
        }

        // NULLPUNKT SETZEN
        mod[0] = new Complex(0.0D, 0.0D);
//        mod[c.length - 1] = new Complex(0.0D, 0.0D);

        
        // transform back to 
        FastFourierTransformer fft2 = new FastFourierTransformer( DftNormalization.STANDARD );
        Complex[] modData = fft2.transform(mod, TransformType.INVERSE );

//                TimeSeriesObject check = new TimeSeriesObject( "COMPLEX part" );
//        
//        // take only the real - value to build the transformed time series.
        for (int i = 0; i < N_Original; i++) {
            rFFT.addValuePair( i, modData[i].getReal() );
//            check.addValuePair( i, modData[i].getImaginary() );
        }
//        
//        Vector<TimeSeriesObject> mrv2 = new Vector<TimeSeriesObject>();
//        mrv2.add( check );
//        mrv2.add( rFFT );
//        mrv2.add( this );
//        
//        MultiChart.open(mrv2, true);
//        
//        System.out.println(" null: " + rFFT );
        
        
        // return the transformed ts build from shuffled phases.
        return rFFT;
    }

    
    public TimeSeriesObjectFFT getPhaseRandomizedModifiedFFT_SHUFFLED_INV( ) {

        System.out.println( ">>> phase_randomized_SHUFFLE" );
        
        TimeSeriesObjectFFT aThis = this;
        
        TimeSeriesObjectFFT rFFT = new TimeSeriesObjectFFT();
        rFFT.setLabel( aThis.getLabel() + " (phase_randomized_SHUFFLE)");
        
        double[] data = aThis.getYData();

        int N_Original = data.length ;
        
        System.out.println( "N_original=" + data.length );
        
        data = extendToPowerOf2(data);

        System.out.println( "N_extended=" + data.length );

        FastFourierTransformer fft = new FastFourierTransformer( DftNormalization.STANDARD );
        Complex[] c = fft.transform(data, TransformType.FORWARD );
        
        // MODIFIED => for back transformation  by FFT
        Complex[] mod = new Complex[c.length];

        double N = c.length;

//        double c0_R = c[0].getReal();
//        double c0_I = c[0].getImaginary();
//
//        double cf_R = c[(data.length - 1)].getReal();
//        double cf_I = c[(data.length - 1)].getImaginary();
//
//        
//        
////        double fmin = 0;
////        double fmax = 0.5D * samplingRate;
////
//////        double df = fmax / ( N * 0.5 );
////        
//        
//        double f0 = c0_R;
//        double df = N / 6283.1853071795858D;

        Vector<Double> ph = new Vector<Double>();
        
        /**
         * Randomize by shuffeling the phases
         */
        for (int i = 1; i <= c.length / 2; i++) {
            ph.add(c[i].getImaginary());
        }
        
        // shuffle the ontainer...
        Collections.shuffle(ph);
        
        System.out.println( "Nr of complex numbers : " + ph.size() );
        System.out.println( "Nr of coefficients    : " + c.length );
        
        // use the shuffeled phases ...
        for (int i = 1; i <= c.length / 2 ; i++) {

            mod[i] = new Complex( c[i].getReal(), ph.elementAt(i-1) ); 
            mod[(int)N-i] = new Complex( c[(int)N-i].getReal(), ph.elementAt(i-1) ) ;
        
        }

        // NULLPUNKT SETZEN => kein konstanter Anteil
        mod[0] = new Complex(0.0D, 0.0D);

        // transform back to 
        FastFourierTransformer fft2 = new FastFourierTransformer( DftNormalization.STANDARD);
        Complex[] modData = fft2.transform(mod, TransformType.INVERSE);

//        TimeSeriesObject check = new TimeSeriesObject( "COMPLEX part" );
        
        // take only the real - value to build the transformed time series.
        for (int i = 0; i < N_Original; i++) {
            rFFT.addValuePair( i, modData[i].getReal() );
//            check.addValuePair( i, modData[i].getImaginary() );
        }
        
//        Vector<TimeSeriesObject> mrv2 = new Vector<TimeSeriesObject>();
//        mrv2.add( check );
//        mrv2.add( rFFT );
//        mrv2.add( this );
//        
//        MultiChart.open(mrv2, true);
//        
//        System.out.println(" null: " + rFFT );
        
        // return the transformed ts build from shuffled phases.
        return rFFT;
    }



    public boolean isNotEmpty() {
        boolean v = true;
        if (this.xValues == null) {
            v = false;
        } else if (this.xValues.size() == 0) {
            v = false;
        }

        return v;
    }

    private static int getShortestLength(TimeSeriesObject[] mrs) {
        boolean isFirst = true;
        int l = 0;
        for (TimeSeriesObject mr : mrs) {
            int l2 = mr.xValues.size();
            if (isFirst) {
                l = l2;
                isFirst = false;
            } else {
                if (l2 >= l) {
                    continue;
                }
                l = l2;
            }
        }
        return l;
    }

    public DecimalFormat getDecimalFormat_STAT() {
        return this.decimalFormat_STAT;
    }

    public DecimalFormat getDecimalFormat_X() {
        return this.decimalFormat_X;
    }

    public DecimalFormat getDecimalFormat_Y() {
        return this.decimalFormat_Y;
    }

    public StringBuffer getStatus() {
        return this.status;
    }

 
    
    public static TimeSeriesObjectFFT getGaussianDistribution(int i) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
        mr.setLabel(i + "=> gaussian distr. ");
        mr.setDecimalFomrmatX("0.00000");
        mr.setDecimalFomrmatY("0.00000");
        for (int j = 0; j < i; j++) {
            mr.addValuePair(1.0D * j, RNGWrapper.getStdRandomGaussian() );
        }
        return mr;
    }

    public int[] getSize() {
        int[] d = new int[2];
        d[0] = this.xValues.size();
        d[1] = this.yValues.size();
        return d;
    }

    public TimeSeriesObjectFFT(String label, String xL, String yL) {
        this(label);
        this.label_Y = yL;
        this.label_X = xL;
    }

    public TimeSeriesObjectFFT(String label) {
        this();
        this.label = label;
    }

    public TimeSeriesObjectFFT() {
        this.xValues = new Vector();
        this.yValues = new Vector();
        this.label = ("unnamed - " + DateFormat.getDateTimeInstance().format(new Date(System.currentTimeMillis())));
        this.status = new StringBuffer();
        this.status.append("[CREATION] \t" + new Date(System.currentTimeMillis()) + "\n");
        this.status.append("[FORMAT]   \tx:" + this.dfx + "\ty:" + this.dfy + "\n");
    }

    public void addValuePair(double x, double y) {
        this.xValues.add(Double.valueOf(x));
        this.yValues.add(Double.valueOf(y));
    }

    public Vector getXValues() {
        return this.xValues;
    }

    public Vector getYValues() {
        return this.yValues;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();

        int size = this.xValues.size();

        sb.append("#\n# M e s s r e i h e \n# Klasse : " + getClass() + " \n# Label  : " + this.label + "\n#\n# Anzahl Wertepaare = " + size + "\n#\n");
        sb.append("#\n#" + this.addinfo + "\n#\n");
        sb.append(getStatisticData("# "));
        sb.append("\n");
        for (int i = 0; i < size; i++) {
            double x = ((Double) getXValues().elementAt(i)).doubleValue();
            double y = ((Double) getYValues().elementAt(i)).doubleValue();

            sb.append(x + " " + y + "\n");
        }
        sb.append("#\n" + this.sbComments.toString());
        return sb.toString();
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setAddinfo(String addinfo) {
        this.addinfo = addinfo;
    }

    public String getAddinfo() {
        return this.addinfo;
    }

    public String getLabel() {
        return this.label;
    }

    public double[][] getData() {
        int z = this.xValues.size();
        double[][] data = new double[2][z];

        int max = this.xValues.size();

        for (int i = 0; i < max; i++) {
            double x = ((Double) getXValues().elementAt(i)).doubleValue();
            double y = ((Double) getYValues().elementAt(i)).doubleValue();
            data[0][i] = x;
            data[1][i] = y;
        }
        return data;
    }

    public void calcAverage() {
        int z = this.xValues.size();
        double sum = 0.0D;

        for (int i = 0; i < z; i++) {
            sum += ((Double) getYValues().elementAt(i)).doubleValue();
        }
        this.av = (sum / z);
    }

    public double getAvarage() {
        return this.av;
    }

    public void setDecimalFomrmatX(String format) {
        this.dfx = format;
        this.decimalFormat_X = new DecimalFormat(format);
    }

    public void setDecimalFomrmatY(String format) {
        this.dfy = format;
        this.decimalFormat_Y = new DecimalFormat(format);
    }

    public double getMaxX() {
        double[][] data = getData();
        double[] dx = data[0];
        return NumberUtils.max(dx);
    }

    public double getMaxY() {
        double[][] data = getData();
        double[] dy = data[1];
        return NumberUtils.max(dy);
    }
    
    public double getMinY() {
        double[][] data = getData();
        double[] dy = data[1];
        return NumberUtils.min(dy);
    }

    public String getStatisticData(String pre) {
        return super.getStatisticData( pre );
    }

    public double[] getYData() {
        double[] yV = new double[getYValues().size()];

        for (int i = 0; i < getYValues().size(); i++) {
            Double y = (Double) getYValues().get(i);
            yV[i] = y.doubleValue();
        }

        return yV;
    }

    public XYSeries getXYSeries() {
        XYSeries series = new XYSeries(getLabel());

        for (int i = 0; i < getXValues().size(); i++) {
            Double x = (Double) getXValues().get(i);
            Double y = (Double) getYValues().get(i);
            series.add(x, y);
        }

        return series;
    }

    public void writeToFile(File f) {
        if (!f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }

        FileWriter fw = null;
        try {
            fw = new FileWriter(f);
            fw.write(toString());
            fw.close();
        } catch (IOException ex) {
            Logger.getLogger(TimeSeriesObject.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            try {
                fw.close();
            } catch (IOException ex) {
                Logger.getLogger(TimeSeriesObject.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    public void addValue(double y) {
        int x = getXValues().size() + 1;
        addValuePair(1.0D * x, y);
    }

    public String getLabel_X() {
        return this.label_X;
    }

    public void setLabel_X(String label_X) {
        this.label_X = label_X;
    }

    public void setLabel_Y(String label_Y) {
        this.label_Y = label_Y;
    }

    public String getLabel_Y() {
        return this.label_Y;
    }

    public double getStddev() {

        StandardDeviation stdev = new StandardDeviation();

        double[][] data = getData();
        double[] dx = data[0];
        double[] dy = data[1];
        double sigma = stdev.evaluate( dy );
        return sigma;
    }

    public void normalize() {
        double[][] data = getData();
        double[] dx = data[0];
        double[] dy = data[1];

        double maxY = NumberUtils.max(dy);

        for (int i = 0; i < this.yValues.size(); i++) {
            double d = ((Double) this.yValues.elementAt(i)).doubleValue();

            this.yValues.set(i, new Double(d / maxY));
        }
        this.label = this.label.concat(" (max=" + maxY + ")");
    }

    public double getYValueForX(int i) {
        int indexX = this.xValues.indexOf(new Double(i));
        Double value = null;
        if (indexX >= 0) {
            value = (Double) this.yValues.elementAt(indexX);
        } else {
            value = new Double(0.0D);
        }
        return value.doubleValue();
    }

    public TimeSeriesObject setBinningX_sum(int bin) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.binning = bin;
        mr.setLabel(getLabel() + "BIN=" + bin + "_");
        Enumeration en = this.yValues.elements();
        int anzahl = this.yValues.size();

        int rest = anzahl % bin;
        int elements = anzahl - rest;
        int blocks = elements / bin;

        double summeA = summeY();

        mr.addStatusInfo("[BINNING] bin=" + bin + "\tRest=" + rest + "\tBlöcke=" + blocks);

        int i = 0;
        int j = 0;
        double v = 0.0D;

        while (i < elements) {
            v += ((Double) en.nextElement()).doubleValue();

            if (i % bin == bin - 1) {
                mr.addValuePair(j, v);
                v = 0.0D;
                j++;
            }
            i++;
        }
        return mr;
    }

    public TimeSeriesObject setBinningX_average(int bin) {
        TimeSeriesObject resultat = new TimeSeriesObject();
        resultat.setLabel(getLabel() + "_AV");

        double summe = 0.0D;

        Enumeration en = this.yValues.elements();
        int c = 0;
        int z = -1;
        while (en.hasMoreElements()) {
            z++;
            c++;
            summe += ((Double) en.nextElement()).doubleValue();

            if (z == bin - 1) {
                double wert = summe / bin;

                resultat.addValuePair(c, wert);
                summe = 0.0D;
                z = -1;
            }

        }

        return resultat;
    }

    public void divide_Y_by(double divBy) {
        Vector y = new Vector();
        Enumeration en = this.yValues.elements();
        while (en.hasMoreElements()) {
            y.add(Double.valueOf(((Double) en.nextElement()).doubleValue() / divBy));
        }
        this.yValues = y;
    }

    public double summeY() {
        double summe = 0.0D;
        Enumeration en = this.yValues.elements();
        while (en.hasMoreElements()) {
            summe += ((Double) en.nextElement()).doubleValue();
        }
        return summe;
    }

    public TimeSeriesObject diff(TimeSeriesObject mr2) {
        System.out.println("Differenz der Anzahl der Werte: " + (this.yValues.size() - mr2.yValues.size()));
        double difff = 0.0D;
        TimeSeriesObject mr = new TimeSeriesObject();

        int max = mr2.xValues.size();
        for (int k = 0; k < max; k++) {
            double v1 = ((Double) this.yValues.elementAt(k)).doubleValue();
            double v2 = ((Double) mr2.yValues.elementAt(k)).doubleValue();
            double delta = v1 - v2;
            difff += Math.abs(delta);
            mr.addValuePair(k, delta);
        }
        return mr;
    }

    public TimeSeriesObject add(TimeSeriesObject mr2) {
        this.anzahlOfAllAddedMessreihen += 1;

        TimeSeriesObject mr = null;

        if (this.yValues.size() == 0) {
            mr = mr2.copy();
        } else {
            mr = new TimeSeriesObject();
            int max = mr2.yValues.size();
            for (int k = 0; k < max; k++) {
                double v1 = 0.0D;
                double v2 = 0.0D;
                try {
                    v1 = ((Double) this.yValues.elementAt(k)).doubleValue();
                } catch (Exception ex) {
                }
                try {
                    v2 = ((Double) mr2.yValues.elementAt(k)).doubleValue();
                } catch (Exception ex) {
                }

                double sum = v1 + v2;
                mr.addValuePair(k, sum);
            }
        }
        return mr;
    }

    public void addValues(TimeSeriesObject mr) {
        Enumeration en = mr.xValues.elements();
        int i = 0;
        while (en.hasMoreElements()) {
            double x = ((Double) en.nextElement()).doubleValue();
            double y = ((Double) mr.yValues.elementAt(i)).doubleValue();
            addValuePair(x, y);
            i++;
        }
    }

    public TimeSeriesObject scaleX(int maxX) {
        double summeOriginal = summeY();
        TimeSeriesObject mr = new TimeSeriesObject();
        double a = this.yValues.size() * 1.0D;
        double b = maxX * 1.0D;
        double fieldsPerBlock = b / a;
        mr.addStatusInfo("[SCALE] \tFields per Block:" + fieldsPerBlock + "\tvalues:" + a + "\tmaxX:" + b + "\n");

        mr.setLabel(getLabel() + "SCALED:x=" + maxX + "");
        Enumeration en = this.yValues.elements();
        int j = 0;
        while (en.hasMoreElements()) {
            double wert = ((Double) en.nextElement()).doubleValue();
            double v = wert / (fieldsPerBlock * 1.0D);
            for (int i = 0; i < fieldsPerBlock; i++) {
                mr.addValuePair(j, v);
                j++;
            }
        }
        mr.addStatusInfo("[SCALE] \tError: " + (mr.summeY() - summeOriginal) + "\n");
        return mr;
    }

    public void addStatusInfo(String string) {
        this.status.append("\n#" + string);
    }

    public String getStatusInfo() {
        return this.status.toString();
    }

    public TimeSeriesObject cut(int nrOfValues) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(getLabel());

        for (int i = 0; i < nrOfValues; i++) {
            mr.addValuePair(((Double) this.xValues.elementAt(i)).doubleValue(), ((Double) this.yValues.elementAt(i)).doubleValue());
        }
        mr.addStatusInfo("[CUT] \tLength old:" + this.xValues.size() + "\tLength new:" + nrOfValues + "\n");

        return mr;
    }

    public TimeSeriesObject shift(int offset) throws Exception {
        if (Math.abs(offset) > this.yValues.size()) {
            throw new Exception("TimeSeriesObject kürzer als SHIFT-Weite!");
        }
        if (offset < 0) {
            return shiftLeft(Math.abs(offset));
        }
        return shiftRight(Math.abs(offset));
    }

    public TimeSeriesObject copy() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(getLabel());
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for (int i = 0; i < max; i++) {
            mr.addValuePair(i, ((Double) this.yValues.elementAt(i)).doubleValue());
        }
        return mr;
    }
    
    /**
     * This may break RESULTS since it is not handling the x-values correct !!!
     * 
     * Use this method only for REAL series not for COMPLEX !!! 
     * 
     * @param m
     * @return 
     */
    public static TimeSeriesObjectFFT convertToMessreiheFFT(TimeSeriesObject m) {

        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
        
        mr.setLabel(m.getLabel());
        mr.binning = m.binning;
        mr.decimalFormat_STAT = m.decimalFormat_STAT;
        mr.decimalFormat_X = m.decimalFormat_X;
        mr.decimalFormat_Y = m.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = m.label_X;
        mr.label_Y = m.label_Y;
        int max = m.yValues.size();

        for (int i = 0; i < max; i++) {
            mr.addValuePair(i, ((Double) m.yValues.elementAt(i)).doubleValue());
        }
        return mr;
    }

    private TimeSeriesObject shiftRight(int nrOfValues) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(getLabel());

        int max = this.yValues.size();

        for (int i = 0; i < nrOfValues; i++) {
            mr.addValuePair(i, 0.0D);
        }
        for (int i = nrOfValues; i < max; i++) {
            mr.addValuePair(i, ((Double) this.yValues.elementAt(i - nrOfValues)).doubleValue());
        }
        mr.addStatusInfo("[SHIFT] \tRIGHT: " + nrOfValues + "\n");

        return mr;
    }

    private TimeSeriesObject shiftLeft(int nrOfValues) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(getLabel());

        int max = this.yValues.size();

        int j = 0;
        for (int i = nrOfValues; i < max; i++) {
            mr.addValuePair(j, ((Double) this.yValues.elementAt(i)).doubleValue());
            j++;
        }
        for (int i = 0; i < nrOfValues; i++) {
            mr.addValuePair(j, 0.0D);
            j++;
        }
        mr.addStatusInfo("[SHIFT] \tLEFT: " + nrOfValues + "\n");

        return mr;
    }

    private void setStatusInfo(String statusInfo) {
        this.status = new StringBuffer();
        this.status.append(statusInfo);
    }

    public TimeSeriesObject scaleX_2(double f) {
        TimeSeriesObject mr = new TimeSeriesObject();

        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for (int i = 0; i < max; i++) {
            mr.addValuePair(((Double) this.xValues.elementAt(i)).doubleValue() * f, ((Double) this.yValues.elementAt(i)).doubleValue());
        }


        return mr;
    }

    /**
     * Multiply y-values by a factor f.
     * 
     * @param f
     * @return 
     */
    public TimeSeriesObject scaleY_2(double f) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel("x" + f);
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for (int i = 0; i < max; i++) {
            mr.addValuePair(((Double) this.xValues.elementAt(i)).doubleValue(), ((Double) this.yValues.elementAt(i)).doubleValue() * f);
        }
        return mr;
    }

    public void setLabels(String label, String xL, String yL) {
        this.label = label;
        this.label_Y = yL;
        this.label_X = xL;
    }

    public static TimeSeriesObject averageForAll(TimeSeriesObject[] mrs) {
        int anz = mrs.length;

        int shortestLength = getShortestLength(mrs);

        TimeSeriesObject mr = new TimeSeriesObject("MW_" + anz);

        for (int i = 0; i < anz; i++) {
            mr = mr.add(mrs[i], shortestLength);
        }
        mr.divide_Y_by(anz);
        return mr;
    }

    public double getX_for_Y(double y) {
        int counter = 0;
        double value = 0.0D;
        Enumeration vy = this.yValues.elements();
        while (vy.hasMoreElements()) {
            double wy = ((Double) vy.nextElement()).doubleValue();
            if (y > wy) {
                value = ((Double) this.xValues.elementAt(counter)).doubleValue();
            }
            counter++;
        }
        return value;
    }

    public SimpleRegression linFit(double x_min, double x_max)
            throws Exception {
        SimpleRegression regression = new SimpleRegression();

        Vector x = new Vector();
        Vector y = new Vector();
        double m = 0.0D;
        int counter = 0;

        double maks = getMaxX();

        if (x_max > maks) {
            throw new Exception(" Reihe zu kurz. xMax=" + maks + ", x_max=" + x_max);
        }

        for (int i = 0; i < this.xValues.size(); i++) {
            Double x1 = (Double) this.xValues.elementAt(i);
            if ((x1.doubleValue() >= x_min) && (x1.doubleValue() <= x_max)) {
                double ax = x1.doubleValue();
                double ay = ((Double) this.yValues.elementAt(counter)).doubleValue();
                regression.addData(ax, ay);
            }

            counter++;
        }

        return regression;
    }

    public TimeSeriesObject[] split(int length, int anzahl) {
        TimeSeriesObject[] rows = new TimeSeriesObject[anzahl];
        Enumeration en = this.yValues.elements();

        int c = 0;
        for (int i = 0; i < anzahl; i++) {
            TimeSeriesObject mr = new TimeSeriesObject(this.label + " (" + i + ")");
            mr.setLabel("" + i);

            double puffer = 0.0D;
            double last = 0.0D;

            for (int j = 0; j < length; j++) {
                try {
                    puffer = ((Double) en.nextElement()).doubleValue();
                    mr.addValue(puffer);
                    last = puffer;
                } catch (NoSuchElementException e) {
                    c++;
                }
            }
            mr.calcAverage();
            if (c > 0) {
                mr.addComment(c + " fehlende Werte mit RQ=" + mr.getAvarage() + " ersetzt.");
                System.out.println(mr.getLabel() + " >>> " + c + " fehlende Werte mit RQ=" + mr.getAvarage() + " ersetzt.");
                for (int d = 0; d < c; d++) {
                    mr.addValue(mr.getAvarage());
                }
            }
            rows[i] = mr;
        }
        return rows;
    }

    public TimeSeriesObject shrinkX(double min, double max) {
        Vector x = new Vector();
        Vector y = new Vector();

        Enumeration en = this.yValues.elements();

        int i = 0;
        while (en.hasMoreElements()) {
            double yv = ((Double) en.nextElement()).doubleValue();
            double xv = ((Double) this.xValues.elementAt(i)).doubleValue();
            i++;

            if ((xv >= min) && (xv <= max)) {
                x.add(Double.valueOf(xv));
                y.add(Double.valueOf(yv));
            }
        }

        TimeSeriesObject mr = new TimeSeriesObject(this.label + " shrinked(" + min + ", " + max + ")");

        for (int j = 0; j < x.size(); j++) {
            mr.addValuePair(((Double) x.elementAt(j)).doubleValue(), ((Double) y.elementAt(j)).doubleValue());
        }
        return mr;
    }

    public void addComment(String string) {
        this.sbComments.append("# " + string + "\n");
        this.comments.add(string);
    }

    public void scaleXto(int i) {
        Enumeration en = this.xValues.elements();
        double max = getMaxX();
        double f = i / max;
        int x = 0;
        while (en.hasMoreElements()) {
            Double v = (Double) en.nextElement();
            v = Double.valueOf(v.doubleValue() * f);
            this.xValues.setElementAt(v, x);
            x++;
        }
    }

    public void checkKonsistenz() {
        System.out.println(this.xValues.size() + " " + this.yValues.size());
    }

    public void show() {
    }

    public TimeSeriesObjectFFT getFFT(double samplingRate ) {
         

        TimeSeriesObject mr = TimeSeriesObjectFFT.calcFFT(this, samplingRate, TransformType.FORWARD);
        mr.setLabel("FFT : " + getLabel());

        return TimeSeriesObjectFFT.convertToMessreiheFFT( mr );
    
    }

//    public void calcFFT(TimeSeriesObjectFFT aThis, TimeSeriesObject rFFT) {
//        
//        double[] data = aThis.getYData();
//        data = extendToPowerOf2(data);
//        
//        
//        FastFourierTransformer fft = new FastFourierTransformer();
//
//        Complex[] c = fft.transform(data);
//
//        double N = c.length;
//
//        double c0_R = c[0].getReal();
//        double c0_I = c[0].getImaginary();
//        System.out.println("c[0]= ( " + c0_R + ", i*" + c0_I + ")");
//
//        double cN1_R = c[1].getReal();
//        double cN1_I = c[1].getImaginary();
//        System.out.println("c[1]= ( " + cN1_R + ", i*" + cN1_I + ")");
//
//        double cN_R = c[(int) (N - 1.0D)].getReal();
//        double cN_I = c[(int) (N - 1.0D)].getImaginary();
//        System.out.println("c[N-1]= ( " + cN_R + ", i*" + cN_I + ")");
//
//        double caNN_R = c[((int) (N / 2.0D) - 1)].getReal();
//        double caNN_I = c[((int) (N / 2.0D) - 1)].getImaginary();
//        System.out.println("c[N/2  -  1]= ( " + caNN_R + ", i*" + caNN_I + ")  ");
//
//        double cNN_R = c[(int) (N / 2.0D)].getReal();
//        double cNN_I = c[(int) (N / 2.0D)].getImaginary();
//        System.out.println("c[N/2]= ( " + cNN_R + ", i*" + cNN_I + ")  [Nyquist-Anteil]");
//
//        double cbNN_R = c[((int) (N / 2.0D) + 1)].getReal();
//        double cbNN_I = c[((int) (N / 2.0D) + 1)].getImaginary();
//        System.out.println("c[N/2  +  1]= ( " + cbNN_R + ", i*" + cbNN_I + ")  ");
//
//        double fmax = c0_R;
//        double fmin = 0.5D;
//
//        double df = N / 6283.1853071795858D;
//
//        System.out.println("> N=" + N + "; \n>f_max=" + fmax + "; \n>f_min=" + fmin + "; \n>df=" + df);
//
//        for (int i = 1; i < (int) N / 2; i++) {
//            double omega = c0_R / 1000.0D * (i * df);
//
//            rFFT.addValuePair(i * df, c[i].getReal());
//        }
//    }
    
    static public void debugComplexSeries( Complex[] c, String label ) {
        
        TimeSeriesObject real = new TimeSeriesObject( "Re" );
        TimeSeriesObject imaginaer = new TimeSeriesObject( "Im" );
        
        // take only the real - value to build the transformed time series.
        for (int i = 0; i < c.length; i++) {
            real.addValuePair( i, c[i].getReal() );
            imaginaer.addValuePair( i, c[i].getImaginary() );
        }
        
        Vector<TimeSeriesObject> mrv2 = new Vector<TimeSeriesObject>();
        mrv2.add( real );
        mrv2.add( imaginaer );
 
        MultiChart.open(mrv2, "COMPLEX data check : " + label , "x", "y", true);

    }

    /**
     * The Transformtype must be provided ...
     * 
     * BE CAREFULL --- COMPLEX PART IS IGNORED. DO NOT CONCATENATE THIS
     * FUNCTION !!!!
     * 
     * 
     * @param aThis
     * @param samplingRate
     * @param tt
     * @return 
     */
    static public TimeSeriesObject calcFFT(TimeSeriesObject aThis, double samplingRate, TransformType tt ) {

        TimeSeriesObject rFFT = new TimeSeriesObject();
        
        FastFourierTransformer fft = new FastFourierTransformer( DftNormalization.STANDARD);
        
        rFFT.setLabel( "FFT("+aThis.getLabel()+")" );

        double[] data2 = aThis.getYData();
        
     
        // REQUIREMENT for the FFT libs ...
        double[] data = extendToPowerOf2(data2);
        
        // the coefficients
        Complex[] c = fft.transform(data, tt );
        
        double N = c.length;
        
        // debugComplexSeries( c , "initial transform" );
 
        double c0_R = c[0].getReal();
        double c0_I = c[0].getImaginary();
        
        System.out.println("> L    = " + data2.length + " (Länge der Zeitreihe)" );
        System.out.println("> L_exp= " + data.length + " (Expandiert)" );
        System.out.println("> N    = " + N + " (Anzahl der Koeffizienten)" );
        
        System.out.println("> SR   = " + samplingRate + " werte/s; // sampling rate" );
        
        System.out.println("> t    = " + ( N/samplingRate) + " s");
        
        System.out.println("> c[0]= ( " + c0_R + ", i*" + c0_I + ")");

        double c1_R = c[1].getReal();
        double c1_I = c[1].getImaginary();
        
        System.out.println("> c[1]= ( " + c1_R + ", i*" + c1_I + ")");

        double caNN_R = c[((int) (N / 2.0D) - 1)].getReal();
        double caNN_I = c[((int) (N / 2.0D) - 1)].getImaginary();
        System.out.println("> c[N/2  -  1]= ( " + caNN_R + ", i*" + caNN_I + ")  ");

        double cNN_R = c[(int) (N / 2.0D)].getReal();
        double cNN_I = c[(int) (N / 2.0D)].getImaginary();
        System.out.println("> c[N/2]= ( " + cNN_R + ", i*" + cNN_I + ")  [Nyquist-Anteil]");

        double cbNN_R = c[((int) (N / 2.0D) + 1)].getReal();
        double cbNN_I = c[((int) (N / 2.0D) + 1)].getImaginary();
        System.out.println("> c[N/2  +  1]= ( " + cbNN_R + ", i*" + cbNN_I + ")  ");

        double cbNNa_R = c[(int) (N - 2.0D)].getReal();
        double cbNNa_I = c[(int) (N - 2.0D)].getImaginary();
        System.out.println("> c[N-1]= ( " + cbNNa_R + ", i*" + cbNNa_I + ")  ");

        double cN_R = c[(int) (N - 1.0D)].getReal();
        double cN_I = c[(int) (N - 1.0D)].getImaginary();
        System.out.println("> c[N]= ( " + cN_R + ", i*" + cN_I + ")");

        
        double fmin = 0;
        double fmax = 0.5D * samplingRate;

        double df = fmax / ( N * 0.5 );
        
        System.out.println("N=" + N + "; f_max=" + fmax + "; f_min=" + fmin + "; df=" + df);

        System.out.println("\n>f_max=" + fmax + "; \n>f_min=" + fmin + "; \n>df=" + df);

        rFFT.addValuePair(0, c0_R );
        
        for (int i = 1; i < (int) N; i++) {
            
            double f = i * df;
            
            rFFT.addValuePair(f, c[i].getReal());
            
        }
         
        /** 
         * 
         * AN DER STELLE IST DER KOMPLEXE TEIL NICHT NULL !!!
         * 
         * DER KOMPLEXE ANTEIL WIRD ABER IGNORIERT. DAHER FÜHRT DIE FOLGENDE
         * RÜCKTRANSFORMATION NICHT ZUR IDENTITÄT. !!! 
         *
         */
        return rFFT;
        
    }
    
    /**
     * Modification of time series.
     * 
     * Phase Randomization is moving the "imaginary part" by a randtom angle.
     */
    private void calc_modified_PR(TimeSeriesObjectFFT aThis, TimeSeriesObject rFFT, double samplingRate) {
        
        double[] data = aThis.getYData();
        
        int N_Original = data.length;
        
        data = extendToPowerOf2(data);
        
        FastFourierTransformer fft = new FastFourierTransformer( DftNormalization.STANDARD );

        Complex[] c = fft.transform(data, TransformType.FORWARD );
        Complex[] modCOMPLEX = new Complex[c.length];

        double N = c.length;

        double c0_R = c[0].getReal();
        double c0_I = c[0].getImaginary();

        double cf_R = c[(data.length - 1)].getReal();
        double cf_I = c[(data.length - 1)].getImaginary();

        double f0 = c0_R;
        double df = N / 6283.1853071795858D;
 
        for (int i = 1; i <= c.length / 2; i++) {
            
            double randPhase = Math.random() * Math.PI * 2.0;
            
            Complex e = new Complex( Math.E , 0.0 );
            
            Complex faktor = e.pow( Complex.I.multiply( randPhase ) );
            
            modCOMPLEX[i] = c[i].multiply(faktor);
            
            if (i < c.length / 2)
               modCOMPLEX[c.length - i] = c[c.length - i].multiply( faktor.conjugate() );
        
        }
        
        modCOMPLEX[0] = new Complex(0.0D, 0.0D);

        FastFourierTransformer fft2 = new FastFourierTransformer( DftNormalization.STANDARD);
        Complex[] modData = fft2.transform(modCOMPLEX, TransformType.INVERSE );

        for (int i = 0; i < N_Original; i++) {
            rFFT.addValuePair(i, modData[i].getReal());
        }
        
        TimeSeriesObjectFFT.debugComplexSeries(modCOMPLEX, " <PR> : COMPLEX-Modifiziert" );
        TimeSeriesObjectFFT.debugComplexSeries(modData, " <PR> : Transformiert" );
    }

    
    /**
     * Modification of the imaginary part is changing the power Spectrum and 
     * introduces controles long term correlations.
     * 
     */
    private void calc_modified_FF(TimeSeriesObjectFFT aThis, TimeSeriesObject rFFT, double beta, double samplingRate) {
        
//        debug = true;
        
        double[] data = aThis.getYData();
        
        int N_Original = data.length;
        
        data = extendToPowerOf2(data);
        
        FastFourierTransformer fft = new FastFourierTransformer( DftNormalization.STANDARD );

        Complex[] c = fft.transform(data, TransformType.FORWARD );
        Complex[] modCOMPLEX = new Complex[c.length];

        double N = c.length;

        double c0_R = c[0].getReal();
        double c0_I = c[0].getImaginary();

        double cf_R = c[(data.length - 1)].getReal();
        double cf_I = c[(data.length - 1)].getImaginary();
       
        double f0 = c0_R;
        double df = N / 6283.1853071795858D;
 
        for (int i = 1; i <= c.length / 2; i++) {
            
            double f = i * df;
            
            double faktor = Math.pow(f, -0.500 * beta);
            
            modCOMPLEX[i] = c[i].multiply(faktor);
            
            if (i < c.length / 2)
                modCOMPLEX[c.length - i] = c[c.length - i].multiply(faktor);
        }
        
        modCOMPLEX[0] = new Complex(0.0D, 0.0D);

        FastFourierTransformer fft2 = new FastFourierTransformer( DftNormalization.STANDARD);
        Complex[] modData = fft2.transform(modCOMPLEX, TransformType.INVERSE );

        for (int i = 0; i < N_Original; i++) {
            rFFT.addValuePair(i, modData[i].getReal());
        }
//        
//        if( debug ) {
//            TimeSeriesObjectFFT.debugComplexSeries(modCOMPLEX, beta + " : COMPLEX-Modifiziert" );
//            TimeSeriesObjectFFT.debugComplexSeries(modData, beta + " : Transformiert" );
//        }
    }

    public static boolean debug = false;
    
    /**
     * To speed up FFT we need a length which is a power of 2.
     * To achieve this we fill up with zero at the end of the series.
     * 
     * @param data
     * @return 
     */
    static private double[] extendToPowerOf2(double[] data) {
        int z = data.length;
        int i = 0;
        while (Math.pow(2.0D, i * 1.0D) < z) {
            i++;
        }
        i--;
        int z_min = (int) Math.pow(2.0D, i * 1.0D);
        i++;
        int z_max = (int) Math.pow(2.0D, i * 1.0D);

        //if ( debug ) 
        
        System.out.println("l=" + i + " z_min=" + z_min + " z_max=" + z_max + " mit 0.0 aufgefüllt:" + (z_max - z));
        
        double[] back = new double[z_max];
        for (int a = 0; a < z_max; a++) {
            if (a >= z) {
                back[a] = 0.0D;
            } else {
                back[a] = data[a];
            }
        }
        return back;
    }

    /**
     * Modifies the row and gives back the result of the final FFT
     * which is a time series of real values.
     * 

     * @return 
     */
    public TimeSeriesObjectFFT getModifiedTimeSeries_PhaseRandomized() {
        
        TimeSeriesObjectFFT rFFT = new TimeSeriesObjectFFT();
        rFFT.setLabel("PhaseRandomized {" + getLabel() + "}" );

        // WAS GENAU BEDEUTED DENN nun die 
        double band = 6283.1853071795858D; 
        calc_modified_PR(this, rFFT, band);

        return rFFT;
        
    }
    /**
     * Modifies the row and gives back the result of the final FFT
     * which is a time series of real values.
     * 
     * @param beta
     * @return 
     */
    public TimeSeriesObjectFFT getModifiedTimeSeries_FourierFiltered(double beta) {
        
        TimeSeriesObjectFFT rFFT = new TimeSeriesObjectFFT();
        rFFT.setLabel("FourierFilterd (beta=" + beta + "){" + getLabel() + "}" );

        // WAS GENAU BEDEUTED DENN nun die 
        double band = 6283.1853071795858D;
        calc_modified_FF(this, rFFT, beta, band);

        return rFFT;
        
    }



//    /**
//     * Hier wird ein KNICK eingefügt ...
//     * 
//     * @param aThis
//     * @param rFFT
//     * @param beta 
//     */
//    private void calc_modified_FFT2(TimeSeriesObjectFFT aThis, TimeSeriesObject rFFT, double beta) {
//        double[] data = aThis.getYData();
//        data = extendToPowerOf2(data);
//        FastFourierTransformer fft = new FastFourierTransformer( DftNormalization.STANDARD );
//
//        Complex[] c = fft.transform(data, TransformType.FORWARD );
//        Complex[] mod = new Complex[c.length];
//
//        double N = c.length;
//
//        double c0_R = c[0].getReal();
//        double c0_I = c[0].getImaginary();
//
//        double cf_R = c[(data.length - 1)].getReal();
//        double cf_I = c[(data.length - 1)].getImaginary();
//
//        double f0 = c0_R;
//        double df = N / 6283.1853071795858D;
//
//        System.out.println( "Anzahl der Koefizienten: " +  c.length ) ;
//        
//        
//        for (int i = 1; i < c.length; i++) {
//            double f = i * df;
//            double faktor = Math.pow(f, -0.5D * beta);
//            
//            if ( i < 4000 ) 
//                mod[i] = c[i].multiply(faktor);
//            else 
//                mod[i] = c[i];
//        }
//
//        mod[0] = new Complex(0.0D, 0.0D);
//
//        FastFourierTransformer fft2 = new FastFourierTransformer( DftNormalization.STANDARD );
//        Complex[] modData = fft2.transform(mod, TransformType.INVERSE);
//
//        for (int i = 0; i < c.length; i++) {
//            rFFT.addValuePair(i, modData[i].getReal());
//        }
//    }

}