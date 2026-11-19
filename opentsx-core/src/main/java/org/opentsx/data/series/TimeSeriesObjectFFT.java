/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.data.series;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.complex.ComplexUtils;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.jfree.data.xy.XYSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.*;


public class TimeSeriesObjectFFT extends TimeSeriesObject {

    private static final Logger LOG = LoggerFactory.getLogger(TimeSeriesObjectFFT.class);
    
    public TimeSeriesObjectFFT getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV( ) {
        return getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV(new Random());
    }

    public TimeSeriesObjectFFT getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV(Random random) {
        TimeSeriesObjectFFT rFFT = randomizedPhases(random, false);
        rFFT.setLabel( getLabel() + " (phase_randomized_MULTIPLY_random)");
        return rFFT;
    }

    public TimeSeriesObjectFFT getPhaseRandomizedModifiedFFT_SHUFFLED_INV( ) {
        return getPhaseRandomizedModifiedFFT_SHUFFLED_INV(new Random());
    }

    public TimeSeriesObjectFFT getPhaseRandomizedModifiedFFT_SHUFFLED_INV(Random random) {
        TimeSeriesObjectFFT rFFT = randomizedPhases(random, true);
        rFFT.setLabel( getLabel() + " (phase_randomized_SHUFFLE)");
        return rFFT;
    }

