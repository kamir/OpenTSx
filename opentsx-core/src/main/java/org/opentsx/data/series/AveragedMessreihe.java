/**
 * Splits the x axis between min and max into n ranges. The values in a range
 * are averaged into one point P'(x',y').
 *
 * With a line, separate curves are computed for the points above and below
 * the line, so there are the series:
 *
 *     - the original TimeSeriesObject mr
 * (    - X errors  )
 * (    - Y errors  )
 */
package org.opentsx.data.series;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Enumeration;

/**
 *
 * @author kamir
 */
public class AveragedMessreihe {

    private static final Logger LOG = LoggerFactory.getLogger(AveragedMessreihe.class);
    
    public TimeSeriesObject mrLAST = null;
    public TimeSeriesObject mrOriginal = null;
    
    public TimeSeriesObject mrBinned[] = new TimeSeriesObject[3];
    
    double xMin = 0.0;
    double xMax = 5000.0;
    
    int steps = 150 - 1;
    
    double[][] vX = null;
    double[][] vY = null;
    
    double[][] vX_carrier = null;
    double[][] vY_carrier = null;
    
    double[][] zX = null;
     
    double dx = 0;
    

        
    public AveragedMessreihe( TimeSeriesObject mr ) {
        
        mrOriginal = mr; // the split above and below always refers to this series
        
        mrBinned = new TimeSeriesObject[3];
        
        String l = mr.getLabel();
        mrBinned[0] = new TimeSeriesObject( l + " all");
        mrBinned[1] = new TimeSeriesObject( l + " o");
        mrBinned[2] = new TimeSeriesObject( l + " u");
    };
    
    public void initSegements( int anz , TimeSeriesObject toSplit ) {
                        
        
        this.steps = anz - 1;
        
        vX = new double[ steps ][3];        
        vY = new double[ steps ][3];
        
        vX_carrier = new double[ steps ][3];        
        vY_carrier = new double[ steps ][3];
        
        zX = new double[ steps ][3]; 
        dx = xMax / steps;        
        
        double offset = dx / 2.0; 
              
        int ouIndex = 0;
        int i = 0;
        
        double xc = 0.0;
        double yc = 0.0;
            
        Enumeration en = mrOriginal.xValues.elements();
        while( en.hasMoreElements() ) { 
            
            double x = (Double)en.nextElement();
            double y = (Double)mrOriginal.yValues.get(i);
            
            xc = (Double)toSplit.xValues.elementAt(i);
            yc = (Double)toSplit.yValues.elementAt(i);
            
            // the range of the value
            int j = (int)( x / dx );
            
            if ( isOben(x, y) ) {
                ouIndex = 1;
            }
            if ( isUnten(x, y) ) {
                ouIndex = 2;
            }
            if ( j < 0 || j >= vY.length ) {
                LOG.debug("skipped value outside the ranges series={} x={} ranges={}", mrOriginal.getLabel(), x, vY.length);
            }
            else {
                vY[j][ouIndex] = vY[j][ouIndex] + yc;            
                zX[j][ouIndex] = zX[j][ouIndex] + 1;
                
                vY[j][0] = vY[j][0] + yc;            
                zX[j][0] = zX[j][0] + 1;
            }
            i++;
        }
        double y = 0;
        
        for( int k = 0; k < steps;k++ ) { 
           
           y = vY[k][0];
           mrBinned[0].addValuePair( (k + 0.5) * dx , ( y / zX[k][0])   );    
           
           y = vY[k][1];
           mrBinned[1].addValuePair( (k + 0.5) * dx , ( y / zX[k][1])   );
           
           y = vY[k][2];
           mrBinned[2].addValuePair( (k + 0.5) * dx , ( y / zX[k][2])   );
                
        }
        
    };
    
    


    private boolean isUnten(double x, double y) {
        double ySoll = yX_double( (double)x );
        if ( y >= ySoll ) return true;
        else return false;
    }

    private boolean isOben(double x, double y) {
        double ySoll = yX_double( (double)x );
        if ( y < ySoll ) return true;
        else return false;
    }    
    
    public static double yX_double(double X) {
        return (m * X + (1.0*n) );
    }
    
    static int n = 0;
    static double m = 0;
    public static void _setGerade( int x1, int y1,int x2,int y2) { 
        n = y1;
        m = (1.0*(y2-y1))/(1.0*(x2-x1));
    };
    public static int yX(int X) {
        return (int) (m * X + n);
    }

    public TimeSeriesObject[] getNrOfValuesPerBin() {
        
        TimeSeriesObject[] mr = new TimeSeriesObject[3];
        
        mr[0] = new TimeSeriesObject();
        mr[1] = new TimeSeriesObject();
        mr[2] = new TimeSeriesObject();
        
        for( int k = 0; k < steps;k++ ) { 
            for( int k1 = 0; k1 < 3; k1++ ) {
               double z = (double)zX[k][k1];
               mr[k1].addValuePair( (k + 0.5) * dx , z );        
            }
        }   
        return mr;
         
    }
    




   
    
}
