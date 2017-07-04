/*
 * This Generator creates Time-Series for functional tests of the
 * Hadoop.TS software packages.
 * 
 *   - Sin / Cos wave ...
 *      
 * 
 */
package org.apache.hadoopts.app.thesis;

import org.apache.hadoopts.data.series.Messreihe;
import java.util.Vector;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.MessreiheFFT;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Hashtable;
import javax.imageio.ImageIO;
import org.apache.commons.math3.transform.TransformType;

/**
 *
 * @author kamir
 */
public class TSGeneratorFINAL {
    
    static StringBuffer log = null;
    
    static Vector<Messreihe> testsA = null;
    static Vector<Messreihe> testsB = null;
    static Vector<Messreihe> testsC = null;
    
    public static String PATTERN = "\\W+";
    
    /** 
     * 
     * Test some sinus waves with FFT 
     *
     */
    public static void main( String[] args ) throws Exception { 
        
        // never forget !!!
        stdlib.StdRandom.initRandomGen(1);
        
        testsA = new Vector<Messreihe>();
        testsB = new Vector<Messreihe>();
        testsC = new Vector<Messreihe>();
        
        log = new StringBuffer();
    
        // some sample frequencies ...
        double[] fs = { 1.0, 1.0,1.0 }; // , 20.0, 50.0, 117.0, 1000 }; //, 2000, 5000 };  
        
        /**
         * SamplingRate SR;
         * 
         *         defines the number of samples per unit of time (usually seconds)
         */
        double samplingRate = 10000.0;
        
        int i = 0;
        for( double f : fs ) {
            i++;
            double totaltime = 16.384; // s
            double ampl = 1.0;
                        
            MessreiheFFT mr = TSGeneratorFINAL.getSinusWave(f, totaltime, samplingRate, ampl / i, 1, 0.1);
            testsA.add(mr);
            
            /**
             * 
             * 
             * WARNING TERMS NOT ACCURATE !!! 
             * 
             *             // http://de.sci.mathematik.narkive.com/gfJHegzO/frequenz-aus-fft-fourier-transform-bestimmen

Zuerst musst du mal den Nullpunkt der FF-transforierten Daten wissen.
Eine FFT produziert aus n Werten wieder n Werte, wobei n/2 Werte für
die Frequenzen von 0 bis zur halben Sampling-Frequenz (SF) stehen. Die
andere Hälfte ist meistens oben dran zu finden (von halber Sampling-
Frequenz bis zur Samplingfrequenz) oder unten dran (von minus halber
Sampling-Frequenz bis 0) oder ist gar nicht dargestellt, weil sowieso
redundant (= gespiegelt).

Dann steht der k-te Wert rechts vom Nullpunkt für die Frequenz k/n mal
Samplingfrequenz. Die Samplingfrequenz (in Hz) ist 1 durch die
Samplingrate. Und die Samplingrate ist die Zeit zwischen zwei
aufeinanderfolgenden Werten im Zeitbereich, oder die Länge des Signals
in Sekunden dividiert durch n. Die Wellenlänge ist dann c durch die
Samplingfrequenz, oder eingesetzt n/k mal Samplingrate mal c.
             **/
            
//            Messreihe mrFFT = new Messreihe(); 
//            MessreiheFFT mrFFT2 = new MessreiheFFT(); 
//
//            mrFFT2.calcFFT( mr, samplingRate, TransformType.FORWARD);
//            
//            testsB.add( mrFFT );


            
        }
        
        boolean showLegend = true;
//        MultiChart.open(testsA, "raw data", 
//                        "t [s]", "y(t)", showLegend, log.toString() );
        
//        MultiChart.open(testsB, "FFT tests", 
//                        "f [Hz]", "c", showLegend, log.toString() );
    
        
        
        KreuzKorreltion k1 = new KreuzKorrelation();
        
        
        
                // simple randomisation
//        int N = (int)Math.pow(2,16);
//        
//        Messreihe m22 = FFTPhaseRandomizer.getRandomRow( N );
// 
//        // phase manipulation
//        //Messreihe mA = FFTPhaseRandomizer.getPhaseRandomizedRow(m22.copy(), false, false, 0, FFTPhaseRandomizer.MODE_shuffle_phase);
//        Messreihe mB = FFTPhaseRandomizer.getPhaseRandomizedRow(m22.copy(), false, false, 0, FFTPhaseRandomizer.MODE_multiply_phase_with_random_value );
//        
//
//        //testsC.add( mA );
//        testsC.add( mB );
        
        // MultiChart.open(testsC, "Phase Randomization Tests", "t", "y", showLegend, log.toString(), null );
        MultiChart.setSmallFont();
        MultiChart.open(testsA, "RAW SERIES ", "t", "y", showLegend, log.toString(), null );
        
        
        
    };
    
