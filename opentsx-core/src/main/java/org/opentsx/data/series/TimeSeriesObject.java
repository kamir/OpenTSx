package org.opentsx.data.series;


import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.algorithms.statistics.DatasetNormalizationTool;
import org.opentsx.algorithms.statistics.DistributionTester;
import org.opentsx.algorithms.statistics.HaeufigkeitsZaehlerDouble;
import org.jfree.data.xy.XYSeries;
import org.opentsx.data.model.EpisodesRecord;
import org.opentsx.data.model.Event;
import org.opentsx.data.model.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.*;


public class TimeSeriesObject implements Serializable {
    
    static final long serialVersionUID = 3519446562958441036L;

    private static final Logger LOG = LoggerFactory.getLogger(TimeSeriesObject.class);

    public static TimeSeriesObject sigmaForAll(Vector<TimeSeriesObject> rows) {
        
        int anz = rows.size();

        int shortestLength = getShortestLength( rows );

        StandardDeviation stdev = new StandardDeviation();

        TimeSeriesObject mr = new TimeSeriesObject("sigma_" + anz );

        for( int j=0; j < shortestLength; j++ ) {
            double[] a = new double[anz];
            for ( int i = 0; i < anz; i++ ) {
                a[i] = (Double)rows.elementAt(i).yValues.elementAt(j);
            };
            mr.addValuePair(j, stdev.evaluate( a) );
        }
       
        return mr;
    
    }

    public static Vector<TimeSeriesObject> fromComplex(Complex[] d, String label, double offset) {
        
        Vector<TimeSeriesObject> v = new Vector<TimeSeriesObject>();

        TimeSeriesObject real = new TimeSeriesObject( label + "_R" );
        TimeSeriesObject im = new TimeSeriesObject( label + "_I" );
        
        for( int i = 0; i < d.length ; i++ ) {
            real.addValue( d[i].getReal() + offset );
            im.addValue( d[i].getImaginary() + offset);
        }
        
        v.add( real );
        v.add( im );
        
        return v;
    }

    public static TimeSeriesObject getLinearFunction(double m, double n, double dx, double xMin, int N) {
        
        TimeSeriesObject mr = new TimeSeriesObject();
        
        mr.setLabel("m="+m + " n=" + n );
        
        for( int i = 0; i < N; i++ ) {
            mr.addValuePair( (xMin+i*dx), ( xMin + i * dx ) * m + n );
        }
        
        return mr;
    }
    
    /** 
     * This field hold a trend of length
     * 
     * "lengthTrend".
     */
    public double[] trend;
    public int lengthTrend;


 


    
    public TimeSeriesObject(double[] d ) {
        this();
        for( int i = 0; i < d.length; i++ ) {
            this.addValue(d[i]);
        }
    }
    
    public static double[] calcPeriodeTrend(TimeSeriesObject mr, int d ) {

        double[] sum = new double[d]; 
        double[] count = new double[d]; 
        double[] trend = new double[d]; 

        double[][] data = mr.getData();
        int z = mr.yValues.size();
        
        for( int i = 0; i < z; i++ ) {
            double v = data[1][i];
            int c = i % d;
            sum[c] = sum[c] + v;
            count[c]++;
        }
        
        for( int i = 0; i < d; i++ ) {
            trend[i] = sum[i] / count[i];
        }
        
        return trend;
    }
    
    public static TimeSeriesObject normalizeByPeriodeTrend(TimeSeriesObject mr, int d ) {

        
        TimeSeriesObject mr2 = new TimeSeriesObject();
        mr2.setLabel( mr.getLabel() + "_t"+d+"_removed");

        mr2.trend = calcPeriodeTrend(mr,d);
        mr2.lengthTrend = d;
        
        double[][] data = mr.getData();
 
        int z = mr.yValues.size();
        
        for( int i = 0; i < z; i++ ) {
            int c = i % d;
            double t = data[0][i];
            double v = data[1][i] / mr2.trend[c];
            mr2.addValuePair(t, v);
        }
        return mr2;
    }

    public static TimeSeriesObject getFromEpisode(EpisodesRecord episode) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( episode.getLabel().toString() );

        double[][] data = mr.getData();

        int z = episode.getObservationArray().size();

        long t0 = episode.getTStart();

        for( int i = 0; i < z; i++ ) {

            Observation obs = episode.getObservationArray().get(i);

            double t = obs.getTimestamp();

            double v = obs.getValue();

            mr.addValuePair(t, v);

        }

