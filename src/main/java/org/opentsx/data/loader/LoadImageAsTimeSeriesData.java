/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.opentsx.data.loader;

import org.opentsx.app.bucketanalyser.MacroTrackerFrame;
import org.opentsx.app.bucketanalyser.TSBucketSource;
import org.opentsx.app.bucketanalyser.TSBucketTransformation;
import org.opentsx.experimental.SimpleBucketTool;
import org.semanpix.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.tsbucket.generator.TSBucketCreator_GrayImageColor;
import org.opentsx.tsbucket.generator.TSBucketCreator_WordLength;
import org.opentsx.core.TSBucket;

import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class LoadImageAsTimeSeriesData {
        
    public static void main(String[] ARGS) throws IOException, InstantiationException, IllegalAccessException {

        // prepare the time series bucket ...
        org.opentsx.tsbucket.generator.TSBucketCreator_GrayImageColor.main(ARGS);
        
        
        
        
        String experiment = TSBucketCreator_GrayImageColor.experiment;
        
        MacroTrackerFrame.init("Look into an image file " + experiment + ")" );
        
        MacroTrackerFrame.addSource(TSBucketSource.getSource("ImageLines"));

        String fn = TSBucketCreator_WordLength.baseOut + "/" + experiment + "/bucket_grayImage.tsb.vec.seq";
        
        loadCorpusDataForExperiment( fn , experiment );

    }

    private static void loadCorpusDataForExperiment(String fn, String experiment) throws IOException {

        TSBucket.processSeriesLabelOnLoad = false;
        
        Vector<TimeSeriesObject> r0 = SimpleBucketTool.loadBucketData( fn );
        
        String windowName = "ImageLines (exp: " + experiment + ") ";
        
        MacroTrackerFrame.addTransformation(TSBucketTransformation.getTransformation("Collection", windowName, "LOAD"));

        MultiChart.open(r0, false, windowName);

    }
 
 
 
}
