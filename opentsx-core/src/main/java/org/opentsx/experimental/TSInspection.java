package org.opentsx.experimental;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.opentsx.thesis.LongTermCorrelationSeriesGenerator;
import org.semanpix.chart.simple.MultiChart;
import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.data.series.TimeSeriesObjectFFT;


import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class TSInspection {

    static StringBuffer log = null;

    static Vector<TimeSeriesObject> testsA = null;
    static Vector<TimeSeriesObject> testsB = null;
    static Vector<TimeSeriesObject> testsA1 = null;
    static Vector<TimeSeriesObject> testsB1 = null;
    static Vector<TimeSeriesObject> testsC = null;

    public static String PATTERN = "\\W+";

    /**
     * SamplingRate SR;
     *
     * defines the number of samples per unit of time (usually seconds)
     */
    static double samplingRate = 5000.0;

    public static void main(String[] args) throws Exception {


        RNGWrapper.init();


        testsA = new Vector<TimeSeriesObject>();
        testsB = new Vector<TimeSeriesObject>();

        testsA1 = new Vector<TimeSeriesObject>();
        testsB1 = new Vector<TimeSeriesObject>();

        testsC = new Vector<TimeSeriesObject>();

        log = new StringBuffer();

        // some sample frequencies ...
        double[] fs = {10.0, 20.0, 50.0, 117.0, 1000}; //, 2000, 5000 };  

        TimeSeriesObject total = null;

        for (double f : fs) {

//            double totaltime = 13.1072; // s
//
//            double ampl = 1.0;
//
//            TimeSeriesObjectFFT mr = TSInspection.getSinusWave(f, totaltime, samplingRate, ampl);
//
//            if (total == null) {
//                total = mr;
//            } else {
//                total = total.add(mr);
//            }

            /**
             * Handle individual components
             */
//            processMessreihe(mr);

        }

        // processMessreihe( TimeSeriesObjectFFT.convertToMessreiheFFT(total) );
//        
//        boolean showLegend = true;
//
//      MultiChart.open(testsA, "raw data",
//                "t [s]", "y(t)", showLegend, log.toString());
////      [OK]
//      MultiChart.open(testsB, "FFT ( raw data )",
//              "f [Hz]", "c", showLegend, log.toString());
//
//        MultiChart.open(testsA1, "FFT_INV ( FFT ( raw data ))",
//                "t [s]", "y(t)", showLegend, log.toString());
//
//        MultiChart.open(testsB1, "FFT_INV ( FFT ( raw data ))) ",
//                "f [Hz]", "c", showLegend, log.toString());

//        // simple randomisation
//        int N = (int)Math.pow(2,16);
//        TimeSeriesObject m3 = FFTPhaseRandomizer.getRandomRow( N );
// 
//        // phase manipulation
//        TimeSeriesObject mA = FFTPhaseRandomizer.getPhaseRandomizedRow(m3.copy(), false, false, 0, FFTPhaseRandomizer.MODE_shuffle_phase);
//        TimeSeriesObject mB = FFTPhaseRandomizer.getPhaseRandomizedRow(m3.copy(), false, false, 0, FFTPhaseRandomizer.MODE_multiply_phase_with_random_value );
//
//        testsC.add( mA );
//        testsC.add( mB );
//        
        
        // [OK]
//        MultiChart.open(testsC, "FFT_INV( FFT ( raw data.COMPLEX )", "t", "y", showLegend, log.toString() );
    
        testPhaseManipulationTransformA();
    
    
    }

        
    /**
     * 
     * totaltime         = Laenge in sekunden
     * samplingRate = anzahl werte pro Sekunde
     * 
     * f            = Frequenz
     * a            = Amplitude
     */
        public static TimeSeriesObjectFFT getSinusWave(double f, double time, double samplingRate, double a) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
        int steps = (int) (time * samplingRate);

        mr.setLabel("N=" + steps + " (SR=" + samplingRate + " Hz, f=" + f + " Hz)");
        mr.setDecimalFomrmatX("0.00000");
        mr.setDecimalFomrmatY("0.00000");

        double dt = 1.0 / samplingRate; // s

        for (int j = 0; j < steps; j++) {
            double t = j * dt;  // sekunden
            mr.addValuePair(t, a * Math.sin(2.0 * Math.PI * f * t));
        }
        return mr;
    }

    public static TimeSeriesObjectFFT getSinusWave(double f, double time, double samplingRate, double a, double p) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
        int steps = (int) (time * samplingRate);

        mr.setLabel("N=" + steps + " (SR=" + samplingRate + " Hz, f=" + f + " Hz)");
        mr.setDecimalFomrmatX("0.00000");
        mr.setDecimalFomrmatY("0.00000");

        double dt = 1.0 / samplingRate; // s

        for (int j = 0; j < steps; j++) {
            double t = j * dt;  // sekunden
            mr.addValuePair(t, a * Math.sin(2.0 * (Math.PI * f * t + p)));
        }
        return mr;
    }

    public static TimeSeriesObjectFFT getSinusWave(double f, double time, double samplingRate, double a, double p, double noice) {
        TimeSeriesObjectFFT mr = new TimeSeriesObjectFFT();
        int steps = (int) (time * samplingRate);

        mr.setLabel("N=" + steps + " (SR=" + samplingRate + " Hz, f=" + f + " Hz)");
        mr.setDecimalFomrmatX("0.00000");
        mr.setDecimalFomrmatY("0.00000");

        double dt = 1.0 / samplingRate; // s

        for (int j = 0; j < steps; j++) {

            double t = j * dt;  // sekunden

            double sinePART = Math.sin(2.0 * (Math.PI * f * t + p));
            double noisePART = (-1.0 + (Math.random() * 2.0)) * noice;

            mr.addValuePair(t, a * (sinePART + noisePART));

        }
        return mr;
    }

    static TimeSeriesObject getSinusWave(TimeSeriesObject mr, TimeSeriesObject mr0, TimeSeriesObject mr1, TimeSeriesObject mr2) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public static TimeSeriesObject getWordLengthSeries(File f) throws IOException {
        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(f.getAbsolutePath());
        BufferedReader br = new BufferedReader(new FileReader(f));
        while (br.ready()) {
            String line = br.readLine();
            String[] words = line.split(PATTERN);
            for (String w : words) {
                mr.addValue(w.length());
            }
        }
        return mr;
    }

    public static TimeSeriesObject[] getGrayImageSeries(File imageFile) throws IOException {

        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();

        BufferedImage image = ImageIO.read(imageFile);

        int zSeries = image.getHeight();

        int lSeries = image.getWidth();

        for (int z = 0; z < zSeries; z++) {
            TimeSeriesObject mr = new TimeSeriesObject();

            mr.setLabel(imageFile.getAbsolutePath() + "_line_" + z);

            for (int t = 0; t < lSeries; t++) {

                Color c = new Color(image.getRGB(t, z));

                int red = (int) (c.getRed() * 0.299);
                int green = (int) (c.getGreen() * 0.587);
                int blue = (int) (c.getBlue() * 0.114);

                double v = red + green + blue;
                mr.addValuePair(1.0 * t, v);
                //System.out.println( "zeile(" + z +") " + t + " => " +v );

            }
            vmr.add(mr);
        }

        TimeSeriesObject[] MRS = new TimeSeriesObject[vmr.size()];
        MRS = vmr.toArray(MRS);
        return MRS;
    }

    public static TimeSeriesObject getTFIDFSeries(File f, Hashtable<String, Integer> idfs, Hashtable<String, Integer> wc, String text) {

        TimeSeriesObject mr = new TimeSeriesObject();
        mr.setLabel(f.getAbsolutePath());

        int i = 0;
        for (String s : text.split(PATTERN)) {

            double tf = wc.get(s);

            /// System.out.println( "{" + s + "} " + tf );
            double idf = idfs.get(s);

            mr.addValuePair(i, tf / idf);

            i++;
        }
        return mr;
    }

    private static void processMessreihe(TimeSeriesObjectFFT mr) {
        
        // raw data
        testsA.add(mr);

        // FFT( raw data )
        TimeSeriesObject mrFFT = TimeSeriesObjectFFT.calcFFT(mr, samplingRate, TransformType.FORWARD );
        testsB.add(mrFFT);

        // FFT_INV( FFT( raw data )) => OBWOHL DAS RESULTAT NICHT IDENTISCH IST, IS
        // DIE FUNKTION KORREKT, MAN DARF SIE NUR NICHT ALS KETTE AUSFÜHREN.
        // 
        TimeSeriesObject mrBACK = TimeSeriesObjectFFT.calcFFT( mrFFT, samplingRate, TransformType.INVERSE);
        testsA1.add(mrBACK);

        // FFT( FFT_INV( FFT( raw data )))
        testsB1.add(TimeSeriesObjectFFT.calcFFT( mrBACK, samplingRate, TransformType.FORWARD));
        
        /**
         * 
         * Individual FFT for forward and inverse
         * 
         */
        double[] d1 = mr.getYData();
         
        FastFourierTransformer fft1 = new FastFourierTransformer( DftNormalization.STANDARD );
        FastFourierTransformer fft2 = new FastFourierTransformer( DftNormalization.STANDARD );
        
        Complex[] d2 = fft1.transform( d1, TransformType.FORWARD);
        
        Complex[] d3a = fft2.transform( d2 , TransformType.INVERSE );
        
        Vector<TimeSeriesObject> vmr1 = TimeSeriesObject.fromComplex( d3a, "INV-TRANS( FFT (raw data))", 0 );

        // FFT_INV( FFT ( COMPLEX(raw data) ))
        testsC.addAll(vmr1); 
       
    }

    private static void testPhaseManipulationTransformA() throws Exception {

        Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();
        
        int N = (int)Math.pow(2, 16);
        
        TimeSeriesObject gr = TimeSeriesObject.getGaussianDistribution(N);
        TimeSeriesObject sinus = testsA.elementAt(3).copy().cut(N);
        
        TimeSeriesObject mr2 = LongTermCorrelationSeriesGenerator.getRandomRow( gr.copy(), -2, true, true );
        TimeSeriesObject mr3 = LongTermCorrelationSeriesGenerator.getRandomRow( sinus.copy(), -2, true, true );

        vmr.add(gr);
//        vmr.add(sinus);
        
        vmr.add(mr2);
//        vmr.add(mr3);
        
        
        MultiChart.open(vmr, "Phase Manipulation", "t", "y", true, "beta=1.5", null);
        
    }

}
