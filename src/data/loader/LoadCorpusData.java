package data.loader;

import app.bucketanalyser.MacroTrackerFrame;
import app.bucketanalyser.TSBucketSource;
import app.bucketanalyser.TSBucketTransformation;
import app.experimental.SimpleBucketTool;
import chart.simple.MultiChart;
import data.series.Messreihe;
import hadoopts.buckets.generator.TSBucketCreator_TFIDFRecursive;
import hadoopts.buckets.generator.TSBucketCreator_WordLength;
import hadoopts.buckets.generator.TSBucketCreator_WordLengthRecursive;
import hadoopts.core.TSBucket;
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

        hadoopts.buckets.generator.TSBucketCreator_TFIDFRecursive.main(ARGS);
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
