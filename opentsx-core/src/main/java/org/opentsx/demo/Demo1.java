package org.opentsx.demo;

import org.opentsx.data.generator.RNGWrapper;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.exporter.OriginProject;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class Demo1 {

    public static void main( String[] args ) throws IOException {

        RNGWrapper.init();

        TimeSeriesObject mr = TimeSeriesObject.getGaussianDistribution( 50 );
        mr = mr.cutFromStart( 10 );
        
        System.out.println( mr );
        
        OriginProject op = new OriginProject();
        op.initBaseFolder("./DEMO/TS-TEST/");
        op.initSubFolder("dump");
  
        
        MeasurementTable mwt = new MeasurementTable();
        mwt.setLabel("name");
        mwt.addMessreihe( mr );
        
        Vector<TimeSeriesObject> vec = new Vector<TimeSeriesObject>();
        vec.add(mr);
        
        op.storeMeasurementTable(mwt);
        op.storeChart(vec, true, "name", "name");
            
    };
}
