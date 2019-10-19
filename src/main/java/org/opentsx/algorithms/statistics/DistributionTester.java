/**
 *  GPL - infiziert !!!
 * 
 * 
 */
package org.opentsx.algorithms.statistics;

import org.opentsx.data.generator.RNGWrapper;

import java.io.IOException;
import java.io.Writer;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class DistributionTester {
    
    boolean debug = false;
   
    public Writer fw = null; 
    
    public static DistributionTester getDistributionTester() { 
        DistributionTester tester = new DistributionTester();
        tester.init();
        return tester;
    }
    
    public Vector<Double> getData() { 
        return t.values;
    } 
    
    ShapiroTester t = null;
    
    public void init() { 
        t =  new ShapiroTester();
    }
    
    
    
    public double[] testDataset( double[] data ) throws IOException { 
        double[] v = t.test(data);
//        if ( debug ) { 
//            System.out.println(">>> shapiro-test: " + v[0] + " " + v[1] );
//        }
        if ( fw!=null ) { 
            fw.write(">>> shapiro-test: " + v[0] + " " + v[1] + "\n" );
        }
        return v;
    }
    
    public String testDatasetS(double[] d2) throws IOException {
        double[] d = testDataset(d2);
        return d[0] + "\t" + d[1];
    }
    
    public double[] testLogDataset( double[] data ) throws IOException { 
        double[] logData = new double[ data.length ];
        int i = 0;
        for( double d : data ) {
            logData[i] = Math.log( d );
            i++;
        }
        double[] v = t.test(logData);
        if ( debug ) { 
            System.out.println(">>> shapiro-test (log): " + v[0] + " " + v[1] );
        }
        if ( fw!=null ) { 
            fw.write(">>> shapiro-test (log):" + v[0] + " " + v[1] + "\n" );
        }
        return v;
    }
        
    public static void main( String[] args ) throws IOException {

        RNGWrapper.init();

        double[] dataA = new double[150];
        double[] dataB = new double[150];
        double[] dataC = new double[150];
        double[] dataD = new double[150];
        
        // http://www.roguewave.com/Portals/0/products/imsl-numerical-libraries/java-library/docs/5.0.1/api/com/imsl/stat/NormalityTestEx1.html
        double x[] = {23.0, 36.0, 54.0, 61.0, 73.0, 23.0, 37.0, 54.0, 61.0,
        73.0, 24.0, 40.0, 56.0, 62.0, 74.0, 27.0, 42.0, 57.0, 63.0, 75.0, 29.0,
        43.0, 57.0, 64.0, 77.0, 31.0, 43.0, 58.0, 65.0, 81.0, 32.0, 44.0, 58.0,
        66.0, 87.0, 33.0, 45.0, 58.0, 68.0, 89.0, 33.0, 48.0, 58.0, 68.0, 93.0,
        35.0, 48.0, 59.0, 70.0, 97.0};
        
        for( int i = 0; i < 150; i++) { 
            dataA[i] = RNGWrapper.getStdRandomGaussian();
            dataB[i] = RNGWrapper.getStdPoisson( 0.2 );
            dataC[i] = RNGWrapper.getStdRandomPareto( 0.2 );
            dataD[i] = RNGWrapper.getStdRandomGeometric( 0.5 );
        }
        
        DistributionTester dt = DistributionTester.getDistributionTester();
        dt.testDataset(dataA);
        dt.testDataset(dataB);
        dt.testDataset(dataC);
        dt.testDataset(dataD);
        dt.testDataset(x);
        
        dt.testLogDataset(dataA);
        dt.testLogDataset(dataB);
        dt.testLogDataset(dataC);
        dt.testLogDataset(dataD);
        dt.testLogDataset(x);
        
    }


    
}
