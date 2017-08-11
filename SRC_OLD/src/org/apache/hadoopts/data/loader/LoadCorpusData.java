package org.apache.hadoopts.data.loader;

import org.apache.hadoopts.app.bucketanalyser.MacroTrackerFrame;
import org.apache.hadoopts.app.bucketanalyser.TSBucketSource;
import org.apache.hadoopts.app.bucketanalyser.TSBucketTransformation;
import org.apache.hadoopts.app.experimental.SimpleBucketTool;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_TFIDFRecursive;
import org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_WordLength;
import org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_WordLengthRecursive;
import org.apache.hadoopts.hadoopts.core.TSBucket;
import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LoadCorpusData {
        
    public static void main(String[] ARGS) throws IOException, InstantiationException, IllegalAccessException {

        // prepare the time series bucket ...
        
//        hadoopts.buckets.generator.TSBucketCreator_WordLength.main(ARGS);
//        String experiment = TSBucketCreator_WordLength.experiment;

//        hadoopts.buckets.generator.TSBucketCreator_WordLengthRecursive.main(ARGS);
//        String experiment = TSBucketCreator_WordLengthRecursive.experiment;

        org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_TFIDFRecursive.main(ARGS);
        String experiment = TSBucketCreator_TFIDFRecursive.experiment;

        
        MacroTrackerFrame.init("Some Text files " + experiment + ")" );
        
        MacroTrackerFrame.addSource(TSBucketSource.getSource("Corpus"));

        String fn = TSBucketCreator_WordLength.baseOut + "/" + experiment + "/bucket_tfidf_.tsb.vec.seq";
        
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
