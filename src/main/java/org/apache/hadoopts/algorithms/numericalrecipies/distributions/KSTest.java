/**
 *
 * 
 * 
 */
package org.apache.hadoopts.algorithms.numericalrecipies.distributions;

import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest;
import org.apache.hadoopts.app.utils.Executor;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author kamir
 */
public class KSTest {
   
    /**
     * 
     * @param F1 - a set of double values
     * @param F2 - a second set double values
     * 
     * @param fw - a writer to log intermediate results.
     * @param min - ...
     * @param max - ...
     *
     * @return a double value
     *
     * @throws IOException 
     */
    private static double twoSideKSTest( Vector<Double> F1, 
                                         Vector<Double> F2, 
                                         Writer fw, 
                                         double min, 
                                         double max ) throws IOException { 
        
        return twoSideKSTest( F1, F2, fw, 2, "DEFAULT", min, max );
    
    }

    /**
     * Returns the p value of the two sided test and writes the analysis results
     * into an Output-Collector. 
     * 
     * @param F1 ...
     * @param F2 ...
     * @param sides ...
     * @param typ ...
     * @param min ...
     * @param max ...
     *
     * @return ...
     *
     */
    public static double twoSideKSTest( Vector<Double> F1, 
                                         Vector<Double> F2, 
                                         Writer fw, 
                                         int sides, 
                                         String typ, 
                                         double min, 
                                         double max ) throws IOException {
        double p = 1.0;
        
        if ( fw == null ) fw = new PrintWriter( System.out );

        int N1 = F1.size();
        int N2 = F2.size();
        
        Double[] di1n = new Double[N1];
        F1.copyInto( di1n );

        Double[] di2n = new Double[N2];
        F2.copyInto( di2n );

        String q = "[-]";
        String type = "-";
        if ( typ != null ) type = typ;


        if( N1==0 ) {
            String line = String.format("%s\t%5d\t%5d\t%9.2e\t%s\t%5f\t%5f\t%s\n", typ, N1, N2, p, q ,min, max,typ);
            fw.write( line );
            System.out.println( line );
            return p;
        } 
        if( N2==0 ) {
            String line = String.format("%s\t%5d\t%5d\t%9.2e\t%s\t%5f\t%5f\t%s\n", typ, N1, N2, p, q ,min, max,typ);
            fw.write( line );
            System.out.println( line );
            return p;
        }


        int j1 = 0;
        int j2 = 0;
        
        double vF1 = 0.0;
        double vF2 = 0.0;
        double dist = 0.0;
        
        
        
//        while (!(j1>N1) || !(j2>N2))	// calculate KS test difference
        while (j1 <= N1-1 && j2 <= N2-1)	// calculate KS test difference
        {  
           // System.out.println("j1=" + j1 + "  j2=" + j2 ); 
           
           if (j1 < N1 && di1n[j1] <= di2n[j2]) {
              j1++;
              vF1 = (double)j1/(double)N1;
           }   
           if (j1 < N1 && di1n[j1] >= di2n[j2]) {
              j2++; 
              vF2 = (double)j2/(double)N2;
           }
           if (Math.abs(vF1-vF2) > dist)
              dist = Math.abs(vF1-vF2);
           
           if ( j1 >= N1 ) break;
           if ( j2 >= N2 ) break;
           
        }
        
        
//        /**
//         * 
//         * find the supremum ...  
//         */
//        while( j1 < N1 && j2 < N2 ) { 
//           
////            if ( F1.elementAt(j1) <= F2.elementAt(j1) ) vF1 = j1++/N1;
////            if ( F1.elementAt(j1) >= F2.elementAt(j1) ) vF2 = j2++/N2;
//            vF1 = F1.elementAt(j1);
//            vF2 = F2.elementAt(j2);
//            
//            j1++;
//            j2++;
//            
//            if ( Math.abs(vF1-vF2) > dist)
//              dist = Math.abs(vF1-vF2);
//    
//            System.out.println( "j1=" + j1 + " " + "j2=" + j2 + " " + "> SUPREMUM=" + dist );
//        }
        
        System.out.println( "> SUPREMUM=" + dist );
        
        //calculate KS test p-value
        double N = Math.sqrt((double)N1*(double)N2/(double)(N1+N2)); 
        double a = (N+0.12+0.11/N)*dist;
        a = -2.0*a*a;
        double b = 2.0;
        double pval=0.0;
        double termbf=0.0;
        
        int i = 1;
        double term = 0.0;
        for ( i=1; i<100; i++) {  
           double ii = (double)i * 1.0;
           if (a*ii*ii > -500)
              term = b * Math.exp(a*ii*ii);
           else
              term = 0.0;
           pval += term;
           if (Math.abs(term) <= 0.001*termbf || Math.abs(term) <= 1e-8*pval)
              i=102;
           b = -b;
           termbf = Math.abs(term);
        }

        if (sides==1)  pval = pval * 0.5;
        
        if (i<101) pval = 1.0;

        // #   printf("KS test with N1=%5d, N2=%5d data points yields p-value=%9.2e\n", N1, N2, pval);


        if ( pval < 0.03 ) q = "[o]";
        if ( pval < 0.01 ) q = "[+]";
        if ( pval < 0.001 ) q = "[#]";

        String line = String.format("%s\t%5d\t%5d\t%9.2e\t%s\t%5f\t%5f\t%s\n", typ, N1, N2, pval,q ,min, max,typ);
        fw.write( line );
        
        System.out.println( line );
        
        return pval;
    }
    