        return mr;

    }

    public boolean isNotEmpty() {
        boolean v = true;
        if ( this.xValues == null ) {
            v = false;
        }
        else {
            if ( xValues.size() == 0 ) v = false;
        }
        return v;
    };
    
    // each String[2] holds id1 and id2
    public Vector<String[]> xLabels = new Vector<String[]>();
    public Vector<String> xLabels2 = new Vector<String>();

    public void doFilter( double aboveMW ) {
        for( int i = 0; i < yValues.size(); i++ ) {
            double vx = (Double)xValues.elementAt(i);
            double vy = (Double)yValues.elementAt(i);

            if ( (vy / aboveMW) > this.av ) yValues.setElementAt( av, i);

        }
    }
    
   

    public TimeSeriesObject replaceZeroWithAverage() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(label);
        mr.setLabel_X(label_X);
        mr.setLabel_Y(label_Y);

        this.calcAverage();

        double av = this.getAvarage();

        for( int i = 0; i < yValues.size(); i++ ) {
            double vx = (Double)xValues.elementAt(i);
            double vy = (Double)yValues.elementAt(i);
            if (vy == 0.0) vy = av;
            mr.addValuePair(vx, vy);
        };

        return mr;
    };

    
    private static int getShortestLength(Vector<TimeSeriesObject> rows) {
        boolean isFirst = true;
        int l = 0;
        for ( TimeSeriesObject mr : rows ) {
            int l2 = mr.xValues.size();
            if ( isFirst ) {
               l = l2;
               isFirst = false;
            }
            else {
               if ( l2 < l ) l = l2;
            }
        };
        return l;
    }
    
    private static int getShortestLength(TimeSeriesObject[] mrs) {
        boolean isFirst = true;
        int l = 0;
        for ( TimeSeriesObject mr : mrs ) {
            int l2 = mr.xValues.size();
            if ( isFirst ) {
               l = l2;
               isFirst = false;
            }
            else {
               if ( l2 < l ) l = l2;
            }
        };
        return l;
    }

    public DecimalFormat getDecimalFormat_STAT() {
        return decimalFormat_STAT;
    }

    public DecimalFormat getDecimalFormat_X() {
        return decimalFormat_X;
    }

    public DecimalFormat getDecimalFormat_Y() {
        return decimalFormat_Y;
    }

    public StringBuffer getStatus() {
        return status;
    }

    int anzahlOfAllAddedMessreihen = 1;

    public static TimeSeriesObject getUniformDistribution(int i, double d, double d0) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(i + " => uniform (" +d+","+d0+")");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        for( int j = 0 ; j<i; j++ ) {
            mr.addValuePair(1.0*j , RNGWrapper.getStdRandomUniform ( d,d0 ) );
        }
        return mr;    
    }
        
    public static TimeSeriesObject getGaussianDistribution(int i) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(i + " => gaussian");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        for( int j = 0 ; j<i; j++ ) {
            mr.addValuePair(1.0*j , RNGWrapper.getStdRandomGaussian() );
        }
        return mr;
    }

    public static TimeSeriesObject getGaussianDistribution(int i, double mw, double std) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(i + " => gaussian (mu="+mw+", sigma="+std+")");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        for( int j = 0 ; j<i; j++ ) {
            mr.addValuePair(1.0*j ,  RNGWrapper.getStdRandomGaussian( mw, std )  );
        }
        return mr;
    }
    
    public static TimeSeriesObject getExpDistribution(int i, double mw) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(i + " => exp (lambda="+mw+")");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        for( int j = 0 ; j<i; j++ ) {
            mr.addValuePair(1.0*j , RNGWrapper.getStdRandomExp( mw ) );
        }
        return mr;
    }
    
    public static TimeSeriesObject getParetoDistribution(int i, double mw) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(i + " => pareto (alpha="+mw+")");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        for( int j = 0 ; j<i; j++ ) {
            mr.addValuePair(1.0*j , RNGWrapper.getStdRandomPareto( mw ) );
        }
        return mr;
    }

    public static TimeSeriesObject getGeometricDistribution(int i, double mw) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(i + " => geometric (p="+mw+")");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        for( int j = 0 ; j<i; j++ ) {
            mr.addValuePair(1.0*j , RNGWrapper.getStdRandomGeometric(mw) );
        }
        return mr;
    }

    
    public static TimeSeriesObjectFFT getGaussianDistribution2(int i, double mw, double std) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
        mr.setLabel(i + " values => gaussian distribution");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        for( int j = 0 ; j<i; j++ ) {
            mr.addValuePair(1.0*j , RNGWrapper.getStdRandomGaussian( mw, std ) );
        }
        return mr;
    }
 
    public int[] getSize() {
        int[] d = new int[2];
        d[0] = xValues.size();
        d[1] = yValues.size();
        return d;
    };

    

    public static final String dfx = "0.00000";
    public static final String dfy = "0.00000";
    public static final String dfstat = "0.00000";

    DecimalFormat decimalFormat_X = new DecimalFormat( dfx );
    DecimalFormat decimalFormat_Y = new DecimalFormat( dfy );
    DecimalFormat decimalFormat_STAT = new DecimalFormat( dfstat );

    public String label = "?";
    public String addinfo = "";

    public Vector xValues = null;
    public Vector yValues = null;


    /**
     *
     * @param label label of the series
     * @param xL label of the x values
     * @param yL label of the y values
     */public TimeSeriesObject(String label, String xL, String yL) {
        this(label);
        this.label_Y = yL;
        this.label_X = xL;
    };


    public TimeSeriesObject(String label) {
        this();
        this.label = label;
    };

    /**
     * Creates the data containers.
     */
    public TimeSeriesObject() {
        xValues = new Vector<Double>();
        yValues = new Vector<Double>();
        if ( !noStatus ) {
            label = "unnamed - " + DateFormat.getDateTimeInstance().format( new Date( System.currentTimeMillis() ));
            status = new StringBuffer();
            status.append( "[CREATION] \t" + new Date( System.currentTimeMillis() ) +"\n" );
            status.append( "[FORMAT]   \tx:" + dfx + "\ty:" + dfy +"\n" );        
        };    
        dateHash = new Hashtable<Date,Double>();
        hashedValues = new Hashtable<String,Double>();
    }
    
    static public boolean noStatus = false;


    /**
     * Adds a value pair.
     *
     * @param x
     * @param y
     */
    public void addValuePair( double x, double y ) {
        xValues.add(x);
        yValues.add(y);
    };


    public Vector getXValues() {
        return xValues;
    }

    public Vector getYValues() {
        return yValues;
    }

    /**
     * Formats the series for the console.
     *
     * @return
     */
    @Override
    public String toString() {

        StringBuffer sb = new StringBuffer();

        int size = xValues.size();

        sb.append("#\n# TimeSeriesObject \n# Class : " + this.getClass() + " \n# Label  : " + this.label + "\n# Nr of data points => " + size );
        sb.append("#\n#" + this.addinfo + "\n#");
        sb.append("#\n" + status.toString() );
        if ( size > 0 ) {
            sb.append( getStatisticData("# ") +"\n" );
        }

        int MAX = Math.min(5, size);

        for (int i = 0 ; i < MAX; i++  ) {

            double x = (Double)getXValues().elementAt(i);
            double y = (Double)getYValues().elementAt(i);

            sb.append(  decimalFormat_X.format(x) + " " + decimalFormat_Y.format(y) + "\n");

        }
        sb.append( " ... ");
        sb.append("#\n" + sbComments.toString() );
        return sb.toString();
    };

    public void setLabel(String label) {
        this.label = label;
    }

    public void setAddinfo(String addinfo) {
        this.addinfo = addinfo;
    }

    public String getAddinfo() {
        return addinfo;
    }

    public String getLabel() {
        if ( this.label == null ) return "?";
        return this.label;
    }

    public double[][] getData() {
        int z = this.xValues.size();
        double[][] data = new double[2][z];

        int max = xValues.size();

        for (int i = 0 ; i < max; i++  ) {
            double x = (Double)getXValues().elementAt(i);
            double y = (Double)getYValues().elementAt(i);
            data[0][i]= x;
            data[1][i]= y;
        }
        return data;
    };
    
    public double[][] getDataT() {
        int z = this.xValues.size();
        double[][] data = new double[z][2];

        int max = xValues.size();

        for (int i = 0 ; i < max; i++  ) {
            double x = (Double)getXValues().elementAt(i);
            double y = (Double)getYValues().elementAt(i);
            data[i][0]= x;
            data[i][1]= y;
        }
        return data;
    };


    /**
     * Computes the mean of the y values and keeps it in the field av.
     */
    public void calcAverage() {
        int z = this.xValues.size();
        double sum = 0.0;

        for (int i = 0 ; i < z; i++  ) {
            sum = sum + (Double)getYValues().elementAt(i);

        }
        av = sum / ((double)z);

    };

    double av = 0.0;
    double sum = 0.0;

    public double getAvarage2() {
        calcAverage();
        return av;
    };

    /**
     * Returns the mean of the y values. It is computed on every call.
     */
    public double getAvarage() {
        calcAverage();
        return av;
    };

    public void setDecimalFomrmatX(String format) {
        this.decimalFormat_X = new DecimalFormat( format );
    }
    public void setDecimalFomrmatY(String format) {
        this.decimalFormat_Y = new DecimalFormat( format );
    }

    public double getMinX() {
        double[][] data = this.getData();
        double[] dx = data[0];
        return NumberUtils.min( dx );
    };
    
    public double getMaxX() {
        double[][] data = this.getData();
        double[] dx = data[0];
        return NumberUtils.max( dx );
    };

    public double getMaxY() {
        double[][] data = this.getData();
        double[] dy = data[1];
        return NumberUtils.max( dy );
    };

    public double sumYValues() { 
        double[][] data = this.getData();
        double[] dx = data[0];
        double[] dy = data[1];
        return StatUtils.sum( dy );
    };

    public Hashtable<String, String> getStatisticData( Hashtable<String, String> table ) {

        double[][] data = statisticInput();
        double[] dx = data[0];
        double[] dy = data[1];

        StandardDeviation stdev = new StandardDeviation();
        table.put( "max_T" , decimalFormat_STAT.format(NumberUtils.max( dx ) ) );
        table.put( "min_T" , decimalFormat_STAT.format(NumberUtils.min( dx ) ) );
        table.put( "nr_T" ,  dx.length + "" );

        table.put( "max_Y" , decimalFormat_STAT.format(NumberUtils.max( dy ) ) );
        table.put( "min_Y" , decimalFormat_STAT.format(NumberUtils.min( dy ) ) );
        table.put( "mw__Y" , decimalFormat_STAT.format(StatUtils.mean( dy ) ) );
        table.put( "std_Y" , decimalFormat_STAT.format(stdev.evaluate( dy ) ) );
        table.put( "var_Y" , decimalFormat_STAT.format(StatUtils.variance( dy ) ) );
        table.put( "sum_Y" , decimalFormat_STAT.format(StatUtils.sum( dy ) ) );
        table.put( "nr__Y" , dy.length +"" );

        return table;
    }

    double[][] statisticInput() {
        if ( xValues.isEmpty() ) {
            throw new IllegalStateException( "getStatisticData: the series " + label
                    + " has no values; expected at least 1 value" );
        }
        return getData();
    }

    public String getStatisticData( String pre ) {
        StringBuffer sb = new StringBuffer();
        double[][] data = statisticInput();
        double[] dx = data[0];
        double[] dy = data[1];

        StandardDeviation stdev = new StandardDeviation();

        sb.append(pre+"X:\n");
        sb.append(pre+"==\n");

        sb.append(pre+"Max    \t max_X=" + decimalFormat_STAT.format(NumberUtils.max( dx ) ) + "\n" );
        sb.append(pre+"Min    \t min_X=" + decimalFormat_STAT.format(NumberUtils.min( dx ) ) + "\n" );
        sb.append(pre+"Mean   \t avg_X=" + decimalFormat_STAT.format(StatUtils.mean( dx ) ) + "\n" );
        sb.append(pre+"StdDev \t std_X=" + decimalFormat_STAT.format(stdev.evaluate( dx ) ) + "\n" );
        sb.append(pre+"Var    \t var_X=" + decimalFormat_STAT.format(StatUtils.variance( dx ) ) + "\n" );
        sb.append(pre+"Sum    \t sum_X=" + decimalFormat_STAT.format(StatUtils.sum( dx ) ) + "\n" ) ;
        sb.append(pre+"Len    \t len_X=" +  dx.length + "\n" );

        sb.append(pre+"\n");

        sb.append(pre+"Y:\n");
        sb.append(pre+"==\n");

        sb.append(pre+"Max    \t max_Y=" + decimalFormat_STAT.format(NumberUtils.max( dy ) )  + "\n" );
        sb.append(pre+"Min    \t min_Y=" + decimalFormat_STAT.format(NumberUtils.min( dy ) )  + "\n" );
        sb.append(pre+"Mean   \t mw__Y=" + decimalFormat_STAT.format(StatUtils.mean( dy ) )  + "\n" );
        sb.append(pre+"StdDev \t std_Y=" + decimalFormat_STAT.format(stdev.evaluate( dy ) ) + "\n" );
        sb.append(pre+"Var    \t var_Y=" + decimalFormat_STAT.format(StatUtils.variance( dy ) )+ "\n" );
        sb.append(pre+"Sum    \t sum_Y=" + decimalFormat_STAT.format(StatUtils.sum( dy ) ) + "\n" );
        sb.append(pre+"Len    \t nr__Y=" + dy.length + "\n#" );

        return sb.toString();

    };

    public double[] getYData() {
        double[] yV = new double[ this.getYValues().size()];

        for (int i = 0; i < this.getYValues().size(); i++) {
               Double y = (Double)this.getYValues().get(i);
               yV[i] = y;
        }

        return yV;
    }

    public XYSeries getXYSeries() {
        XYSeries series = new XYSeries( this.getLabel() );
        
        for (int i = 0; i < this.getXValues().size(); i++) {
               Double x = (Double)this.getXValues().get(i);
               Double y = (Double)this.getYValues().get(i);
               series.add( x , y );
        }

        return series;
    }

    /**
     * Writes a header of comment lines starting with # and then every pair as x, separator, y on its own line.
     * Creates the parent directories.
     */
    public void writeToFile(File f) {
        writeToFile(f, ' ');
    }

    public void writeToFile(File f, Character separator) {
        File parent = f.getAbsoluteFile().getParentFile();
        try {
            if ( parent != null ) {
                Files.createDirectories(parent.toPath());
            }
            try (Writer w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
                w.write("# TimeSeriesObject\n# Label : " + getLabel() + "\n# Nr of data points => " + xValues.size() + "\n");
                for (int i = 0; i < xValues.size(); i++) {
                    w.write(xValues.elementAt(i) + separator.toString() + yValues.elementAt(i) + "\n");
                }
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException(f + ": cannot write the file (" + ex + ")", ex);
        }
    }

    /**
     * Append a new y-value.
     * 
     * X is simply increased by 1.
     * 
     * @param y 
     */
    public void addValue(double y) {
        int x = this.getXValues().size() + 1;
        this.addValuePair( 1.0 * x , y );
    }

    String label_X = "x";
    String label_Y = "y";

    public String getLabel_X() {
        return this.label_X;
    }

    public void setLabel_X(String label_X) {
        this.label_X = label_X;
    }

    public void setLabel_Y(String label_Y) {
        this.label_Y = label_Y;
    }

    public  String getLabel_Y() {
       return this.label_Y;
    }

    /**
     * Of the y values.
     * @return
     */
    public double getStddev() {

        Object[] data = yValues.toArray();
        double[] dy = new double[ data.length ];
        for( int i = 0; i < data.length ; i++ ) {
            dy[i] = (Double)data[i];
        }

        StandardDeviation stdev = new StandardDeviation();

        double sigma = stdev.evaluate( dy );
        return sigma;
    }

    /**
     * Normalizes to Maximum
     **/ 
    public void normalize() {
        double[][] data = this.getData();
        double[] dx = data[0];
        double[] dy = data[1];

        double maxY = NumberUtils.max( dy );

        for( int i = 0; i < this.yValues.size(); i++ ) {
            double d = (Double)this.yValues.elementAt(i);

            this.yValues.set(i, new Double( d / maxY ) );
        }
        this.label = this.label.concat(" (max=" + maxY +")" );
    }
 
    
    /**
     * Replaces every y value with its base 10 logarithm. A value of 0 or below gives NaN.
     */
    public void calcLog10_for_Y() {
        for( int i = 0; i < this.yValues.size(); i++ ) {
            double d = (Double)this.yValues.elementAt(i);
            this.yValues.set(i, d > 0 ? Math.log10(d) : Double.NaN );
        }
        this.label = this.label.concat("_log10" );
    }

    /**
     * Replaces every y value with its natural logarithm. A value of 0 or below gives NaN.
     */
    public void calcLn_for_Y() {
        for( int i = 0; i < this.yValues.size(); i++ ) {
            double d = (Double)this.yValues.elementAt(i);
            this.yValues.set(i, d > 0 ? Math.log(d) : Double.NaN );
        }
        this.label = this.label.concat("_ln" );
    }

    /**
     * Returns a new series with the natural logarithm of every y value at the same x. A value of 0 or below gives
     * NaN, so the result has as many points as this series.
     */
    public TimeSeriesObject calcMR_Ln_for_Y() {
        TimeSeriesObject mr = new TimeSeriesObject( this.label.concat("_ln" ) );
        for( int i = 0; i < this.yValues.size(); i++ ) {
            double d = (Double)this.yValues.elementAt(i);
            double x = (Double)this.xValues.elementAt(i);
            mr.addValuePair( x, d > 0 ? Math.log(d) : Double.NaN );
        }
        return mr;
    }

    public double getYValueForX2( double x, double epsilon ) {
 
        double returnValueY = Double.NaN;
        
        int counter = 0;
        
        Enumeration<Double> vx = this.xValues.elements();
        while( vx.hasMoreElements() ) {
            double wx = vx.nextElement();
            if ( x > (wx-epsilon) && (x <= (wx+epsilon) ) ) {
                returnValueY = ((Double)this.yValues.elementAt(counter)).doubleValue();
            }
            counter++;
        }
        return returnValueY;
    };
    
    public double getYValueForX2( double x ) {
        int i = this.xValues.indexOf( x );
        double y = (Double)this.yValues.elementAt(i);
        return y;
    };

    public double getYValueForX(int i) {
        int indexX = this.xValues.indexOf( new Double(i) );
        Double value = null;
        if( indexX >= 0 ) value = (Double)this.yValues.elementAt(indexX) ;
        else value = new Double( 0 );
        return value.doubleValue();
    }

    int binning = 1;

    // Sums each block of "bin" values into one value; a rest shorter than bin is dropped.
    public TimeSeriesObject setBinningX_sum(int bin ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.binning = bin;
        mr.setLabel( this.getLabel() + "_BIN=" +bin+"" );
        Enumeration<Double> en = this.yValues.elements();
        int anzahl = this.yValues.size();

        int rest = anzahl % bin;
        int elements = anzahl - rest;
        int blocks = elements / bin;

        double summeA = this.summeY();

        mr.addStatusInfo( "[BINNING] bin=" + bin + "\tRest=" + rest + "\tblocks=" + blocks  );

        int i=0;
        int j=0;
        double v = 0;

        while ( i < elements ) {
            v = v + en.nextElement();

            if( i % bin == (bin-1) ) {
                mr.addValuePair( j , v);
                v = 0;
                j++;
            }
            i++;
        }
        return mr;

    };

    StringBuffer status = null;

    /**
     *
     * @param bin
     * @return
     */
    public TimeSeriesObject setBinningX_average(int bin ) {

        TimeSeriesObject resultat = new TimeSeriesObject();
        resultat.setLabel( getLabel() + "_AV("+bin+")");
       
        double summe = 0;
        
        Enumeration en = yValues.elements();
        int c = 0;
        int z = -1;
        while ( en.hasMoreElements() ) {
            z++;
            
            summe = summe + (Double)en.nextElement();
            
            if ( z == (bin-1) ) {
               double wert = summe/(double)bin;
               resultat.addValuePair( c , wert );
               summe = 0.0;
               z=-1;
               c++;
            }
            
        }

        return resultat;
    };

    public void divide_Y_by( double divBy ) {
        Vector<Double> y = new Vector<Double>();
        Enumeration<Double> en = this.yValues.elements();
        while( en.hasMoreElements() ) {
            y.add( en.nextElement() / divBy );
        }
        this.yValues = y;
    };

    public void add_to_Y( double offs ) {
        Vector<Double> y = new Vector<Double>();
        Enumeration<Double> en = this.yValues.elements();
        while( en.hasMoreElements() ) {
            y.add( en.nextElement() + offs);
        }
        this.yValues = y;
    };
        
    public double summeY() {
        double summe = 0;
        Enumeration<Double> en = this.yValues.elements();
        while( en.hasMoreElements() ) {
            summe = summe + en.nextElement();
        }
        return summe;
    };

    public TimeSeriesObject divide_by(TimeSeriesObject mr2 ) {
        int n = this.yValues.size();
        if ( mr2.yValues.size() != n ) {
            throw new IllegalArgumentException("divide_by: the series have " + n + " and " + mr2.yValues.size()
                    + " values; expected series of equal length");
        }
        TimeSeriesObject mr = new TimeSeriesObject();
        for( int k = 0; k < n; k++ ) {
            double v1 = (Double)this.yValues.elementAt(k);
            double v2 = (Double)mr2.yValues.elementAt(k);
            mr.addValuePair((Double)this.xValues.elementAt(k), v2 == 0.0 ? Double.NaN : v1 / v2);
        }
        mr.setLabel( this.getLabel() + " / " + mr2.getLabel() );
        return mr;
    };
    
    // pointwise difference of the two series
    public TimeSeriesObject diff(TimeSeriesObject mr2 ) {

        LOG.debug("diff series={} other={} valueCountDifference={}", label, mr2.label, this.yValues.size() - mr2.yValues.size());
        double difff = 0.0;
        TimeSeriesObject mr = new TimeSeriesObject();

        int max = mr2.xValues.size();
        for( int k = 0; k < max; k++ ) {

            double v1 = (Double)this.yValues.elementAt(k);
            double v2 = (Double)mr2.yValues.elementAt(k);
            double delta = v1-v2;
            difff = difff + Math.abs(delta);
            mr.addValuePair(k, delta);
        }
        return mr;
    };

    public TimeSeriesObject add(TimeSeriesObject mr2 ) {

        String label = this.getLabel();

        this.anzahlOfAllAddedMessreihen++;

        int n = this.yValues.size();
        if ( n > 0 && mr2.yValues.size() != n ) {
            throw new IllegalArgumentException("add: the series have " + n + " and " + mr2.yValues.size()
                    + " values; expected series of equal length");
        }
        TimeSeriesObject base = n == 0 ? mr2 : this;
        TimeSeriesObject mr = new TimeSeriesObject();
        for( int k = 0; k < mr2.yValues.size(); k++ ) {
            double v1 = n == 0 ? 0.0 : (Double)this.yValues.elementAt(k);
            mr.addValuePair((Double)base.xValues.elementAt(k), v1 + (Double)mr2.yValues.elementAt(k));
        }
        label = label + "+" + mr2.getLabel();
        mr.setLabel( label);
        return mr;
    };

    public TimeSeriesObject add(TimeSeriesObject messreihe, int shortestLength) {
        int n = this.yValues.size();
        if ( (n > 0 && n < shortestLength) || messreihe.yValues.size() < shortestLength ) {
            throw new IllegalArgumentException("add: the series have " + n + " and " + messreihe.yValues.size()
                    + " values; expected at least " + shortestLength + " values in each");
        }
        TimeSeriesObject base = n == 0 ? messreihe : this;
        TimeSeriesObject mr = new TimeSeriesObject();
        for( int k = 0; k < shortestLength; k++ ) {
            double v1 = n == 0 ? 0.0 : (Double)this.yValues.elementAt(k);
            mr.addValuePair((Double)base.xValues.elementAt(k), v1 + (Double)messreihe.yValues.elementAt(k));
        }
        return mr;
    }

    /**
     * Appends the values of mr to this series.
     * 
     */ 
    public void addValues( TimeSeriesObject mr ) {
        Enumeration en = mr.xValues.elements();
        int i = 0;
        while( en.hasMoreElements() ) {
            double x = (Double)en.nextElement();
            double y = (Double) mr.yValues.elementAt(i);
            this.addValuePair(x, y);
            i++;
        }
    };

    public TimeSeriesObject scaleX(int maxX) {

        double summeOriginal = summeY();
        TimeSeriesObject mr = new TimeSeriesObject();
        double a = this.yValues.size()*1.0;
        double b = (maxX*1.0);
        double fieldsPerBlock = b / a;
        mr.addStatusInfo("[SCALE] \tFields per Block:" + fieldsPerBlock + "\tvalues:" + a + "\tmaxX:" +b +"\n");
        
        mr.setLabel( this.getLabel() + "SCALED:x="+maxX+"");
        Enumeration<Double> en = this.yValues.elements();
        int j = 0;
        while( en.hasMoreElements() ) {
            double wert = en.nextElement();
            double v = wert / (fieldsPerBlock*1.0);
            for( int i = 0; i < fieldsPerBlock; i++ ) {
                
                mr.addValuePair(j, v);
                j++;
            }
        }
        mr.addStatusInfo("[SCALE] \tError: " + (mr.summeY() - summeOriginal)+"\n" );
        return mr;
    }

    public void addStatusInfo(String string) {
        this.status.append( "\n#" + string );
    }
    public String getStatusInfo() {
        return this.status.toString();
    }

    public TimeSeriesObject cut(int nrOfValues ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );

        for( int i = 0; i < nrOfValues ; i++ ) {
            mr.addValuePair( (Double)xValues.elementAt(i),(Double)yValues.elementAt(i) );
        };
        mr.addStatusInfo("[CUT] \tLength old:" + this.xValues.size() + "\tLength new:" + nrOfValues +"\n");

        return mr;
    };

    /**
     * Shifts the values of the series by "offset" positions.
     *
     *   offset greater than 0: right
     *   offset smaller than 0: left
     *
     * @param offset
     *
     * @return a new series
     *
     * @throws Exception
     */
    public TimeSeriesObject shift(int offset ) throws Exception {
        if( Math.abs(offset) > yValues.size() ) throw new Exception("shift: offset " + offset + " exceeds the "
                + yValues.size() + " values; expected an offset from -" + yValues.size() + " to " + yValues.size());
        if ( offset < 0 ) return this.shiftLeft( Math.abs(offset));
        else return this.shiftRight( Math.abs(offset));
    };

        /**
     * Shifts the values of the series by "offset" positions.
     *
     *   offset greater than 0: right
     *   offset smaller than 0: left
     *
     * @param offset
     *
     * @return a new series
     *
     * @throws Exception
     */
    public TimeSeriesObject shift(int offset , double v ) throws Exception {
        if( Math.abs(offset) > yValues.size() ) throw new Exception("shift: offset " + offset + " exceeds the "
                + yValues.size() + " values; expected an offset from -" + yValues.size() + " to " + yValues.size());
        if ( offset < 0 ) return this.shiftLeft( Math.abs(offset), v);
        else return this.shiftRight( Math.abs(offset), v);
    };

    public TimeSeriesObject copy(int limit ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        mr.hashedValues = new Hashtable<String,Double>();
        mr.xLabels = new Vector<String[]>();
        
        for( int i = 0; i < limit && i < yValues.size(); i++ ) {
            mr.addValuePair( (double)i , (Double)yValues.elementAt(i) );
        }
        
        return mr;
    }
        
    public TimeSeriesObject copy() {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            mr.addValuePair( (double)i , (Double)yValues.elementAt(i) );
        }
        return mr;
    }

    public TimeSeriesObject cutFromStart(int n ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() + "( -" + n + " values )" );

        int max = this.yValues.size();

        for( int i = n; i < max ; i++ ) {
            mr.addValuePair( (double)i , (Double)yValues.elementAt(i) );
        }
        return mr;
    };

    /**
     * x is a timestamp ....
     * 
     * @param v
     * @return 
     */
    public TimeSeriesObject addToX2(int v ) {
        
        LOG.debug("add to x series={} value={}", label, v);
        
        

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );

        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            double xi = (Double)this.xValues.elementAt(i);
            double yi = (Double)this.yValues.elementAt(i);
            mr.addValuePair( xi + v , yi );
        }
  
        mr.addStatusInfo("[Add to x]=" + v +"\n");

        return mr;
    };
        
    /**
     * x is just a running number ...
     * 
     * @param v
     * @return 
     */
    public TimeSeriesObject addToX(int v ) {
        
        LOG.debug("add to x series={} value={}", label, v);

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );

        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            mr.addValuePair( 1.0*(i+v) , (Double)this.yValues.elementAt(i) );
        }
  
        mr.addStatusInfo("[Add to x]=" + v +"\n");

        return mr;
    };
    
    private TimeSeriesObject shiftRight(int nrOfValues , double missingValue ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );

        int max = this.yValues.size();

        for( int i = 0; i < nrOfValues ; i++ ) {
            mr.addValuePair( (double)i , missingValue );
        }
        for( int i = nrOfValues; i < max ; i++ ) {
            mr.addValuePair( (double)i,(Double)yValues.elementAt(i-nrOfValues) );
        };
        mr.addStatusInfo("[SHIFT] \tRIGHT: " + nrOfValues +"\n");

        return mr;
    };

    private TimeSeriesObject shiftRight(int nrOfValues ) {
        return shiftRight(nrOfValues, 0.0);
    };

    private TimeSeriesObject shiftLeft(int nrOfValues, double missingValue ) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );

        int max = this.yValues.size();

        int j = 0;
        for( int i = nrOfValues; i < max ; i++ ) {
            mr.addValuePair( (double)j,(Double)yValues.elementAt(i) );
            j++;
        };
        for( int i = 0; i < nrOfValues ; i++ ) {
            mr.addValuePair( (double)j , missingValue );
            j++;
        }
        mr.addStatusInfo("[SHIFT] \tLEFT: " + nrOfValues +"\n");

        return mr;
    };

    private TimeSeriesObject shiftLeft(int nrOfValues ) {
        return shiftLeft(nrOfValues, 0.0 );
    };


    private void setStatusInfo(String statusInfo) {
        this.status = new StringBuffer();
        this.status.append(statusInfo );

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

        for( int i = 0; i < max ; i++ ) {
            mr.addValuePair( (Double)xValues.elementAt(i) * f , (Double)yValues.elementAt(i)  );
        }
        return mr;
    }



    public TimeSeriesObject scaleY_2(double f) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( "x"+f );
            mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            mr.addValuePair( (Double)xValues.elementAt(i)  , (Double)yValues.elementAt(i) * f  );
        }
        return mr;
    }

    public void setLabels(String label, String xL, String yL) {
        this.label = label;
        this.label_Y = yL;
        this.label_X = xL;
    }


    /**
     * Averages the series pointwise over the length of the shortest series and keeps the x values of the first
     * series.
     *
     * @param mrs
     * @return
     */
    static public TimeSeriesObject averageForAll(TimeSeriesObject[] mrs ) {
        int anz = mrs.length;

        int shortestLength = getShortestLength( mrs );
        
        TimeSeriesObject mr = new TimeSeriesObject("avg_" + anz );

        for ( int i = 0; i < anz; i++ ) {
            mr = mr.add(mrs[i] , shortestLength );
        };
        mr.divide_Y_by( anz );
        return mr;
    };

    public static TimeSeriesObject averageForAll(Vector<TimeSeriesObject> rows) {
        int anz = rows.size();

        int shortestLength = getShortestLength( rows );
        
        TimeSeriesObject mr = new TimeSeriesObject("avg_" + anz );

        for ( int i = 0; i < anz; i++ ) {
            mr = mr.add(rows.elementAt(i) , shortestLength );
        };
        mr.divide_Y_by( anz );
        return mr;
    }
    
    /**
     * Returns the x value of the first point whose y value lies strictly within epsilon of y, or NaN when no point
     * does.
     */
    public double getX_for_Y2( double y , double epsilon ) {
        for( int i = 0; i < this.yValues.size(); i++ ) {
            double wy = (Double)this.yValues.elementAt(i);
            if( y > (wy-epsilon) && y < (wy+epsilon) ) {
                return (Double)this.xValues.elementAt(i);
            }
        }
        return Double.NaN;
    };

    public double getX_for_Y( double y ) {
        int counter = 0;
        double value = 0.0;
        Enumeration<Double> vy = this.yValues.elements();
        while( vy.hasMoreElements() ) {
            double wy = vy.nextElement();
            if ( y > wy ) {
                value = ((Double)this.xValues.elementAt(counter)).doubleValue();
            }
            counter++;
        }
        return value;
    };

    /**
     * Calulates a liner Fit for all values ...
     * @param x_min
     * @param x_max
     * @return
     */
    public SimpleRegression linFit(double x_min, double x_max) throws Exception {
        
        SimpleRegression regression = new SimpleRegression();

        Vector<Double> x = new Vector<Double>();
        Vector<Double> y = new Vector<Double>();
        double m = 0.0;
        int counter = 0;

        double maks = this.getMaxX();


        for( int i = 0; i < this.xValues.size(); i++ ) {

           Double x1 = (Double)xValues.elementAt(i);
           if ( x1 >= x_min && x1 <= x_max ) {
               double ax = x1;
               double ay = ((Double)yValues.elementAt(counter)).doubleValue();
               regression.addData( ax,ay) ;
           }
           counter++;
        }


        return regression;
    }

 


    public TimeSeriesObject[] split(int length, int anzahl) {
        TimeSeriesObject[] rows = new TimeSeriesObject[anzahl];
        Enumeration en = yValues.elements();

        for( int i = 0; i <  anzahl; i++ ) {
            int c = 0;
            TimeSeriesObject mr = new TimeSeriesObject( this.label+" ("+i+")" );
            mr.setLabel(""+i);

            double puffer = 0.0;
            double last = 0.0;

            for( int j = 0; j < length; j++ ) {
                try {
                    puffer = (Double)en.nextElement();
                    mr.addValue( puffer );
                    last = puffer;
                }
                catch( NoSuchElementException e ) { 
                    c++;
                }
            }
            double mean = mr.getAvarage();
            if ( c > 0 ) {
                mr.addComment(  c + " missing values replaced with mean=" + mean + "." );
                LOG.debug("split filled missing values series={} missing={} mean={}", mr.getLabel(), c, mean);
                for( int d = 0 ; d < c ; d++ ){ 
                    mr.addValue( mean );
                }
            }
            rows[i] = mr;
        };
        return rows;
    }
    
    public TimeSeriesObject shrinkX(double min, double max) {
        Vector<Double> x = new Vector<Double>();
        Vector<Double> y = new Vector<Double>();
        
        Enumeration en = yValues.elements();

        int i = 0;
        while ( en.hasMoreElements() ) {
            double yv = (Double)en.nextElement();
            double xv = (Double)xValues.elementAt(i);
            i++;
            
            if( xv>=min && xv <= max ) {
                x.add( xv );
                y.add( yv );
            }
        }    

        TimeSeriesObject mr = new TimeSeriesObject( this.label+" shrinked("+min+", "+max+")" );
 
        for( int j = 0; j < x.size(); j++ ) {
            mr.addValuePair( (Double)x.elementAt( j ), (Double)y.elementAt( j ));
        }
        return mr;
    }

    public StringBuffer sbComments = new StringBuffer();
    public Vector<String> comments = new Vector<String>();
    public void addComment(String string) {
        sbComments.append("# " + string + "\n");
        comments.add( string );
    }

    public void scaleXto(int i) {
        Enumeration<Double> en = this.xValues.elements();
        double max = getMaxX();
        double f = (double)i / max;
        int x = 0;
        while ( en.hasMoreElements() ) {
            Double v = en.nextElement();
            v=v*f;
            xValues.setElementAt(v, x);
            x++;
        }
    }

    public boolean hasNAN_values() {
        boolean back = false;
        Enumeration<Double> en = this.getYValues().elements();
        while( en.hasMoreElements() ) {
            Double d = en.nextElement();
            if ( Double.isNaN( d.doubleValue() ) ) back = true;
        };
        return back;
    };

    public void checkKonsistenz() {
        LOG.debug("value counts series={} x={} y={}", label, xValues.size(), yValues.size());

    };

    public void show() {



        
    }

    public TimeSeriesObject getLogReturn() {
        
        TimeSeriesObject mr = new TimeSeriesObject();
        
        mr.setLabel( getLabel() + "_Log(RelDiff)");

        int len = this.xValues.size();

        // calc diffs from data
        double delta = 0.0;
        double logDelta = 0.0;
        double now = 0.0;
        
        double last = (Double)this.yValues.elementAt(0);
        
        for (int i = 0; i < len; i++) {
            
            now = (Double)this.yValues.elementAt(i);
            
            delta = Math.log(now) - Math.log( last );
            
            last = now;
            
            mr.addValuePair(i, delta );
        
        }

        return mr;
    }

    
       /**
     * We sum all ( values - AVERAGE ) 
     * @return 
     */
    public TimeSeriesObject getNextDiffs() {

       TimeSeriesObject mr = new TimeSeriesObject();
        
        mr.setLabel( getLabel() + "_Returns");

        int len = this.xValues.size();

        // calc diffs from data
        double delta = 0.0;
        double now = 0.0;
        
        double last = (Double)this.yValues.elementAt(0);
        
        for (int i = 0; i < len; i++) {
            
            now = (Double)this.yValues.elementAt(i);
            
            delta = now - last;
            
            last = now;
            
            mr.addValuePair(i, delta );
        
        }

        return mr;
    };
    /**
     * We sum all ( values - AVERAGE ) 
     * @return 
     */
    public TimeSeriesObject getProfile() {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( getLabel() + "_Profil");

        int len = this.xValues.size();

        // mean value of data
        this.calcAverage();
        double mw = this.getAvarage();

        // calc profile from data
        double prsum = 0.0;
        for (int i = 0; i < len; i++) {
            prsum = prsum + ( (Double)this.yValues.elementAt(i) - mw);
            mr.addValuePair(i, prsum);
        }

        return mr;
    };

    /**
     * Uses the x values of the first row. The x values of the other rows are not compared. Each point is the mean
     * of the rows that have a value at that index.
     *
     * @param rows
     * @return
     */
    public static TimeSeriesObject calcAveragOfRows(Vector<TimeSeriesObject> rows ) throws Exception{
        LOG.debug("average of rows rows={}", rows.size());
        TimeSeriesObject r = new TimeSeriesObject();
        r.setLabel("mean of " + rows.size() + " series");
        r.setLabel_X( rows.elementAt(0).getLabel_X() );
        r.setLabel_Y( rows.elementAt(0).getLabel_Y() );

        int i = rows.elementAt(0).xValues.size();
        int j = rows.size();
        int a = 0;
        while( a < i ) {
            int c = 1;
            TimeSeriesObject ref = rows.elementAt(0);

            Double vx0 = (Double)ref.xValues.elementAt(a);

            double sum = (Double)ref.yValues.elementAt(a);

            for( int z=1; z<j; z++) {
                TimeSeriesObject vergl = rows.elementAt(z);
                if ( a < vergl.yValues.size() ) {
                    sum = sum + (Double)vergl.yValues.elementAt(a);
                    c++;
                }
            }

            double mw = sum / (double)c;
            
            r.addValuePair(vx0, mw);

            a++;
        }
        return r;
    };

    public TimeSeriesObject[] splitInto(int bin) {

        int l = this.xValues.size();
        int z = l / bin;

        return split(bin, z);
    }

    public TimeSeriesObject subtractAverage() {

        this.calcAverage();

        double av = this.getAvarage();

        int c = 0;
        TimeSeriesObject mr = new TimeSeriesObject();
        Enumeration<Double> en = this.yValues.elements();
        while ( en.hasMoreElements() ) {
            double v = en.nextElement();
            v = v - av;
            mr.addValuePair( (Double)xValues.elementAt(c), v );
            c++;
        }
        return mr;
    }

    public void append(TimeSeriesObject mr0) {
        for( int i = 0; i < mr0.getSize()[0]; i++ ) {
            this.addValuePair( (Double)mr0.getXValues().elementAt(i),  (Double)mr0.getYValues().elementAt(i) );
        }
    }


    public void supressAtStart( int length, double value ) {
        // keeps the length and overwrites the values
            for( int i = 0; i < length; i++ ) {
                yValues.setElementAt( value ,i );
            }
    }

    
    public TimeSeriesObject combineAsXWithY(TimeSeriesObject mrY) {
        TimeSeriesObject mr = new TimeSeriesObject();
        for( int i = 0; i < xValues.size(); i++ ) {
            mr.addValuePair( (Double)yValues.elementAt(i), (Double)mrY.yValues.elementAt(i) );
        }
        return mr;
    }

    public TimeSeriesObject abs() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( "abs( " + this.getLabel() + ") ");
        int c = 0;

        Enumeration<Double> en = this.yValues.elements();
        while ( en.hasMoreElements() ) {
            double v = en.nextElement();
            v = Math.abs(v);
            mr.addValuePair( (Double)xValues.elementAt(c), v );
            c++;
        }
        return mr;

    }

    public Hashtable<Double,Vector<Double>> get_Y_hashedData() {
        Hashtable<Double,Vector<Double>> h = new Hashtable<Double,Vector<Double>>();
        int i = 0;
        for( Object x : this.xValues ) {
            Double y = (Double)yValues.elementAt(i);
            Vector<Double> ids = h.get(y);
            if ( ids == null ) {
                ids = new Vector<Double>();
                h.put(y, ids);
            }
            ids.add((Double) x);
            i++;
        }
        return h;
    }

    public Vector<ValuePair> getValuePairs() {
        double[][] d = this.getData();
        Vector<ValuePair> vps = new Vector<ValuePair>();
        for( int i= 0; i < this.xValues.size(); i++ ) {
            ValuePair vp = new ValuePair();
            vp.x = d[0][i];
            vp.y = d[1][i];
            vps.add(vp);
        }
        return vps;
    };

    public void addValuePair(ValuePair vp) {
        this.addValuePair( vp.x, vp.y );
    }

    public void supressAtEnd(int START_CUTTOF_LENGTH, double value) {

        // keeps the length and overwrites the values
            for( int i = START_CUTTOF_LENGTH; i < this.yValues.size(); i++ ) {
                yValues.setElementAt( value ,i );
            }
   
    }

    public void shuffleXValues() {
        Collections.shuffle( this.xValues );
    }
    
    /**
     * Use Collections for shuffelfing the content of
     * the yValues Vector.
     */
    public void shuffleYValues() {
        Collections.shuffle( this.yValues );
        
    }
    
        /**
     * Use Collections for shuffelfing the content of
     * the yValues Vector.
     */
    public void shuffleYValues( int max ) {
        String label = this.getLabel();
        this.setLabel(label+" (s"+max+")");
        int i = 0;
        while( i < max ) {
            Collections.shuffle( this.yValues );
            i++;
        }
    }

    public TimeSeriesObject cutOut(int begin, int ende) {
       
        TimeSeriesObject r = new TimeSeriesObject();
       
       r.setLabel( getLabel() +"("+begin+","+ende+")" );
       
       for( int i = begin; i < ende; i++ ) {
              if ( i < xValues.size() ) {
                  double x = (Double)xValues.elementAt( i );
                  double y = (Double)yValues.elementAt( i );
                  r.addValuePair(x, y);
           }
       }

       return r;
    }

    /**
     * For automatic saving in a project directory.
     * 
     */
    String filename = null;
    public void setFileName(String string) {
        filename = string;
    }

    public String getFileName() {
        if ( filename == null ) filename = getLabel();
        return filename;
    }

    public void addValues(Vector<Double> dataRawTIMES) {
        for( double da : dataRawTIMES ) {
            this.addValue(da);
        }
    }

    public TimeSeriesObject calcLogLog() {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() + "_LogLog" );
        
        int c = 0;

        Enumeration<Double> en = this.yValues.elements();
        while ( en.hasMoreElements() ) {
            double v = en.nextElement();
            v = Math.abs(v);
            
            double lx = Math.log10( (Double)xValues.elementAt(c) );
            double ly = Math.log10( (Double)yValues.elementAt(c) );
            
            mr.addValuePair( lx, ly );
            c++;
        }
        return mr;
        
    }

    public static TimeSeriesObject createFromStringLine(String s1 ) {
        TimeSeriesObject m = new TimeSeriesObject();
        StringTokenizer t1 = new StringTokenizer( s1 , ";" );
        while( t1.hasMoreTokens() ) { 
            String s = t1.nextToken();
            Double y = Double.parseDouble(s);
            m.addValue(y);
        }
        return m;
    };

    public TimeSeriesObject normalizeToStdevIsOne() {
        TimeSeriesObject mrNeu = DatasetNormalizationTool.normalizeToMWZeroANDSTDevONE(this);
        mrNeu.xLabels = this.xLabels;
        mrNeu.label = mrNeu.label + " (stdv=1.0;mw=0.0)";
        mrNeu.label_X = this.label_X;
        mrNeu.label_Y = this.label_Y;
        return mrNeu;
    }

    // adds a link
    public void addValue(double d, String[] label) {
        this.addValue(d);
        this.hashValueByLabel( label[0]+"_"+label[1] , d);
        this.xLabels.add( label );
    }
    
        // adds a link
    public void addValue(double d, String label) {
        this.addValue(d);
        this.hashValueByLabel( label , d);
        this.xLabels2.add( label );
    }

    public Hashtable hashedValues = new Hashtable<String,Double>();
    public void hashValueByLabel(String string, double d) {
        hashedValues.put(string, d);
    }
    
    public Hashtable<Date, Double> dateHash;
    public void hashValueByDate(Date date, double d) {
        dateHash.put(date, d);
    }


    public TimeSeriesObject limitTo(int i) {
        TimeSeriesObject mr = copy( i );
        return mr;
    }

    
    public void mapDateHashedValuesToRow() {
        List<Date> l = getOrderedDateKeys();
        Iterator it = l.iterator();
        while( it.hasNext() ) { 
            Date d = (Date)it.next();
            double v = dateHash.get( d );
            this.addValue( v );
        };
        
    }
    
    public List<Date> getOrderedDateKeys(Date begin, Date end) {
        Enumeration<Date> s = dateHash.keys();
        List<Date> l = new ArrayList<Date>();
        while( s.hasMoreElements() ) { 
            Date now = s.nextElement();
            if ( now.after(begin) && now.before(end) ) l.add( now );
        };
        Collections.sort(l);
        return l;
    }

    public List<Date> getOrderedDateKeys() {
        Enumeration<Date> s = dateHash.keys();
        List<Date> l = new ArrayList<Date>();
        while( s.hasMoreElements() ) { 
            l.add(s.nextElement());
        };
        Collections.sort(l);
        return l;
    }

    public void mapDataToDateHash(Calendar fd) {
        // maps all values into the date hash
        this.dateHash.clear();        
        for( Object o : yValues ) { 
            Double v = (Double)o;
            this.hashValueByDate( fd.getTime(), v);
            fd.add( Calendar.DAY_OF_MONTH, 1);        
        }
    }

    /**
     * 
     * We just extract data from the list 
     * 
     * @param liste 
     */
    public void filterAndMapBack(List<Date> liste) {
        this.yValues = new Vector<Double>();
        this.xValues = new Vector<Double>();
        
        Iterator it = liste.iterator();
        while( it.hasNext() ) { 
            Date d = (Date)it.next();
            Double v = dateHash.get( d );
            if ( v != null ) this.addValue( v );
            else this.addValue(0.0);
        }
    }
    
    public void mapToStartingDate( int year, int month, int day ) { 
        Calendar cal = new GregorianCalendar();
        cal.clear();
        cal.set( year, month-1, day );
        mapDataToDateHash(cal);    
    }

    public double getMinY() {
        double[][] data = this.getData();
        double[] dy = data[1];
        return NumberUtils.min( dy );
    
    }

    public String getY_values_as_Line() {
        StringBuffer s = new StringBuffer();
        for( Object y : this.yValues ) { 
            double yv = (Double)y;
            s.append( yv + "\t" );
        }
        return s.toString();
    }

    String descr = "?";
    public void setDescription(String string) {
        descr = string;
    }
    public String getDescription() {
        return descr;
    }

    public TimeSeriesObject getYLogData() {
            
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            double v = 0.0;
            double y = (Double)yValues.elementAt(i);
            if ( y > 0.0 ) v = Math.log( y ) ;
            mr.addValuePair( (double)i , v );
        }
        return mr;
    
    }

    String identifier = "?";
    public void setIdentifier(String toString) {
         identifier = toString;
    }

    public String getIdentifier() {
        return identifier;
    }

    public int[] getYData_as_INT() {
        double[] d = this.getYData();
        int[] i = new int[ d.length ];
        int j = 0;
        for( double dd : d ) { 
            i[j] = (int)dd;
            j++;
        }
        return i;
    }

    
    public long getTime_t0() {
        return t0;
    }    
 

    public double getSamplingRate() {
        return samplingRate;
    }

 
    long t0 = -1;
    
    double samplingRate = 1.0;

    public void add_value_to_Y(double v) {
        Vector y = new Vector();
        Enumeration en = this.yValues.elements();
        while (en.hasMoreElements()) {
            y.add(Double.valueOf(((Double) en.nextElement()).doubleValue() + v));
        }
        this.yValues = y;
    }

    public TimeSeriesObject log() {
     
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            double y=0.0;
            double a = (Double)yValues.elementAt(i);
            if ( a == 0 ) y = 0.0;
            else
                y = Math.log10( a );
            mr.addValuePair( (double)i , y );
        }
        return mr;
    
    }

      public TimeSeriesObject subtractFromX(double d) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            double x = (Double)xValues.elementAt(i);
            double y = (Double)yValues.elementAt(i);

            mr.addValuePair( x - d , y );
        }
        return mr;    
    
    }
      
    public static TimeSeriesObject calcAVOfRows(Vector<TimeSeriesObject> grIWL, Vector<TimeSeriesObject> grCN, String l) {
        TimeSeriesObject r = new TimeSeriesObject();
        int z = 0;
        
        for( TimeSeriesObject mr : grIWL ) {
            r = r.add(mr);
            z++;
        }
        
        for( TimeSeriesObject mr : grCN ) {
            r = r.add(mr);
            z++;
        }
        
        r.divide_Y_by(z);
        
        LOG.debug("added rows series={} rows={}", l, z);
        r.setLabel( l );
        return r;
    }  
      
    public static TimeSeriesObject calcSumOfRows(Vector<TimeSeriesObject> grIWL, Vector<TimeSeriesObject> grCN, String l) {
        TimeSeriesObject r = new TimeSeriesObject();
        int z = 0;
        
        for( TimeSeriesObject mr : grIWL ) {
            r = r.add(mr);
            z++;
        }
        
        for( TimeSeriesObject mr : grCN ) {
            r = r.add(mr);
            z++;
        }
        LOG.debug("added rows series={} rows={}", l, z);
        r.setLabel( l );
        return r;
    }

    public TimeSeriesObject divideXBy(double d) {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( this.getLabel() );
        mr.binning = this.binning;
        mr.decimalFormat_STAT = this.decimalFormat_STAT;
        mr.decimalFormat_X = this.decimalFormat_X;
        mr.decimalFormat_Y = this.decimalFormat_Y;
        mr.status = new StringBuffer();
        mr.label_X = this.label_X;
        mr.label_Y = this.label_Y;
        int max = this.yValues.size();

        for( int i = 0; i < max ; i++ ) {
            double x = (Double)xValues.elementAt(i);
            double y = (Double)yValues.elementAt(i);

            mr.addValuePair( x / d , y );
        }
        return mr;        
    }

    public TimeSeriesObject getTrendRow() {
        
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( label + "_NO_TRENDS_AVAILABLE" );

        if ( this.trend != null ) {
            mr.setLabel( label + "_t" + this.lengthTrend );

            int i = 0;
            for( double v : this.trend ) {
                mr.addValuePair( i, v);
                i++;
            }
        }
        
        return mr;
    }

    private String groupKey = null;

    public String getGroupKey() {
        return groupKey;
    }
    public void setGroupKey(String key) {
        groupKey = key;
    }
    

    
    public double calculateShannonEntropy() {
        
        TimeSeriesObject m = this.copy();
        
        m.normalize();
        
        List<Integer> values = m.discretizeY();
        
        
  Map<Integer, Integer> map = new HashMap<Integer, Integer>();
  // count the occurrences of each value
  for (Integer sequence : values) {
    if (!map.containsKey(sequence)) {
      map.put(sequence, 0);
    }
    map.put(sequence, map.get(sequence) + 1);
  }
 
  // calculate the entropy
  Double result = 0.0;
  for (Integer sequence : map.keySet()) {
    Double frequency = (double) map.get(sequence) / values.size();
    result -= frequency * (Math.log(frequency) / Math.log(2));
  }
 
  return result;
}

    int DEFAULT_DISCRETIZATION = 100;
    private List<Integer> discretizeY() {
        
        return discretizeY( DEFAULT_DISCRETIZATION );
        
    }

    private List<Integer> discretizeY(int DEFAULT_DISCRETIZATION) {
        
        List<Integer> list = new ArrayList<Integer>();
        for( double y : this.getYData() ) {
            list.add ( (int)( y * (double)DEFAULT_DISCRETIZATION) % DEFAULT_DISCRETIZATION );
        }
        
        return list;
        
    }

    public void labelWithEntropy() {
        
        DecimalFormat df = new DecimalFormat("0.000");
        double H = this.calculateShannonEntropy();
        
        setLabel( getLabel() + " (H=" + df.format(H) +")" );
    
    }
    
    public void labelWithEntropy(String base) {
        DecimalFormat df = new DecimalFormat("0.000");
        double H = this.calculateShannonEntropy();
        
        setLabel( base + " (H=" + df.format(H) +")" );
    }

    
    /**
     * 
     * @param z
     * @return 
     */
    public TimeSeriesObject calcEntropyForWindow(int z ) {
        if (z < 4) {
            throw new IllegalArgumentException("window length: " + z
                    + " is less than 4; expected a window length of at least 4 values");
        }
        int i = 0;
        TimeSeriesObject m = new TimeSeriesObject();
        m.setLabel( this.getLabel() + "H(l="+z+")" );
        
        
        while( i + 1 < this.getYData().length ) {
            TimeSeriesObject t = this.cutOut(i, i + z);
            m.addValuePair( i , t.calculateShannonEntropy() );
            i = i + (int)( z * 0.25 );
        }
        return m;
    }

    public TimeSeriesObject zip(TimeSeriesObject mr2) {

        TimeSeriesObject m = new TimeSeriesObject( );
        m.setLabel( this.getLabel() + " zip( " + mr2.getLabel() + " ) ");
        
        for ( int i = 0; i < mr2.getYData().length; i++ ) {
            m.addValuePair(this.getYData()[i], mr2.getYData()[i] );
        }
        return m;
        
        
    }
    
    public TimeSeriesObject distributionOfYValues(int min, int max, int bins) {
    
        HaeufigkeitsZaehlerDouble hz = new HaeufigkeitsZaehlerDouble();
        hz.setLabel( this.getLabel() + "_" + this.getLabel_Y() );
        
        hz.min = min;
        hz.max = max;
        hz.intervalle = bins;
        
        hz.addData( this.yValues );
        
        hz.calcWS();
        
        return hz.getHistogram();
        
    }

    public TimeSeriesObject distributionOfYValues(int bins) {
    
        HaeufigkeitsZaehlerDouble hz = new HaeufigkeitsZaehlerDouble();
        hz.setLabel( this.getLabel() + "_" + this.getLabel_Y() );
        
        hz.min = this.getMinY();
        hz.max = this.getMaxY();
        hz.intervalle = bins;
        
        hz.addData( this.yValues );
        
        hz.calcWS();
        
        return hz.getHistogram();
        
    }
    
      public TimeSeriesObject cumulatedDistributionOfYValues(int bins) {
    
        HaeufigkeitsZaehlerDouble hz = new HaeufigkeitsZaehlerDouble();
        hz.setLabel( this.getLabel() + "_" + this.getLabel_Y() );
        
        hz.min = this.getMinY();
        hz.max = this.getMaxY();
        hz.intervalle = bins;
        
        hz.addData( this.yValues );
        
        hz.calcWS();
        
        return hz.mrCUMUL;
        
    }

    public TimeSeriesObject getXValuesAsMessreihe() {
        Vector<Double> v = this.getXValues();
        Collections.sort(v);
        
        TimeSeriesObject m = new TimeSeriesObject();
        for( double x : v ) {
            m.addValue(x);
        }
        return m;
        
    }

    public double[] getSWTestResult() throws IOException {
    
        DistributionTester dt = DistributionTester.getDistributionTester();
        return dt.testDataset(this.getYData());
    }

    /**
     * For the Schreiber-Schmitz method: overwrites the values by rank.
     * 
     * @param refSeries
     * @return 
     */
    public TimeSeriesObject exchangeRankWise(TimeSeriesObject refSeries) {

        // the series with amplitudes to be exchanged
        TimeSeriesObject ranked = this.getRanks()[0];

        // the series wit the right values
        TimeSeriesObject[] r2Ranked = refSeries.getRanks();
        
        Hashtable rankTableRefValues = r2Ranked[1].getHashedByRank( r2Ranked[0] );
        
        
        TimeSeriesObject rb = new TimeSeriesObject();
        rb.setLabel( "ReRanked_" + this.getLabel() + " with ampl. from " + refSeries.getLabel() );
        
        int i = 0;
        // go over the ranks ...
        Iterator it = ranked.yValues.iterator();
        
        while( it.hasNext() ) { 
            
            double pos = i;
            
            // current value has rank ....
            double rankOfValueOriginal = (double)it.next();
            
            
            // take value Y1 from referenz series with the same rank
            Double Y1 = (Double)rankTableRefValues.get( rankOfValueOriginal );
            
            if ( Y1 != null )
                rb.addValuePair( pos, Y1 );
            
            i++;
        };
        
        return rb;
        
         
    }
    
    public int getRank(ArrayList<Double> l, double z) {
        int i = 0;
        while( l.get(i) < z ) {
            i++;
        }
        return i;
    }
    
  
    
    public TimeSeriesObject[] getRanks() {
        
        TimeSeriesObject r = new TimeSeriesObject();
        TimeSeriesObject rORIGINAL = new TimeSeriesObject();
        
        r.setLabel( "RANKs_" + this.getLabel() );
        rORIGINAL.setLabel( "OriginalVaues_" + this.getLabel() );
        
        ArrayList l = new ArrayList();
        l.addAll( this.yValues );
        Collections.sort(l);
        
        int i = 0;
        Iterator it = this.yValues.iterator();
        while( it.hasNext() ) { 

            double v = (double)it.next();
            r.addValuePair( i, getRank( l, v ));
            rORIGINAL.addValuePair( i, v );
            i++;
        };
        
        TimeSeriesObject[] RESULT= new TimeSeriesObject[2];
        RESULT[0] = r;
        RESULT[1] = rORIGINAL;
        
        return RESULT;
    }
    
    

    // for each value we store the value hashed by rank
    public Hashtable getHashedByRank( TimeSeriesObject values) {
        
        
        Hashtable t = new Hashtable();
        
        int i = 0;
        
        Iterator it = this.yValues.iterator();
        
        while( it.hasNext() ) { 
            
            double val = (double)it.next();
            
            double rank = (double)values.yValues.elementAt(i);
            
            
            t.put( rank , val );
            i++;
            
        };
        
        
        return t;
    }

    public void appendToLabel(String l, String s) {
        this.label = this.label + s + l;
    }


    public TimeSeriesObject concat(Event value) {
       this.addValuePair( value.getTimestamp() , value.getValue() );
       return this;
    }

    public Vector<TimeSeriesObject> getSeriesWithPercentiles() {

        return getSeriesWithPercentiles( 0 );

    }


    /**
     * We skipp some values in the beginning.
     *
     * @param offset
     * @return
     */
    public Vector<TimeSeriesObject> getSeriesWithPercentiles(int offset) {

        Vector<TimeSeriesObject> result = new Vector<TimeSeriesObject>();
        result.add( this );

        TimeSeriesObject tsoP1 = new TimeSeriesObject();
        TimeSeriesObject tsoP2 = new TimeSeriesObject();
        TimeSeriesObject tsoP3 = new TimeSeriesObject();

        tsoP1.setLabel( this.getLabel() + " p75" );
        tsoP2.setLabel( this.getLabel() + " p95" );
        tsoP3.setLabel( this.getLabel() + " p99" );

        Percentile p75 = new Percentile( 75.0 );
        Percentile p95 = new Percentile( 95.0 );
        Percentile p99 = new Percentile( 99.0 );

        double[] values = this.getYData();

        for( int i = 0; i < offset; i++ ) {
            tsoP1.addValue(0);
            tsoP2.addValue(0);
            tsoP3.addValue(0);
        }

        for( int i = 1; i < (this.getYData().length - offset - 1); i++ ) {

            p75.setData( values , offset, i );
            p95.setData( values , offset, i );
            p99.setData( values , offset, i );

            tsoP1.addValue( p75.evaluate() );
            tsoP2.addValue( p95.evaluate() );
            tsoP3.addValue( p99.evaluate() );
        }

        result.add( tsoP1 );
        result.add( tsoP2 );
        result.add( tsoP3 );

        return result;

    }

}
