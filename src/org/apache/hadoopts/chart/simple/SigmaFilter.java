package org.apache.hadoopts.chart.simple;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class SigmaFilter extends Messreihe {

    String label = null;
    boolean legend = true;
    
    /**
     * @param args the command line arguments
     */
    public static void open(Vector<Messreihe> m, String label, boolean legend ) {
        
        stdlib.StdRandom.initRandomGen(1);

        SigmaFilter sf = new SigmaFilter();
        sf.label = label;
        sf.legend = legend;

        for (Messreihe mr : m) {
            
            sf.addCollect(mr,false);
        
        }
        
        sf.aggregate();
        
        sf.plotASOszi();
    }
    
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        
        stdlib.StdRandom.initRandomGen(1);

        SigmaFilter sf = new SigmaFilter();


        for (int i = 0; i < 100; i++) {
            // one year with one point per hour ...  
            // Messreihe mr = Messreihe.getGaussianDistribution( sf.scale * 365, 10.0, 5.0 );
            Messreihe mr = Messreihe.getExpDistribution( sf.scale * 365, 5.0 );

            sf.addCollect(mr,false);
        }
        sf.aggregate();
        sf.plot();
    }
    
    int scale = 1;
    int scale2 = 1;
    
    double upperTS = 1.0;
    double lowerTS = -1.0;
    
    
    Messreihe mwRAW = null;
    Messreihe sigmaRAW = null;
    
    Messreihe mwBINNED = null;
    Messreihe sigmaBINNED = null;
    Vector<Messreihe> rows = new Vector<Messreihe>();
    Vector<Messreihe> binned = new Vector<Messreihe>();

    private void addCollect(Messreihe mr, boolean aggregateNow) {
        
        rows.add(mr);
        
        Messreihe binnedRow = mr.setBinningX_sum( scale );
        binned.add(binnedRow);
        
        if( aggregateNow ) aggregate();       
    }
    
    private void plotASOszi() {
        
        Vector<Messreihe> plotRows = new Vector();
        
        plotRows.add(mwBINNED.setBinningX_sum( scale2 ));
        
        Messreihe upper = mwBINNED.add( sigmaBINNED.scaleY_2( upperTS ));
        Messreihe lower = mwBINNED.add( sigmaBINNED.scaleY_2( lowerTS ));
         
        
        plotRows.add( upper.setBinningX_sum( scale2 ) );
        plotRows.add( lower.setBinningX_sum( scale2 ) );
        plotRows.add( sigmaBINNED );
        
//        MultiChart.yRangDEFAULT_MAX = 100;
//        MultiChart.yRangDEFAULT_MIN = -100;
//        MultiChart.setDefaultRange = true;
                
        MultiChart.open(plotRows, label, "t", "y(t) , sigma(t)", legend);
    }

    private void plot() {
        
        Vector<Messreihe> plotRows = new Vector();
        
        plotRows.add(mwBINNED.setBinningX_sum( scale2 ));
        
        Messreihe upper = mwBINNED.add( sigmaBINNED.scaleY_2( upperTS ));
        Messreihe lower = mwBINNED.add( sigmaBINNED.scaleY_2( lowerTS ));
        
        plotRows.add( upper.setBinningX_sum( scale2 ) );
        plotRows.add( lower.setBinningX_sum( scale2 ) );
        
//        MultiChart.yRangDEFAULT_MAX = 100;
//        MultiChart.yRangDEFAULT_MIN = -100;
//        MultiChart.setDefaultRange = true;
                
        MultiChart.open(plotRows, "random test data (1 year, hourly)", "t", "y(t) , sigma(t)", true);
    }

    private void aggregate() {
        
        System.out.println( ">   aggregate now ... " );  
        
        mwRAW = Messreihe.averageForAll(rows);
        mwBINNED = Messreihe.averageForAll(binned);
        
        sigmaRAW = Messreihe.sigmaForAll(rows);    
        sigmaBINNED = Messreihe.sigmaForAll(binned);    
        
        System.out.println( ">   Sigma-Band calculation DONE!" );               

    }
}
