/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package data.loader;

import app.bucketanalyser.MacroTrackerFrame;
import app.bucketanalyser.TSBucketSource;
import app.bucketanalyser.TSBucketTransformation;
import static app.bucketanalyser.MacroRecorder.loadOp;
import app.experimental.SimpleBucketTool;
import chart.simple.MultiChart;
import data.series.Messreihe;
import hadoopts.buckets.generator.TSBucketCreator_WordLength;
import hadoopts.core.TSBucket;
import hadoopts.loader.StockDataLoader2;
import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LoadCorpusData {
        
    public static void main(String[] ARGS) throws IOException, InstantiationException, IllegalAccessException {

        // prepare the time series bucket ...
        hadoopts.buckets.generator.TSBucketCreator_WordLength.main(ARGS);
        
        String experiment = TSBucketCreator_WordLength.experiment;
        
        MacroTrackerFrame.init("Some Text files " + experiment + ")" );
        
        MacroTrackerFrame.addSource(TSBucketSource.getSource("Corpus"));

        String fn = TSBucketCreator_WordLength.baseOut + "/" + experiment + "/bucket_wordLength_.tsb.vec.seq";
        
        loadCorpusDataForExperiment( fn , experiment );

    }

    private static void loadCorpusDataForExperiment(String fn, String experiment) throws IOException {

        TSBucket.processSeriesLabelOnLoad = false;
        
        Vector<Messreihe> r0 = SimpleBucketTool.loadBucketData( fn );
        
        String windowName = "Corpus (exp: " + experiment + ") ";
        
        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));

        MultiChart.open(r0, false, windowName);

    }
 
 
 
}