    /**
     * 
     * totaltime         = Laenge in sekunden
     * samplingRate = anzahl werte pro Sekunde
     * 
     * f            = Frequenz
     * a            = Amplitude
     */
        public static MessreiheFFT getSinusWave(double f, double time, double samplingRate, double a ) {
        MessreiheFFT mr = new MessreiheFFT();
        int steps = (int)(time * samplingRate);
                
        mr.setLabel("N="+ steps + " (SR="+ samplingRate +" Hz, f="+ f +" Hz)");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        
        double dt = 1.0 / samplingRate; // s
        
        for( int j = 0 ; j<steps; j++ ) {
            double t = j * dt;  // sekunden
            mr.addValuePair( t , a * Math.sin( 2.0 * Math.PI * f * t ) );
        }
        return mr;
    }
    
        public static MessreiheFFT getSinusWave(double f, double time, double samplingRate, double a, double p ) {
        MessreiheFFT mr = new MessreiheFFT();
        int steps = (int)(time * samplingRate);
                
        mr.setLabel("N="+ steps + " (SR="+ samplingRate +" Hz, f="+ f +" Hz)");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        
        double dt = 1.0 / samplingRate; // s
        
        for( int j = 0 ; j<steps; j++ ) {
            double t = j * dt;  // sekunden
            mr.addValuePair( t , a * Math.sin( 2.0 * ( Math.PI * f * t + p ) ) );
        }
        return mr;
    }
    
    public static MessreiheFFT getSinusWave(double f, double time, double samplingRate, double a, double p, double noice ) {
        MessreiheFFT mr = new MessreiheFFT();
        int steps = (int)(time * samplingRate);
                
        mr.setLabel("N="+ steps + " (SR="+ samplingRate +" Hz, f="+ f +" Hz)");
        mr.setDecimalFomrmatX( "0.00000" );
        mr.setDecimalFomrmatY( "0.00000" );
        
        double dt = 1.0 / samplingRate; // s
        
        for( int j = 0 ; j<steps; j++ ) {
            
            double t = j * dt;  // sekunden
            
            double sinePART = Math.sin( 2.0 * ( Math.PI * f * t + p ) );
            double noisePART = (-1.0+(Math.random() * 2.0)) * noice;
            
            mr.addValuePair( t , a * ( sinePART + noisePART ) );
            
        }
        return mr;
    }

    static Messreihe getSinusWave(Messreihe mr, Messreihe mr0, Messreihe mr1, Messreihe mr2) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public static Messreihe getWordLengthSeries(File f) throws IOException {
        Messreihe mr = new Messreihe();
        mr.setLabel(f.getAbsolutePath());
        BufferedReader br = new BufferedReader( new FileReader( f ) );
        while( br.ready() ) {
            String line = br.readLine();
            String[] words = line.split( PATTERN );
            for( String w : words)
                mr.addValue( w.length() );
        }
        return mr;
    }

    public static Messreihe[] getGrayImageSeries(File imageFile) throws IOException {
        
        Vector<Messreihe> vmr = new Vector<Messreihe>();

        BufferedImage image = ImageIO.read(imageFile);
        
        int zSeries = image.getHeight();
       
        int lSeries = image.getWidth();
        
        for( int z = 0; z < zSeries; z++ ) {
            Messreihe mr = new Messreihe();
            
            mr.setLabel( imageFile.getAbsolutePath() + "_line_" + z );
            
            for( int t = 0; t < lSeries; t++ ){
                
               Color c = new Color(image.getRGB(t, z));

               int red = (int)(c.getRed() * 0.299);
               int green = (int)(c.getGreen() * 0.587);
               int blue = (int)(c.getBlue() *0.114);
               
                double v = red+green+blue;
                mr.addValuePair(1.0 * t, v );
                //System.out.println( "zeile(" + z +") " + t + " => " +v );

            }
            vmr.add(mr);
        }
    
        Messreihe[] MRS = new Messreihe[vmr.size()];
        MRS = vmr.toArray(MRS); 
        return MRS;
    }

    public static Messreihe getTFIDFSeries(File f, Hashtable<String, Integer> idfs, Hashtable<String, Integer> wc, String text) {

        Messreihe mr = new Messreihe();
        mr.setLabel( f.getAbsolutePath() );
 
        int i = 0;
        for( String s : text.split( PATTERN ) ){

            double tf = wc.get(s);
            
            /// System.out.println( "{" + s + "} " + tf );
            
            double idf = idfs.get(s);
            
            mr.addValuePair(i, tf / idf );
            
            i++;
        }
        return mr;
    }
    
}
