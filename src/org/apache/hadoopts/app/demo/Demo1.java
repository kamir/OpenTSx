package org.apache.hadoopts.app.demo;

import org.apache.hadoopts.data.RNGWrapper;
import org.apache.hadoopts.data.export.MesswertTabelle;
import org.apache.hadoopts.data.export.OriginProject;
import org.apache.hadoopts.data.series.TimeSeriesObject;

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
        op.initFolder("dump");
  
        
        MesswertTabelle mwt = new MesswertTabelle();
        mwt.setLabel("name");
        mwt.addMessreihe( mr );
        
        Vector<TimeSeriesObject> vec = new Vector<TimeSeriesObject>();
        vec.add(mr);
        
        op.storeMesswertTabelle(mwt);
        op.storeChart(vec, true, "name", "name");
            
    };
}
