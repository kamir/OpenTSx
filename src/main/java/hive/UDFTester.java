package hive;

import org.apache.hadoop.io.Text;
import java.util.*;
import com.google.common.collect.*;

/**
 * Created by kamir on 17.08.17.
 */
public class UDFTester {

    public static void main(String[] ARGS) {

        long t0 = System.currentTimeMillis() / 1000;
        long start = t0 - (3600);



        String[] tsRAW = {"0", "1", "2", "3", "4", "5", "6", "8", "9","10", "11","12","13" };

        String metric = "udfs_test";



        String[] ts =  {"0", "1", "2", "3", "4", "5", "6", "8", "9", "10", "11", "12", "13" };
        String[] val = {"0", "1", "2", "3", "4", "5", "6", "8", "9", "10", "11", "12", "13" };
        String[] tags = {"ID=3", "L=30" };



        // create good time stamps ...
        int i = 0;
        for( String t : tsRAW ) {
            long tsREAL = Long.parseLong( t ) * 60 + start;
            ts[i] = ""+tsREAL;

            i++;
        }



        ArrayList<String> tsList = Lists.newArrayList( Arrays.asList(   ts   ));
        ArrayList<String> valList = Lists.newArrayList( Arrays.asList(   val   ));
        ArrayList<String> tagsList = Lists.newArrayList( Arrays.asList(   tags   ));



        PersistTimeSeriesUDF udfInstance = new PersistTimeSeriesUDF();

        String dbHost = "cc-poc-mk-1.gce.cloudera.com";

//        Text result = udfInstance.evaluate(ts, val, dbHost, metric, tags);
        Text result = udfInstance.evaluate( tsList, valList, dbHost, metric, tagsList);

        System.out.println(result);

    }
}
