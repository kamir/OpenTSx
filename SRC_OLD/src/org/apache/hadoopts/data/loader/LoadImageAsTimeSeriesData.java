/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.apache.hadoopts.data.loader;

import org.apache.hadoopts.app.bucketanalyser.MacroTrackerFrame;
import org.apache.hadoopts.app.bucketanalyser.TSBucketSource;
import org.apache.hadoopts.app.bucketanalyser.TSBucketTransformation;
import static org.apache.hadoopts.app.bucketanalyser.MacroRecorder.loadOp;
import org.apache.hadoopts.app.experimental.SimpleBucketTool;
import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.Messreihe;
import org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_GrayImageColor;
import org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_WordLength;
import org.apache.hadoopts.hadoopts.core.TSBucket;
import org.apache.hadoopts.hadoopts.loader.StockDataLoader2;
import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LoadImageAsTimeSeriesData {
        
    public static void main(String[] ARGS) throws IOException, InstantiationException, IllegalAccessException {

        // prepare the time series bucket ...
        org.apache.hadoopts.hadoopts.buckets.generator.TSBucketCreator_GrayImageColor.main(ARGS);
        
        
        
        
        String experiment = TSBucketCreator_GrayImageColor.experiment;
        
        MacroTrackerFrame.init("Look into an image file " + experiment + ")" );
        
        MacroTrackerFrame.addSource(TSBucketSource.getSource("ImageLines"));

        String fn = TSBucketCreator_WordLength.baseOut + "/" + experiment + "/bucket_grayImage.tsb.vec.seq";
        
        loadCorpusDataForExperiment( fn , experiment );

    }

    private static void loadCorpusDataForExperiment(String fn, String experiment) throws IOException {

        TSBucket.processSeriesLabelOnLoad = false;
        
        Vector<Messreihe> r0 = SimpleBucketTool.loadBucketData( fn );
        
        String windowName = "ImageLines (exp: " + experiment + ") ";
        
        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));

        MultiChart.open(r0, false, windowName);

    }
 
 
 
}
