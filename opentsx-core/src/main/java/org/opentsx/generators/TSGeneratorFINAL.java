/*
 * This Generator creates Time-Series for functional tests of the
 * Hadoop.TS software packages.
 * 
 *   - Sin / Cos wave ...
 *      
 * 
 */
package org.opentsx.generators;

import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class TSGeneratorFINAL {
    
    static StringBuffer log = null;
    
    static Vector<TimeSeriesObject> testsA = null;
    static Vector<TimeSeriesObject> testsB = null;
    static Vector<TimeSeriesObject> testsC = null;
    
    public static String PATTERN = "\\W+";
    
    /** 
     * 
     * Test some sinus waves with FFT 
     *
     */
    public static void main( String[] args ) throws Exception { 
        
        RNGWrapper.init();

        testsA = new Vector<TimeSeriesObject>();
        testsB = new Vector<TimeSeriesObject>();
        testsC = new Vector<TimeSeriesObject>();
        
        log = new StringBuffer();
    
        // some sample frequencies ...
        double[] fs = { 1.0, 1.0, 1.0 };
        
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
                        
            TimeSeriesObjectFFT mr = TSGeneratorFINAL.getSinusWave(f, totaltime, samplingRate, ampl / i, 1, 0.1);
            testsA.add(mr);
            
            /*
             * The terms below are not exact. Source:
             * http://de.sci.mathematik.narkive.com/gfJHegzO/frequenz-aus-fft-fourier-transform-bestimmen
             *
             * Find the zero point of the transformed data first. An FFT turns
             * n values into n values; n/2 of them stand for the frequencies
             * from 0 to half the sampling frequency. The other half covers
             * half the sampling frequency to the sampling frequency, or minus
             * half the sampling frequency to 0, or is left out because it
             * mirrors the first half.
             *
             * The k-th value right of the zero point stands for the frequency
             * k/n times the sampling frequency. The sampling frequency in Hz is
             * 1 divided by the sampling interval, the time between two
             * consecutive values, or the length of the signal in seconds
             * divided by n. The wavelength is c divided by that frequency,
             * that is n/k times the sampling interval times c.
             */
            


            
        }
        
        boolean showLegend = true;
        
    
        
        
        
        
        
                // simple randomisation
        
        MultiChart.setSmallFont();
        MultiChart.open(testsA, "RAW SERIES ", "t", "y", showLegend, log.toString(), null );
        
        
        
    };
    
    /**
     * time         = length in seconds
     * samplingRate = values per second
     * f            = frequency
     * a            = amplitude
     */
        public static TimeSeriesObjectFFT getSinusWave(double f, double time, double samplingRate, double a ) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
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
    
        public static TimeSeriesObjectFFT getSinusWave(double f, double time, double samplingRate, double a, double p ) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
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

    public static Vector<TimeSeriesObject> getSampleA() {

        testsA = new Vector<TimeSeriesObject>();

        /**
         * Frequency
         *
         *          f [HZ]
         */
        double fs = 1.0;

        /**
         * SamplingRate SR;
         *
         *         defines the number of samples per unit of time (usually seconds)
         */
        double samplingRate = 44000;

        double totaltime = 1.0; //

        double ampl = 1.0;

        testsA.add( TSGeneratorFINAL.getSinusWave(fs, totaltime, samplingRate, ampl ) );
        testsA.add( TSGeneratorFINAL.getSinusWave(fs * 1000, totaltime, samplingRate, ampl* 2 ) );
        testsA.add( TSGeneratorFINAL.getSinusWave(fs * 5000, totaltime, samplingRate, ampl*0.5 ) );

        return testsA;

    }

    public static TimeSeriesObjectFFT getSinusWave(double f, double time, double samplingRate, double a, double p, double noice ) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
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

    /**
     * @param imageFile
     * @return
     * @throws IOException
     */
    public static TimeSeriesObject[] getGrayImageSeries(File imageFile) throws IOException {
        
        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();

        BufferedImage image = ImageIO.read(imageFile);
        
        int zSeries = image.getHeight();
       
        int lSeries = image.getWidth();
        
        for( int z = 0; z < zSeries; z++ ) {
            TimeSeriesObject mr = new TimeSeriesObject();
            
            mr.setLabel( imageFile.getAbsolutePath() + "_line_" + z );
            
            for( int t = 0; t < lSeries; t++ ){
                
               Color c = new Color(image.getRGB(t, z));

               int red = (int)(c.getRed() * 0.299);
               int green = (int)(c.getGreen() * 0.587);
               int blue = (int)(c.getBlue() *0.114);
               
                double v = red+green+blue;
                mr.addValuePair(1.0 * t, v );

            }
            vmr.add(mr);
        }
    
        TimeSeriesObject[] MRS = new TimeSeriesObject[vmr.size()];
        MRS = vmr.toArray(MRS); 
        return MRS;
    }

    public static TimeSeriesObject getTFIDFSeries(File f, Hashtable<String, Integer> idfs, Hashtable<String, Integer> wc, String text) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel( f.getAbsolutePath() );
 
        int i = 0;
        for( String s : text.split( PATTERN ) ){

            double tf = wc.get(s);
            
            
            double idf = idfs.get(s);
            
            mr.addValuePair(i, tf / idf );
            
            i++;
        }
        return mr;
    }
    
}
