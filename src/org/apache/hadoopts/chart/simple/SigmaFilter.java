package org.apache.hadoopts.chart.simple;

import java.io.IOException;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import java.util.Vector;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.export.OriginProject;

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
    public static void open(Vector<Messreihe> m, String label, boolean legend) {

        stdlib.StdRandom.initRandomGen(1);

        SigmaFilter sf = new SigmaFilter();
        sf.label = label;
        sf.legend = legend;

        for (Messreihe mr : m) {

            sf.addCollect(mr, false);

        }

        sf.aggregate();

        MultiChart.open(plotRows, label, "t", "y(t) , sigma(t)", legend);
        
//        sf.plotASOszi();
    }

    MesswertTabelle mwt1 = new MesswertTabelle();
    MesswertTabelle mwt2 = new MesswertTabelle();
    MesswertTabelle mwt3 = new MesswertTabelle();
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {

        stdlib.StdRandom.initRandomGen(1);
        
        /**
         * Export location for OriginPro integration 
         */
        OriginProject op = new OriginProject();
        op.initBaseFolder("/Users/kamir/Documents/THESIS/dissertationFINAL/main/FINAL/LATEX/semanpix/ContinuousAndEventTimeSeries");
        op.initFolder( "data2" );
        
        // The final data export goes via two tables ...
        Vector<Messreihe> topRow = new Vector<Messreihe>();

        SigmaFilter sf = new SigmaFilter();

        Messreihe mr = null;

        for (int i = 0; i < 100; i++) {
            // one year with one point per hour ...  
            mr = Messreihe.getGaussianDistribution(sf.scale * 365, 10.0, 1.0);
//            Messreihe mr = Messreihe.getExpDistribution( sf.scale * 365, 5.0 );

            sf.addCollect(mr, false);
        }

        sf.aggregate();

        sf.mwt1 = new MesswertTabelle();
        sf.mwt1.setLabel("layer1.csv");
        sf.mwt1.singleX = false;

        
        sf.mwt2 = new MesswertTabelle();
        sf.mwt2.setLabel("layer2.csv");
        sf.mwt2.singleX = false;

        sf.mwt3 = new MesswertTabelle();
        sf.mwt3.setLabel("layer3.csv");
        sf.mwt3.singleX = false;

        
        sf.compare(mr);

        sf.plot();
        
        op.storeMesswertTabelle(sf.mwt1);
        op.storeMesswertTabelle(sf.mwt2); 
        op.storeMesswertTabelle(sf.mwt3); 


        
        

    }

    int scale = 1;
    int scale2 = 7;

    double upperTS = 1.0;
    double lowerTS = -1.0;

    Messreihe mwRAW = null;
    Messreihe sigmaRAW = null;

    Messreihe mwBINNED = null;
    Messreihe sigmaBINNED = null;
    
    Vector<Messreihe> rows = new Vector<Messreihe>();
    Vector<Messreihe> binned = new Vector<Messreihe>();

    /**
     *
     * @param mr
     * @param aggregateNow
     */
    public void addCollect(Messreihe mr, boolean aggregateNow) {

        rows.add(mr);

        Messreihe binnedRow = mr.setBinningX_sum(scale);
        binned.add(binnedRow);

        if (aggregateNow) {
            aggregate();
        }
        
    }

