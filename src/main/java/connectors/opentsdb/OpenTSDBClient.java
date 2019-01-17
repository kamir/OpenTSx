package connectors.opentsdb;

import org.apache.hadoopts.data.series.TimeSeriesObject;

import java.net.MalformedURLException;
import java.util.Vector;

/**
 * Created by kamir on 04.08.17.
 */
public class OpenTSDBClient {

    OpenTSDBConnector connector = null;

    public static OpenTSDBClient getClient() throws MalformedURLException {


        OpenTSDBClient client = new OpenTSDBClient();

        try {
            client.connector = new OpenTSDBConnector();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }


        return client;
    }

    public static OpenTSDBClient getClient(String host) throws MalformedURLException {


        OpenTSDBClient client = new OpenTSDBClient();

        try {
            client.connector = new OpenTSDBConnector(host);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }


        return client;
    }

    public static OpenTSDBClient getColocatedLocalClient() throws MalformedURLException {


        OpenTSDBClient client = new OpenTSDBClient();

        try {
            client.connector = new OpenTSDBConnector("127.0.0.1");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }


        return client;
    }


    public void storeMessreiheNow(TimeSeriesObject row) throws Exception {
        connector.storeMessreihe(row, connector);
    }

    public void storeMessreiheWithOffset(TimeSeriesObject row, long t0) throws Exception {
        connector.storeMessreihe(row, connector, t0);
    }

    public void storeBucketData(Vector<TimeSeriesObject> bucketData, long t0) throws Exception {
        connector.storeBucketData(bucketData, connector, t0);
    }

    public TimeSeriesObject readTimeSeriesForMetric(String metric, String range) throws Exception {
        String aggregator = "sum";
        TimeSeriesObject m = connector.readTimeSeriesForMetric(metric, aggregator, range, connector);
        return m;
    }

}