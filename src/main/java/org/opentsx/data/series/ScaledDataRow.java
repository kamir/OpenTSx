package org.opentsx.data.series;

import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;

import javax.swing.*;
import java.text.DecimalFormat;
import java.util.Vector;

/**
 * Eine TimeSeriesObject wird hier "umskaliert" um ein
 * Skalenverhalten zu überprüfen.
 *
 * Diese allg. Klasse ist ein Vorlage und daher abstract.
 * In der Klasse ScalingLawSQUAREROOT wird zum Beispiel
 * eine Variable wie die Personenzahl und ein Exponent
 * zur Skalierung benutzt.
 * 
 * @author kamir
 */
abstract public class ScaledDataRow implements ITimeSeriesObject {

    public String label = null;

    DecimalFormat decimalFormat_X = null;
    DecimalFormat decimalFormat_Y = null;
    DecimalFormat decimalFormat_STAT = new DecimalFormat("0.0000");

    TimeSeriesObject mr = null;

    public double scaleVar = 1.0;
    public double exponent = 1.0;
    public double scaleVar2;
    public double exponent2;
     
    Vector<Double> sxValues = null;
    Vector<Double> syValues = null;
    
    public String defaultLabel;

    public ScaledDataRow() {};

    public ScaledDataRow( TimeSeriesObject mr ) {
        this.mr = mr;
        sxValues = new Vector<Double>();
        syValues = new Vector<Double>();
    };

    public void setMessreihe( TimeSeriesObject mr ) {
        this.mr = mr;
        sxValues = new Vector<Double>();
        syValues = new Vector<Double>();
    };

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return this.label;
    }

    public void addValuePair( double x, double y ) {
        sxValues.add(x);
        syValues.add(y);
    };
    
    public Vector<Double> getXValues() {
       return sxValues;
    }

    public Vector<Double> getYValues() {
       return syValues;
    }

    public void calcScalingLaw() {

        int max = mr.getXValues().size();

        for (int i = 0 ; i < max; i++  ) {
            double x = (Double) mr.getXValues().elementAt(i);
            double y = (Double) mr.getYValues().elementAt(i);

            double sx = calcScaledX( x );
            double sy = calcScaledY( y );

            addValuePair(sx, sy);
        };
    };

    public double[][] getData() {
        int z = this.sxValues.size();
        double[][] data = new double[2][z];

        int max = sxValues.size();

        for (int i = 0 ; i < max; i++  ) {
            double x = getXValues().elementAt(i);
            double y = getYValues().elementAt(i);
            data[0][i]= x;
            data[1][i]= y;
        }
        return data;
    };

    public String toString() {
        StringBuffer sb = new StringBuffer();

        int size = getXValues().size();

        sb.append("#\n# Skalierte M e s s r e i h e \n# Klasse : " + this.getClass() + " \n# Label  : " + label + "\n#\n# Anzahl Wertepaare = " + size + "\n#\n");

        sb.append( getStatisticData("# ") );
        sb.append("\n");
        for (int i = 0 ; i < size; i++  ) {
            double x = (Double)getXValues().elementAt(i);
            double y = (Double)getYValues().elementAt(i);

            sb.append( x + "\t" + y + "\n");
        }
        return sb.toString();
    };


 
    abstract public double calcScaledX( double x );
    abstract public double calcScaledY( double y );

    public void setDecimalFomrmatX(String format) {
        this.decimalFormat_X = new DecimalFormat( format );
    }
    public void setDecimalFomrmatY(String format) {
        this.decimalFormat_Y = new DecimalFormat( format );
    }

    abstract public String getDefaultLabel();

    public void initParameter() {
        String message = this.mr.getLabel() + "\n\nscaleVar = ";
        String in = javax.swing.JOptionPane.showInputDialog(new JFrame(), message );
        scaleVar = Double.parseDouble(in);
        this.setLabel( this.getDefaultLabel() + mr.getLabel() );
    }


    public String getStatisticData(String pre) {
        StringBuffer sb = new StringBuffer();

        double[][] data = this.getData();
        double[] dx = data[0];
        double[] dy = data[1];

        StandardDeviation stdev = new StandardDeviation();

        sb.append(pre+"X:\n");
        sb.append(pre+"==\n");

        sb.append(pre+"Max    \t max_X=" + decimalFormat_STAT.format(StatUtils.max( dx ) ) + "\n" );
        sb.append(pre+"Min    \t min_X=" + decimalFormat_STAT.format(StatUtils.min( dx ) ) + "\n" );
        sb.append(pre+"Mean   \t avg_X=" + decimalFormat_STAT.format(StatUtils.mean( dx ) ) + "\n" );
        sb.append(pre+"StdAbw \t std_X=" + decimalFormat_STAT.format(stdev.evaluate( dx ) ) + "\n" );
        sb.append(pre+"Var    \t var_X=" + decimalFormat_STAT.format(StatUtils.variance( dx ) ) + "\n" );
        sb.append(pre+"Sum    \t sum_X=" + decimalFormat_STAT.format(StatUtils.sum( dx ) ) + "\n" ) ;
        sb.append(pre+"Len    \t len_X=" + dx.length + "\n" );

        sb.append(pre+"\n");

        sb.append(pre+"Y:\n");
        sb.append(pre+"==\n");

        sb.append(pre+"Max    \t max_Y=" + decimalFormat_STAT.format(StatUtils.max( dy ) )  + "\n" );
        sb.append(pre+"Min    \t min_Y=" + decimalFormat_STAT.format(StatUtils.min( dy ) )  + "\n" );
        sb.append(pre+"Mean   \t avg_Y=" + decimalFormat_STAT.format(StatUtils.mean( dy ) )  + "\n" );
        sb.append(pre+"StdAbw \t std_Y=" + decimalFormat_STAT.format(stdev.evaluate( dy ) )  + "\n" );
        sb.append(pre+"Var    \t var_Y=" + decimalFormat_STAT.format(StatUtils.variance( dy ) )+ "\n" );
        sb.append(pre+"Sum    \t sum_Y=" + decimalFormat_STAT.format(StatUtils.sum( dy ) ) + "\n" );
        sb.append(pre+"Len    \t len_Y=" + dy.length + "\n#" );

        return sb.toString();
    };



}