    public static double twoSideKSTest( KSTestData d, Writer fw ) throws IOException {
        d.filterAndSort();
        return twoSideKSTest( d.vec1, d.vec2, fw, d.min, d.max );
    }
    
    
    public static double delta_mu = 0.0;
    public static double delta_sigma = 0.0;


    // we use generated random sample data ...
    public static void runWithSampleData(int z, double min, double max, String label, String run ) throws IOException {

        String logfile_name = KSTest.class.getName();
        String foldername = "./data/out/NRCTESTS/";
        
        File folder = new File( foldername );
        if (!folder.exists() ) folder.mkdirs();
        
        FileWriter fwLOG = new FileWriter( "./data/out/NRCTESTS/" +  logfile_name + "-LOG-" + label + ".txt" );
        FileWriter fwDAT = new FileWriter( "./data/out/NRCTESTS/" +  logfile_name + "-DUMP-DATA-" + label + ".tsv" );
        
        String awkfile = "./data/out/NRCTESTS/" +  logfile_name + "-DUMP-DATA-" + label + "-awk.csv";
        FileWriter fwAWKDATA = new FileWriter( awkfile );

        try {

            String resultLine = ""; 
                    
            //
            // produce some test data and define boundaries
            //
            long seed = 1;
                        
            KSTestData test1 = KSTestData.getRandomSamples( z, seed, delta_mu, delta_sigma );  

            test1.min = min;
            test1.max = max;

            //
            // Test implementation 1
            //
            KolmogorovSmirnovTest refTest = new KolmogorovSmirnovTest();
            double pRef = refTest.kolmogorovSmirnovTest(test1.F1, test1.F2);
            double DRef = refTest.kolmogorovSmirnovStatistic(test1.F1, test1.F2);
            
            String vACM3 = ">>> p-value (Apache Commons Math impl.) \n  p=" + pRef;
            vACM3 = vACM3 + "\n>>> D-value (Apache Commons Math impl.) \n  D=" + DRef;
            resultLine = resultLine.concat(""+pRef);
            
            //
            // Test implementation 2
            //
            double pNRC = KSTest.twoSideKSTest( test1, null );
            String vNRC = ">>> p-value (NRC implementation) \n  p=" + pNRC;
            resultLine = resultLine.concat("\t"+pNRC);
            
            
            System.out.println( vNRC + "\n" );
            System.out.println( vACM3 + "\n" );

            fwLOG.write(  vNRC + "\n\n" );
            fwLOG.write(  vACM3 + "\n\n");
            
            test1.dumpRawData( folder.getAbsolutePath(), z + "_" + min + "_" + max + "_testdata.tsv" );
            String fnRAWSERIES = foldername + "dump-raw/" + z + "_" + min + "_" + max + "_testdata.tsv";
            
            //------------------------------------------------------------------
            //
            // Describe the distributions in more detail ...
            // 
            //------------------------------------------------------------------
            // test1.dumpDistributionData( folder.getAbsolutePath(), z + "_" + min + "_" + max + "_histogramdata.tsv" );
            // test1.dumpDistributionProperties( test1.F1, fwLOG, z + "_" + min + "_" + max + "_F1_distr_test.rdf" );
            // test1.dumpDistributionProperties( test1.F2, fwLOG, z + "_" + min + "_" + max + "_F2_distr_test.rdf" );
           
            fwLOG.flush();
            fwLOG.close();
            
            fwDAT.close();
            fwAWKDATA.close();
            
            //
            // Test Implementation 3
            //
            // run the awk Script 
            //
            String awkScriptFolder = "./KS-TEST/gawk/";
            
            String cmd1 = "sed s/,/./g " + fnRAWSERIES + " | gawk -f " + awkScriptFolder + "kstest.awk -v min=-100.0 -v max=100 -v typ=default > " + logfile_name + "_" + label + "_" + run + ".txt" ;
            String cmd2 = "sed s/,/./g " + fnRAWSERIES + " | gawk -f " + awkScriptFolder + "kstest.awk -v min=-100.0 -v max=100 -v typ=default";
            String cmd3 = "sed s/,/./g " + fnRAWSERIES + " > /opt/tmp.dat";
            String cmd5 = "/usr/local/bin/gawk -f " + awkScriptFolder + "kstest.awk -v min=-100.0 -v max=100 -v typ=default /opt/tmp.dat";
 
            String cmd6 = awkScriptFolder + "run.sh " + fnRAWSERIES + " " + awkScriptFolder;
 
            String cmd4 = "ls -s";

            System.out.println( cmd6 );
            double pValAWK = Double.parseDouble( Executor.execute(cmd6 , 0) );
            String vNRCAWK = "\n>>> p-value (NRC implementation in AWK) \n  p=" + pValAWK;
            resultLine = resultLine.concat("\t"+pValAWK);
            
            resultLine = resultLine.concat("\t\t\t\t\t\t\t"+delta_mu);
            resultLine = resultLine.concat("\t"+delta_sigma);
            resultLine = resultLine.replace('.', ',');
            
           
            System.out.println( vNRCAWK );
            
            //
            // Test implementation 4
            //
            // run R script
            //
            System.out.println( fnRAWSERIES ); 
            
            System.out.println( resultLine );
            StringSelection selection = new StringSelection(resultLine);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
            
        } 
        catch (IOException ex) {
            Logger.getLogger(KSTest.class.getName()).log(Level.SEVERE, null, ex);
        }
    
    }

  
    
 

}

