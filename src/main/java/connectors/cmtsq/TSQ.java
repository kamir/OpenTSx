package connectors.cmtsq;

import com.google.gson.Gson;

/**
 * Created by kamir on 24.07.17.
 */
public class TSQ {

    String title = null;
    String tsquery = null;


    public TSQ() {
    }

    public static TSQ createTSQ_from_JSON(String json) {
        Gson gson = new Gson();
        TSQ q = gson.fromJson( json, TSQ.class );
        return q;
    }

    public String toString() {
        return "TSQ:" + title + " [" + tsquery + "]";
    }



    public TSQ( String q, String d) {
        tsquery = q;
        title = d;
    }


    // some factory methods
    public static TSQ getDefaultTSQ() {
        // a simple default query ...
        String query = "select cpu_percent";
        String title = "All time series for all metrics for DataNodes.";
        return new TSQ( query, title );
    }

    // some factory methods
    public static TSQ getTSQ_for_host(String host) {
        String q = "select * where (roleType=DATANODE or roleType=TASKTRACKER) and hostname=" + host;
        String d = "All time series for all metrics for DataNodes or TaskTrackers that are running on host named \"host\"";
        TSQ tsq = new TSQ(q,d);
        return tsq;
    }



}
