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
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.TransformType;

/**
 *
 * @author kamir
 */
public class TSInspection {

    static StringBuffer log = null;

    static Vector<Messreihe> testsA = null;
    static Vector<Messreihe> testsB = null;
    static Vector<Messreihe> testsA1 = null;
    static Vector<Messreihe> testsB1 = null;
    static Vector<Messreihe> testsC = null;

    public static String PATTERN = "\\W+";

    /**
     * SamplingRate SR;
     *
     * defines the number of samples per unit of time (usually seconds)
     */
    static double samplingRate = 5000.0;

    public static void main(String[] args) throws Exception {

        stdlib.StdRandom.initRandomGen(1);

        testsA = new Vector<Messreihe>();
        testsB = new Vector<Messreihe>();

        testsA1 = new Vector<Messreihe>();
        testsB1 = new Vector<Messreihe>();

        testsC = new Vector<Messreihe>();

        log = new StringBuffer();

        // some sample frequencies ...
        double[] fs = {10.0, 20.0, 50.0, 117.0, 1000}; //, 2000, 5000 };  

        Messreihe total = null;

        for (double f : fs) {

//            double totaltime = 13.1072; // s
//
//            double ampl = 1.0;
//
//            MessreiheFFT mr = TSInspection.getSinusWave(f, totaltime, samplingRate, ampl);
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

        // processMessreihe( MessreiheFFT.convertToMessreiheFFT(total) );
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
//        Messreihe m3 = FFTPhaseRandomizer.getRandomRow( N );
// 
//        // phase manipulation
//        Messreihe mA = FFTPhaseRandomizer.getPhaseRandomizedRow(m3.copy(), false, false, 0, FFTPhaseRandomizer.MODE_shuffle_phase);
//        Messreihe mB = FFTPhaseRandomizer.getPhaseRandomizedRow(m3.copy(), false, false, 0, FFTPhaseRandomizer.MODE_multiply_phase_with_random_value );
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
        public static MessreiheFFT getSinusWave(double f, double time, double samplingRate, double a) {
        MessreiheFFT mr = new MessreiheFFT();
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

    public static MessreiheFFT getSinusWave(double f, double time, double samplingRate, double a, double p) {
        MessreiheFFT mr = new MessreiheFFT();
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

    public static MessreiheFFT getSinusWave(double f, double time, double samplingRate, double a, double p, double noice) {
        MessreiheFFT mr = new MessreiheFFT();
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

    static Messreihe getSinusWave(Messreihe mr, Messreihe mr0, Messreihe mr1, Messreihe mr2) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public static Messreihe getWordLengthSeries(File f) throws IOException {
        Messreihe mr = new Messreihe();
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

    public static Messreihe[] getGrayImageSeries(File imageFile) throws IOException {

        Vector<Messreihe> vmr = new Vector<Messreihe>();

        BufferedImage image = ImageIO.read(imageFile);

        int zSeries = image.getHeight();

        int lSeries = image.getWidth();

        for (int z = 0; z < zSeries; z++) {
            Messreihe mr = new Messreihe();

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

        Messreihe[] MRS = new Messreihe[vmr.size()];
        MRS = vmr.toArray(MRS);
        return MRS;
    }

    public static Messreihe getTFIDFSeries(File f, Hashtable<String, Integer> idfs, Hashtable<String, Integer> wc, String text) {

        Messreihe mr = new Messreihe();
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

    private static void processMessreihe(MessreiheFFT mr) {
        
        // raw data
        testsA.add(mr);

        // FFT( raw data )
        Messreihe mrFFT = MessreiheFFT.calcFFT(mr, samplingRate, TransformType.FORWARD );
        testsB.add(mrFFT);

        // FFT_INV( FFT( raw data )) => OBWOHL DAS RESULTAT NICHT IDENTISCH IST, IS
        // DIE FUNKTION KORREKT, MAN DARF SIE NUR NICHT ALS KETTE AUSFÜHREN.
        // 
        Messreihe mrBACK = MessreiheFFT.calcFFT( mrFFT, samplingRate, TransformType.INVERSE);
        testsA1.add(mrBACK);

        // FFT( FFT_INV( FFT( raw data )))
        testsB1.add(MessreiheFFT.calcFFT( mrBACK, samplingRate, TransformType.FORWARD));
        
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
        
        Vector<Messreihe> vmr1 = Messreihe.fromComplex( d3a, "INV-TRANS( FFT (raw data))", 0 );

        // FFT_INV( FFT ( COMPLEX(raw data) ))
        testsC.addAll(vmr1); 
       
    }

    private static void testPhaseManipulationTransformA() throws Exception {

        Vector<Messreihe> vmr = new Vector<Messreihe>();
        
        int N = (int)Math.pow(2, 16);
        
        Messreihe gr = Messreihe.getGaussianDistribution(N);
        Messreihe sinus = testsA.elementAt(3).copy().cut(N);
        
        Messreihe mr2 = LongTermCorrelationSeriesGenerator.getRandomRow( gr.copy(), -2, true, true );
        Messreihe mr3 = LongTermCorrelationSeriesGenerator.getRandomRow( sinus.copy(), -2, true, true );

        vmr.add(gr);
//        vmr.add(sinus);
        
        vmr.add(mr2);
//        vmr.add(mr3);
        
        
        MultiChart.open(vmr, "Phase Manipulation", "t", "y", true, "beta=1.5");
        
    }

}
