package org.opentsx.demo.brainwave;



import org.opentsx.app.bucketanalyser.MacroTrackerFrame;
import org.opentsx.app.bucketanalyser.TSBucketSource;
import org.opentsx.app.bucketanalyser.TSBucketTransformation;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.folderStore.TSBASE;
import org.opentsx.core.TSBucket;

import java.io.*;
import java.util.Vector;

/**
 * A tool for testing the Brain Signal Classification
 *
 * https://gallery.cortanaintelligence.com/Competition/Decoding-Brain-Signals-2?share=1
 *
 * @author kamir
 * 
 * Inspiration for the project: Peter Whitney, Cloudera
 * 
 * Goal: Demonstration of a use-case for time series based studies on 
 *       physiological data using Hadoop.TS3
 * 
 */
public class ModelTesterCortanaDBS {

    
    
    
    
    public static String basefolder = "/Volumes/DS-Tools/TSDB/";
    public static String exp = "P001/TSB/";
    
    public static String base = null;

    
    
    
    
    public static void main(String[] args) throws FileNotFoundException, IOException {

        MacroTrackerFrame.init("Cortana - BSD");

        // EXP 1
        String label_of_EXPERIMENT = "CN";
        String subLabel = "BSD";

        // Time Series Database
        
        base = basefolder + exp + "tsb_" + System.currentTimeMillis() + "/";
        
        File BASE = new File(base);
        BASE.mkdirs();

        TimeSeriesObject[][] fullSeries = new TimeSeriesObject[64][4];

        // CSV file name
        String CSVFN = "/Volumes/DS-Tools/DATASETS/P001/raw/ecog_train_with_labels.csv";

        FileReader fr = new FileReader(CSVFN);
        BufferedReader br = new BufferedReader(fr);

        for (int j = 0; j < 4; j++) {

            String person = base + "/p" + (j);
            
            File PERSON_im = new File(person + "/image");
            File PERSON_bl = new File(person + "/blank");
            
            PERSON_im.mkdirs();
            PERSON_bl.mkdirs();

            for (int i = 0; i < 64; i++) {

                TimeSeriesObject mr = new TimeSeriesObject();
                mr.setLabel(i + "_" + j);

                fullSeries[i][j] = mr;

            }
        }

        String header = br.readLine();
        int l = header.split(",").length;

        System.out.println(">>> Step 2");

        try {
 

            while (br.ready()) {

                String tp = br.readLine();
                String[] cells = tp.split(",");

                int j = Integer.parseInt(cells[0].substring(2, 3));

                if ( cells.length < l ) throw new Exception("short line");
                
//                System.out.println( cells[0] );
                
                for (int i = 0; i < 64; i++) {
                    fullSeries[i][j - 1].addValue(Double.parseDouble(cells[i+1]));
                }

//                if ( j > 1 ) br.close();
            }
        } catch (Exception ex) {

        };

        System.out.println(">>> Step 3");

        int PATIENT = 0;

        System.out.println(">>> length pf series: ");
        System.out.println(fullSeries[0][PATIENT].yValues.size());
        
        int eMax = 20; // nr of pairs
        int eMin = 0; // nr of pairs
        
        int length = 400;

        int PATIENT_to_show = 0;

        while (PATIENT < 4) {

            BrainState[] statesImage = new BrainState[eMax];
            BrainState[] statesBlank = new BrainState[eMax];

            for (int k = 0; k < eMax; k = k + 1) {

                // What goes to the bucket???
                statesImage[k] = new BrainState( "p" + PATIENT, k, "image");
                statesBlank[k] = new BrainState( "p" + PATIENT, k, "blank");

            }

            // go over all series with 432 snippets 
            for (int i = 0; i < 64; i++) {

                TimeSeriesObject[] snippets = fullSeries[i][PATIENT].split(length, eMax * 2);

                for (int k = 0; k < eMax; k = k + 2) {

                    int t = k/2;

                    TimeSeriesObject mr1 = snippets[k];
                    TimeSeriesObject mr2 = snippets[k+1];
                    
                    mr1.setLabel(PATIENT + "_e" + i+"_i");
                    mr2.setLabel(PATIENT + "_e" + i+"_b");
                    
                    // What goes to the bucket???
                    statesImage[t].vmr.add( mr1 );
                    statesBlank[t].vmr.add( mr2 );
                }

            }

//            if (PATIENT == PATIENT_to_show) {
                
//                statesImage[0].show();
//                statesBlank[0].show();
                
//                statesImage[0].store();
//                statesBlank[0].store();
                
                for (int k = eMin; k < eMax; k = k + 1) {
//                     What goes to the bucket???
                    statesImage[k].store();
                    statesBlank[k].store();
                }

//            }

            PATIENT++;

        }
        
        System.out.println( ">>> stored: " + BrainState.storedStates );
        System.exit(0);

    }

}

class BrainState {

    static int storedStates = 0;
    
    public BrainState(String n, int i, String c) {
        nr = i;
        patient = n;
        cat = c;
    }

    String patient = "xyz";
    String cat = "abc";
    int nr = -1;

    // images
    public Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();

    public void store() throws IOException {

        if ( vmr.size() == 0 ) return;
       
        TSBucket b = new TSBucket();
        
        String label = patient;
        
        String comment = "";
        
        String folder = ModelTesterCortanaDBS.base + label + "_" + cat;
        String fn = nr + "_raw";
        File f = new File( folder );
        f.mkdir();
        
        TSBASE.BASE_PATH = f.getAbsolutePath();
        
        System.out.println( ">>> folder: " + f.getAbsolutePath() );
        
        Vector<TimeSeriesObject> vmrNORM = new Vector<TimeSeriesObject>();
        for( TimeSeriesObject mr : vmr ) {
            vmrNORM.add( mr.normalizeToStdevIsOne() );
        }
        
        b.createBucketFromVectorOfSeries(label, cat, vmrNORM);
        MultiChart.store(vmrNORM, label, "y_NORM(t)", "t", false, folder, fn, comment);
        
        storedStates++;
        
    }
        
    public void show() {

        System.out.println(">>> Step 4");

        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("PATIENT " + nr, cat, "Vector "));

        TSBucketSource t = TSBucketSource.getSource("Vector");
        MacroTrackerFrame.addSource(t);

        BrainSignalCorrelator c1 = BrainSignalCorrelator.getInstance();

        MultiChart.openWithCorrelator(vmr, true, "PATIENT 0 - " + cat, c1);

    }

}