    /**
     * The new phases keep c_(N-k) the conjugate of c_k and the Nyquist coefficient c_(N/2) real, so the inverse
     * transform is real and keeps the amplitude spectrum of the zero-padded values. c_0 becomes 0.
     */
    private TimeSeriesObjectFFT randomizedPhases(Random random, boolean shuffle) {
        double[] data = getYData();
        Complex[] c = new FastFourierTransformer(DftNormalization.STANDARD)
                .transform(extendToPowerOf2(data), TransformType.FORWARD);
        int n = c.length;
        int half = n / 2;
        List<Double> phases = new ArrayList<Double>();
        for (int k = 1; k < half; k++) {
            phases.add(shuffle ? c[k].getArgument() : c[k].getArgument() + 2.0 * Math.PI * random.nextDouble());
        }
        if (shuffle) {
            Collections.shuffle(phases, random);
        }
        Complex[] mod = new Complex[n];
        mod[0] = Complex.ZERO;
        for (int k = 1; k < half; k++) {
            mod[k] = ComplexUtils.polar2Complex(c[k].abs(), phases.get(k - 1));
            mod[n - k] = mod[k].conjugate();
        }
        if (half > 0) {
            mod[half] = c[half];
        }
        Complex[] values = new FastFourierTransformer(DftNormalization.STANDARD).transform(mod, TransformType.INVERSE);
        TimeSeriesObjectFFT rFFT = new TimeSeriesObjectFFT();
        for (int i = 0; i < data.length; i++) {
            rFFT.addValuePair(i, values[i].getReal());
        }
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

        sb.append("#\n# Time series \n# Class : " + getClass() + " \n# Label  : " + this.label + "\n#\n# Number of value pairs = " + size + "\n#\n");
        sb.append("#\n#" + this.addinfo + "\n#\n");
        if (size > 0) {
            sb.append(getStatisticData("# "));
        }
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

    public void setDecimalFomrmatX(String format) {
        this.decimalFormat_X = new DecimalFormat(format);
    }

    public void setDecimalFomrmatY(String format) {
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
        int count = this.yValues.size();

        int remainder = count % bin;
        int elements = count - remainder;
        int blocks = elements / bin;

        double originalSum = summeY();

        mr.addStatusInfo("[BINNING] bin=" + bin + "\tRemainder=" + remainder + "\tBlocks=" + blocks);

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
        TimeSeriesObject result = new TimeSeriesObject();
        result.setLabel(getLabel() + "_AV");

        double sum = 0.0D;

        Enumeration en = this.yValues.elements();
        int c = 0;
        int z = -1;
        while (en.hasMoreElements()) {
            z++;
            c++;
            sum += ((Double) en.nextElement()).doubleValue();

            if (z == bin - 1) {
                double value = sum / bin;

                result.addValuePair(c, value);
                sum = 0.0D;
                z = -1;
            }

        }

        return result;
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
        double sum = 0.0D;
        Enumeration en = this.yValues.elements();
        while (en.hasMoreElements()) {
            sum += ((Double) en.nextElement()).doubleValue();
        }
        return sum;
    }

    public TimeSeriesObject diff(TimeSeriesObject mr2) {
        LOG.debug("diff series={} other={} valueCountDifference={}", label, mr2.label, this.yValues.size() - mr2.yValues.size());
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
        double originalSum = summeY();
        TimeSeriesObject mr = new TimeSeriesObject();
        double a = this.yValues.size() * 1.0D;
        double b = maxX * 1.0D;
        double fieldsPerBlock = b / a;
        mr.addStatusInfo("[SCALE] \tFields per Block:" + fieldsPerBlock + "\tvalues:" + a + "\tmaxX:" + b + "\n");

        mr.setLabel(getLabel() + "SCALED:x=" + maxX + "");
        Enumeration en = this.yValues.elements();
        int j = 0;
        while (en.hasMoreElements()) {
            double value = ((Double) en.nextElement()).doubleValue();
            double v = value / (fieldsPerBlock * 1.0D);
            for (int i = 0; i < fieldsPerBlock; i++) {
                mr.addValuePair(j, v);
                j++;
            }
        }
        mr.addStatusInfo("[SCALE] \tError: " + (mr.summeY() - originalSum) + "\n");
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
            throw new Exception("TimeSeriesObject is shorter than the shift distance");
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
     * Preserves coordinates when copying real-valued series; complex coefficients are not represented.
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
            mr.addValuePair(((Double) m.xValues.elementAt(i)).doubleValue(), ((Double) m.yValues.elementAt(i)).doubleValue());
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
        int count = mrs.length;

        int shortestLength = getShortestLength(mrs);

        TimeSeriesObject mr = new TimeSeriesObject("Mean_" + count);

        for (int i = 0; i < count; i++) {
            mr = mr.add(mrs[i], shortestLength);
        }
        mr.divide_Y_by(count);
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

        double maximumX = getMaxX();

        if (x_max > maximumX) {
            throw new Exception("Series is too short. xMax=" + maximumX + ", x_max=" + x_max);
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

    public TimeSeriesObject[] split(int length, int count) {
        TimeSeriesObject[] rows = new TimeSeriesObject[count];
        Enumeration en = this.yValues.elements();

        for (int i = 0; i < count; i++) {
            int c = 0;
            TimeSeriesObject mr = new TimeSeriesObject(this.label + " (" + i + ")");
            mr.setLabel("" + i);

            double buffer = 0.0D;
            double last = 0.0D;

            for (int j = 0; j < length; j++) {
                try {
                    buffer = ((Double) en.nextElement()).doubleValue();
                    mr.addValue(buffer);
                    last = buffer;
                } catch (NoSuchElementException e) {
                    c++;
                }
            }
            double mean = mr.getAvarage();
            if (c > 0) {
                mr.addComment(c + " missing values replaced with mean=" + mean + ".");
                LOG.debug("split filled missing values series={} missing={} mean={}", mr.getLabel(), c, mean);
                for (int d = 0; d < c; d++) {
                    mr.addValue(mean);
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
        LOG.debug("value counts series={} x={} y={}", label, this.xValues.size(), this.yValues.size());
    }

    public void show() {
    }

    public TimeSeriesObjectFFT getFFT(double samplingRate ) {
         

        TimeSeriesObject mr = TimeSeriesObjectFFT.calcFFT(this, samplingRate, TransformType.FORWARD);
        mr.setLabel("FFT : " + getLabel());

        return TimeSeriesObjectFFT.convertToMessreiheFFT( mr );
    
    }

    
    static public void debugComplexSeries( Complex[] c, String label ) {
        
        TimeSeriesObject real = new TimeSeriesObject( "Re" );
        TimeSeriesObject imaginary = new TimeSeriesObject( "Im" );
        
        // take only the real - value to build the transformed time series.
        for (int i = 0; i < c.length; i++) {
            real.addValuePair( i, c[i].getReal() );
            imaginary.addValuePair( i, c[i].getImaginary() );
        }
        
        Vector<TimeSeriesObject> mrv2 = new Vector<TimeSeriesObject>();
        mrv2.add( real );
        mrv2.add( imaginary );
 
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
        
 
        double c0_R = c[0].getReal();
        double c0_I = c[0].getImaginary();
        
        double fmin = 0;
        double fmax = 0.5D * samplingRate;

        double df = fmax / ( N * 0.5 );

        LOG.debug("transformed series={} length={} padded={} coefficients={} samplingRate={} c0={} nyquist={} fMin={} fMax={} df={}",
                aThis.getLabel(), data2.length, data.length, N, samplingRate, c[0], c[(int) (N / 2.0D)], fmin, fmax, df);

        rFFT.addValuePair(0, c0_R );
        
        for (int i = 1; i < (int) N; i++) {
            
            double f = i * df;
            
            rFFT.addValuePair(f, c[i].getReal());
            
        }
         
        // Discarding imaginary coefficients prevents an exact inverse transform.
        return rFFT;
        
    }
    

    
    /**
     * Modification of the imaginary part is changing the power Spectrum and 
     * introduces controles long term correlations.
     * 
     */
    private void calc_modified_FF(TimeSeriesObjectFFT aThis, TimeSeriesObject rFFT, double beta, double samplingRate) {
        
        
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
            
            double factor = Math.pow(f, -0.500 * beta);
            
            modCOMPLEX[i] = c[i].multiply(factor);
            
            if (i < c.length / 2)
                modCOMPLEX[c.length - i] = c[c.length - i].multiply(factor);
        }
        
        modCOMPLEX[0] = new Complex(0.0D, 0.0D);

        FastFourierTransformer fft2 = new FastFourierTransformer( DftNormalization.STANDARD);
        Complex[] modData = fft2.transform(modCOMPLEX, TransformType.INVERSE );

        for (int i = 0; i < N_Original; i++) {
            rFFT.addValuePair(i, modData[i].getReal());
        }
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

        
        LOG.debug("padded to a power of 2 length={} padded={} zeros={}", z, z_max, z_max - z);
        
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
     * Phase randomization with a uniform random phase per coefficient, as in
     * getPhaseRandomizedModifiedFFT_MULTIPLY_RANDOM_INV.
     */
    public TimeSeriesObjectFFT getModifiedTimeSeries_PhaseRandomized() {
        return getModifiedTimeSeries_PhaseRandomized(new Random());
    }

    public TimeSeriesObjectFFT getModifiedTimeSeries_PhaseRandomized(Random random) {
        TimeSeriesObjectFFT rFFT = randomizedPhases(random, false);
        rFFT.setLabel("PhaseRandomized {" + getLabel() + "}" );
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

        double band = 6283.1853071795858D;
        calc_modified_FF(this, rFFT, beta, band);

        return rFFT;
        
    }

}