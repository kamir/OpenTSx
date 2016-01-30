package data.series;

import data.export.MesswertTabelle;
import data.export.OriginProject;
import java.io.IOException;
import java.util.Vector;

/**
 *
 * @author kamir
 */
public class TimeSeries_Demonstrator {

    public static void main( String[] args ) throws IOException {
        
        stdlib.StdRandom.initRandomGen(1);
        
        Messreihe mr = Messreihe.getGaussianDistribution( 50 );
        mr = mr.cutFromStart( 10 );
        
        System.out.println( mr );
        
        OriginProject op = new OriginProject();
        op.initBaseFolder("./DEMO/TS-TEST/");
        op.initFolder("dump");
  
        
        MesswertTabelle mwt = new MesswertTabelle();
        mwt.setLabel("name");
        mwt.addMessreihe( mr );
        
        Vector<Messreihe> vec = new Vector<Messreihe>();
        vec.add(mr);
        
        op.storeMesswertTabelle(mwt);
        op.storeChart(vec, true, "name", "name");
            
    };
}
