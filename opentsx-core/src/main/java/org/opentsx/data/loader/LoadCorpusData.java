package org.opentsx.data.loader;

import org.opentsx.app.bucketanalyser.MacroTrackerFrame;
import org.opentsx.app.bucketanalyser.TSBucketSource;
import org.opentsx.app.bucketanalyser.TSBucketTransformation;
import org.opentsx.experimental.SimpleBucketTool;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.generator.TSBucketCreator_TFIDFRecursive;
import org.opentsx.tsbucket.generator.TSBucketCreator_WordLength;
import org.opentsx.core.TSBucket;
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

        org.opentsx.tsbucket.generator.TSBucketCreator_TFIDFRecursive.main(ARGS);
        String experiment = TSBucketCreator_TFIDFRecursive.experiment;

        
        MacroTrackerFrame.init("Some Text files " + experiment + ")" );
        
        MacroTrackerFrame.addSource(TSBucketSource.getSource("Corpus"));

        String fn = TSBucketCreator_WordLength.baseOut + "/" + experiment + "/bucket_tfidf_.tsb.vec.seq";
        
        loadCorpusDataForExperiment( fn , experiment );

    }

    private static void loadCorpusDataForExperiment(String fn, String experiment) throws IOException {

        TSBucket.processSeriesLabelOnLoad = false;
        
        Vector<TimeSeriesObject> r0 = SimpleBucketTool.loadBucketData( fn );
        
        String windowName = "Corpus (exp: " + experiment + ") ";
        
        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));

        MultiChart.open(r0, false, windowName);

    }
 
 
 
}