//    private void plotASOszi() {
//        
//        Vector<Messreihe> plotRows = new Vector();
//        
//        plotRows.add(mwBINNED.setBinningX_sum( scale2 ));
//        
//        Messreihe upper = mwBINNED.add( sigmaBINNED.scaleY_2( upperTS ));
//        Messreihe lower = mwBINNED.add( sigmaBINNED.scaleY_2( lowerTS ));
//         
//        plotRows.add( upper.setBinningX_sum( scale2 ) );
//        plotRows.add( lower.setBinningX_sum( scale2 ) );
//        plotRows.add( sigmaBINNED );
//        
////        MultiChart.yRangDEFAULT_MAX = 100;
////        MultiChart.yRangDEFAULT_MIN = -100;
////        MultiChart.setDefaultRange = true;
//                
//        MultiChart.open(plotRows, label, "t", "y(t) , sigma(t)", legend);
//    }
    Messreihe compareWithBand = null;

    static Vector<Messreihe> plotRows = new Vector();

    public void plot() {

        if (compareWithBand != null) {
            plotRows.add(compareWithBand);
            mwt1.addMessreihe(compareWithBand);
        }
        
        plotRows.add(mwBINNED.setBinningX_sum(scale));

        Messreihe upper = mwBINNED.add(sigmaBINNED.scaleY_2(upperTS));
        Messreihe lower = mwBINNED.add(sigmaBINNED.scaleY_2(lowerTS));

        mwt1.addMessreihe(lower);
        mwt1.addMessreihe(upper);
        mwt1.addMessreihe(mwBINNED.setBinningX_sum(scale));
        
        if ( calcMinEvents ) {
               
            Messreihe[] mrs = getNegExtremeEvents( compareWithBand , lower );
            
            plotRows.add( mrs[0] );
            plotRows.add( mrs[1].setBinningX_sum(scale2).scaleX_2(scale2) );
            
            mwt2.addMessreihe( mrs[1] );
            mwt3.addMessreihe( mrs[1].setBinningX_sum(scale2).scaleX_2(scale2) );
        
        }
        
        if ( calcMaxEvents ) {
            
            Messreihe[] mr = getPosExtremeEvents( compareWithBand , upper );
            
            plotRows.add( mr[0] );
            plotRows.add( mr[1].setBinningX_sum(scale2).scaleX_2(scale2) );
            
            mwt2.addMessreihe( mr[1] );
            mwt3.addMessreihe( mr[1].setBinningX_sum(scale2).scaleX_2(scale2) );
        
        }

        plotRows.add(upper.setBinningX_sum(scale));
        plotRows.add(lower.setBinningX_sum(scale));

        MultiChart.yRangDEFAULT_MAX = 15;
        MultiChart.yRangDEFAULT_MIN = -5;
        MultiChart.xRangDEFAULT_MAX = 365;
        MultiChart.xRangDEFAULT_MIN = 0;
        MultiChart.setDefaultRange = true;

        MultiChart.open(plotRows, "random test data (1 year, hourly)", "t", "y(t) , sigma(t)", true);
    }

    public void aggregate() {

        System.out.println(">   aggregate now ... " + rows.size() );
        
        

        mwRAW = Messreihe.averageForAll(rows);
        mwBINNED = Messreihe.averageForAll(binned);

        sigmaRAW = Messreihe.sigmaForAll(rows);
        sigmaBINNED = Messreihe.sigmaForAll(binned);

        System.out.println(">   Sigma-Band calculation DONE!");

    }

    boolean calcMinEvents = false;
    boolean calcMaxEvents = false;
    boolean calcAggEvents = false;

    private void compare(Messreihe mr) {

        this.compareWithBand = mr;

        calcMinEvents = true;
        calcMaxEvents = true;
        calcAggEvents = true;

    }

    public Messreihe[] getPosExtremeEvents(Messreihe c, Messreihe upper) {

        Messreihe[] r = new Messreihe[2];
        Messreihe mrE = new Messreihe( );
        Messreihe mrE1 = new Messreihe( );
        r[0]=mrE;
        r[1]=mrE1;
        
        mrE.setLabel( c.label + "_POS_EXTR");
        for( int i = 0; i < upper.yValues.size(); i++ ) {
        
            double v1 = (Double)c.yValues.elementAt(i);
            double v2 = (Double)upper.yValues.elementAt(i);
            
            if ( v1 >= v2 ) {
                mrE.addValuePair( i - 0.0001 , 0);
                mrE.addValuePair( i , 1 );
                mrE.addValuePair( i + 0.0001, 0);
                
                mrE1.addValuePair( i , 1 );

            }
            else {
                mrE.addValuePair( i , 0);
            
                mrE1.addValuePair( i , 0);

            }

        }
        
        return r;
        
    }
    public Messreihe[] getNegExtremeEvents(Messreihe c, Messreihe lower) {

        Messreihe[] r = new Messreihe[2];
        Messreihe mrE = new Messreihe( );
        Messreihe mrE1 = new Messreihe( );
        r[0]=mrE;
        r[1]=mrE1;
        
        mrE.setLabel( c.label + "_NEG_EXTR");
        for( int i = 0; i < lower.yValues.size(); i++ ) {
        
            double v1 = (Double)c.yValues.elementAt(i);
            double v2 = (Double)lower.yValues.elementAt(i);
            
            if ( v1 <= v2 ) {
                mrE.addValuePair( i - 0.0001 , 0);
                mrE.addValuePair( i , -1 );
                mrE.addValuePair( i + 0.0001, 0);
                
                mrE1.addValuePair( i , -1);

            }
            else {
                mrE.addValuePair( i , 0);
                
                mrE1.addValuePair( i , 0);

            }

        }
        
        return r;
        
    }
//
//    private Messreihe getPosExtremeEvents(Messreihe c, Messreihe upper) {
//        Messreihe mrE = new Messreihe( );
//        mrE.setLabel( c.label + "_POS_EXTR");
//        for( int i = 0; i < upper.yValues.size(); i++ ) {
//        
//            double v=0.0;
//            double v1 = (Double)c.yValues.elementAt(i);
//            double v2 = (Double)upper.yValues.elementAt(i);
//            
//            if ( v1 >= v2 ) v = 1.0;
//            
//            mrE.addValuePair( i , v);
//
//            
//        }
//        
//        return mrE;
//    }
}
