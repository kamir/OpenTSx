package org.opentsx.connectors.opentsdb;

import org.semanpix.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.TSBucket;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;


/**
 * Created by kamir on 24.06.17.
 * <p>
 * https://hc.apache.org/httpcomponents-client-ga/examples.html
 * <p>
 * Simple example for data ingestion to OpenTSDB using the REST-API.
 */
public class OpenTSDBConnector {

    public static final String VARIABLE_TEMPLATE_METRIC = "___METRIC___";
    public static final String VARIABLE_TEMPLATE_VALUE = "___VALUE___";
    public static final String VARIABLE_TEMPLATE_TS = "___TS___";
    public static final String VARIABLE_TEMPLATE_TAG_KEY = "___TAG_KEY___";
    public static final String VARIABLE_TEMPLATE_TAG_VALUE = "___TAG_VALUE___";

    public static boolean debug = false;

    // URL putUrl = new URL("http://cdsw-mk8-1.vpc.cloudera.com:4343/api/put");
    public static String OPENTSDB_HOST = "127.0.0.1";

    URL putUrl = null;
    URL queryUrl = null;

    String _eventTemplate = null;

    // String OPENTSDB_HOST = "127.0.0.1";
    Socket pingSocket = null;

    OutputStream out = null;

    public OpenTSDBConnector() throws MalformedURLException {
        putUrl = new URL("http://" + OPENTSDB_HOST + ":4242/api/put");
        queryUrl = new URL("http://" + OPENTSDB_HOST + ":4242/api/query");
        //eventTemplate = initTSToSend();
    }

    public OpenTSDBConnector(Properties props) throws MalformedURLException {
        OPENTSDB_HOST = (String) props.getProperty("opentsdb.host");
        putUrl = new URL("http://" + OPENTSDB_HOST + ":4242/api/put");
        queryUrl = new URL("http://" + OPENTSDB_HOST + ":4242/api/query");
        //eventTemplate = initTSToSend();
    }

    public OpenTSDBConnector(String host) throws MalformedURLException {
        OPENTSDB_HOST = host;
        putUrl = new URL("http://" + host + ":4242/api/put");
        queryUrl = new URL("http://" + OPENTSDB_HOST + ":4242/api/query");
        //eventTemplate = initTSToSend();
    }

    public static void main(String[] args) throws Exception {

        /**
         * needed for working with Hadoop XML-Properties files.
         */
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");

        stdlib.StdRandom.initRandomGen(1);

        // The URL of OpenTSDB Server is known by this component.
        OpenTSDBConnector connector = new OpenTSDBConnector();


        long t0 = System.currentTimeMillis();


        // TODO : Write Benchmark

        /**
         * Put a simple event to OpenTSDB.

         // THE BULK LOAD IS NOT WORKING YET (need a template for this) ...
         int i = 0;
         while ( i < 2 ) {

         HttpURLConnection httpCon = (HttpURLConnection) connector.putUrl.openConnection();
         httpCon.setDoOutput(true);
         httpCon.setRequestMethod("POST");

         OutputStreamWriter out = new OutputStreamWriter(httpCon.getOutputStream());

         i++;

         OpenTSDBEvent e = new OpenTSDBEvent();
         e.metric = "sys.cpu.idle";
         e.tags.put("server", "2");
         e.tags.put("rack", "1");
         e.tags.put("ds", "3");
         e.timestamp = "" + (t0 + (i * 1000));
         e.value = (Math.random() * 5009) + "";

         String pl = e.toJSON();

         System.out.println(pl);

         out.write(pl);

         out.close();

         InputStream ins = httpCon.getInputStream();

         BufferedReader bins = new BufferedReader(new InputStreamReader(ins));

         while (bins.ready())
         System.out.println(" RESPONSE: " + bins.readLine());


         }
         */


        /**
         * Put a set of noisy "TimeSeriesObject" to OpenTSDB with current time stamp.


         TimeSeriesObject mr1 = TimeSeriesObject.getGaussianDistribution( 100 );
         mr1.setLabel( "demo1 s=1,distr=gauss,run=1" );
         TimeSeriesObject mr2 = TimeSeriesObject.getGaussianDistribution( 100, 1000, 10 );
         mr2.setLabel( "demo1 s=2,distr=gauss,run=1" );
         TimeSeriesObject mr3 = TimeSeriesObject.getGaussianDistribution( 100, 100, 2000 );
         mr3.setLabel( "demo1 s=3,distr=gauss,run=1" );

         storeMessreihe( mr1, connector );
         storeMessreihe( mr2, connector );
         storeMessreihe( mr3, connector );
         */


        /**
         *
         */

        /**
         * NEEDS BETTER SCALING
         */
        TimeSeriesObject mr10 = TimeSeriesObject.getParetoDistribution(100000, 1000000);
        mr10.setLabel("demoW10 distr=pareto,run=1");

        TimeSeriesObject mr20 = TimeSeriesObject.getExpDistribution(100000, 10000);
        mr20.setLabel("demoW20 distr=exp,run=1");

        TimeSeriesObject mr30 = TimeSeriesObject.getGaussianDistribution(100000, 10000, 100);
        mr30.setLabel("demoW30 distr=gauss,run=1");

        Vector<TimeSeriesObject> bucketData = new Vector<TimeSeriesObject>();
        bucketData.add(mr10);
        bucketData.add(mr20);
        bucketData.add(mr30);

        storeBucketData(bucketData, connector, System.currentTimeMillis());

        long now = System.currentTimeMillis();
        String range = "start=0&end=" + now;


        /**
         * Read a single TSO from OpenTSDB.
         */
        TimeSeriesObject mr;
        mr = readTimeSeriesForMetric("demoW30", "sum", range, connector);
        System.out.println( mr );

        /**
         * Read a multiple TSO for multiple metrics from OpenTSDB into TSBucket.
         */
        TSBucket bucket = TSBucket.createEmptyBucket();

        String[] metrics = {"demoW10", "demoW20", "demoW30"};


        Vector<Thread> rs = new Vector<Thread>();
        Vector<TSReaderRunnable> rs2 = new Vector<TSReaderRunnable>();
        for (String m : metrics) {

            // bucket.getBucketData().add( readTimeSeriesForMetric( m, "sum", connector ) );

            // String range = "start=2017/08/05-00:00:00&end=2017/08/08-20:05:00";
            long now2 = System.currentTimeMillis();
            String range2 = "start=0&end=" + now2;

            TSReaderRunnable r1 = new TSReaderRunnable(m, "sum", range2, connector, bucket);
            Thread t = new Thread(r1);
            rs.add(t);
            t.start();

            rs2.add(r1);

        }
        for (Thread t : rs) {
            t.join();
        }


        // Take rows from Runnnable ....
        // ---------------------------------
        Vector<TimeSeriesObject> loadedBucketData = new Vector<TimeSeriesObject>();
        for (TSReaderRunnable r : rs2) {
            loadedBucketData.add(r.mr);
        }


        // Original data
        MultiChart.open(loadedBucketData, true, "TEST 3 - original");

        // Reloaded data
        MultiChart.open(bucketData, true, "TEST 3 - reloaded");

    }

    public static Vector<TimeSeriesObject> loadBucketFromOpenTSDB( String[] metrics , OpenTSDBConnector connector, TSBucket bucket) throws Exception {



        Vector<Thread> rs = new Vector<Thread>();
        Vector<TSReaderRunnable> rs2 = new Vector<TSReaderRunnable>();
        for (String m : metrics) {

            // bucket.getBucketData().add( readTimeSeriesForMetric( m, "sum", connector ) );

            // String range = "start=2017/08/05-00:00:00&end=2017/08/08-20:05:00";
            long now2 = System.currentTimeMillis();
            String range2 = "start=0&end=" + now2;

            TSReaderRunnable r1 = new TSReaderRunnable(m, "sum", range2, connector, bucket);
            Thread t = new Thread(r1);
            rs.add(t);
            t.start();

            rs2.add(r1);

        }
        for (Thread t : rs) {
            t.join();
        }


        // Take rows from Runnnable ....
        // ---------------------------------
        Vector<TimeSeriesObject> loadedBucketData = new Vector<TimeSeriesObject>();
        for (TSReaderRunnable r : rs2) {
            loadedBucketData.add(r.mr);
        }

        return loadedBucketData;

    }

    protected static void storeBucketData(Vector<TimeSeriesObject> bucketData, OpenTSDBConnector connector, long l) throws Exception {
        for (TimeSeriesObject mr : bucketData) {

            System.out.println("persist row : " + mr.getLabel() + " => " + mr.xValues.size());

            // storeMessreihe(mr, connector, l);
            storeMessreiheAsStream(mr, connector, l);

        }

    }

    private static void storeListOfEventsFromMessreihe_TelenetStyle(List<OpenTSDBEvent> events, OpenTSDBConnector connector) throws Exception {

        // Telnet style client ...
        System.out.println("Connect to OpenTSDB Service on : " + OpenTSDBConnector.OPENTSDB_HOST + " using Telnet mode (ListStreamMode: dot = 10.000 points).");

        Socket pingSocket = null;

        int i = 1;

        System.out.print(i + "\t\t");

        try {

            pingSocket = new Socket(OpenTSDBConnector.OPENTSDB_HOST, 4242);
            OutputStream out = pingSocket.getOutputStream();

            for (OpenTSDBEvent e : events) {

                out.write(("put " + e.asTelnetPutLoad() + "\n").getBytes());

                if (i % 100000 == 0) System.out.println(i + "\t\t");

                if (i % 10000 == 0) {
                    out.flush();
                    System.out.print(".");

                }

                i++;

            }

            out.close();

        } catch (IOException e) {
            return;
        }

    }

    /**
     * private static void storeListOfEventsFromMessreihe(List<OpenTSDBEvent> events, OpenTSDBConnector connector) throws Exception {
     * <p>
     * StringBuffer sb = new StringBuffer();
     * sb.append("[");
     * <p>
     * for (OpenTSDBEvent e : events) {
     * <p>
     * sb.append(e.toJSON() + ",");
     * <p>
     * }
     * <p>
     * String all = sb.toString();
     * String cont = all.substring(0, all.length() - 1);
     * all = cont + "]";
     * <p>
     * <p>
     * HttpURLConnection httpCon = (HttpURLConnection) connector.putUrl.openConnection();
     * httpCon.setDoOutput(true);
     * httpCon.setRequestMethod("POST");
     * <p>
     * OutputStreamWriter out = new OutputStreamWriter(httpCon.getOutputStream());
     * <p>
     * out.write(all);
     * <p>
     * //System.out.println( all );
     * <p>
     * out.close();
     * <p>
     * InputStream ins = httpCon.getInputStream();
     * <p>
     * BufferedReader bins = new BufferedReader(new InputStreamReader(ins));
     * <p>
     * while (bins.ready())
     * System.out.println(" RESPONSE: " + bins.readLine());
     * <p>
     * <p>
     * }
     */


    public static void storeMessreiheAsStream(TimeSeriesObject row, OpenTSDBConnector connector, long offset) throws Exception {
        streamEventsToOpenTSDB(row, offset, connector);
    }

    public static void storeMessreihe(TimeSeriesObject row, OpenTSDBConnector connector, long offset) throws Exception {

        // We expect the metric as String in this way.

        List<OpenTSDBEvent> events = getEventsFrom(row, offset);

        int z = events.size();

        int batch = 0;
        int volume = 10000000;

        List<OpenTSDBEvent> l;
        int von = 0;
        int bis = volume;

        boolean goOn = true;

        while (batch * volume < z && goOn) {

            von = batch * volume;
            bis = ((batch + 1) * volume) - 1;

            if (bis < z) {
                l = events.subList(von, bis);
                batch++;
                storeListOfEventsFromMessreihe_TelenetStyle(l, connector);
                System.out.println(" batch: " + batch + " [" + von + "," + bis + "]");
            } else {
                bis = 0;
                goOn = false;
            }
        }
        von = bis;
        bis = z;

        l = events.subList(von, bis);

        storeListOfEventsFromMessreihe_TelenetStyle(l, connector);

        System.out.println("zBatches: " + batch + " => " + row.getLabel());


        /*
        for ( OpenTSDBEvent e : events ) {

            //System.out.println( ">>>" + e.toJSON() );

            HttpURLConnection httpCon = (HttpURLConnection) connector.putUrl.openConnection();
            httpCon.setDoOutput(true);
            httpCon.setRequestMethod("POST");

            OutputStreamWriter out = new OutputStreamWriter(httpCon.getOutputStream());

            String pl = e.toJSON();

            System.out.println(pl);

            out.write(pl);

            out.close();

            InputStream ins = httpCon.getInputStream();

            BufferedReader bins = new BufferedReader(new InputStreamReader(ins));

            while (bins.ready())
                System.out.println(" RESPONSE: " + bins.readLine());



        }

        */

//            InputStream ins = httpCon.getInputStream();
//            BufferedReader bins = new BufferedReader(new InputStreamReader(ins));

//            while (bins.ready())
//                System.out.println(bins.readLine());

    }

    public static void storeMessreihe(TimeSeriesObject row, OpenTSDBConnector connector) throws Exception {

        long t0 = System.currentTimeMillis();

        storeMessreihe(row, connector, t0);

    }

    //
    // We need bulk load to OpenTSDB:
    //
    // tsd.http.request.enable_chunked=true
    // tsd.mode=ro => READ ONLY !!! needs to be set to RW
    //
    // docker container ls
    // docker exec -it 6b5162c943bd /bin/bash
    // vi /etc/opentsdb/opentsdb.conf
    //
    public static void streamEventsToOpenTSDB(TimeSeriesObject row, long offset, OpenTSDBConnector connector) throws Exception {

        double resolution = 1000.0;

        double[][] DATA = row.getData();

        // Iterate over all data points ...
        int i = 0;


        System.out.println("Connect to OpenTSDB Service on : " + OpenTSDBConnector.OPENTSDB_HOST + " using Telnet mode (ListStreamMode: dot = 10.000 points).");

        Socket pingSocket = null;

        pingSocket = new Socket(OpenTSDBConnector.OPENTSDB_HOST, 4242);
        OutputStream out = pingSocket.getOutputStream();


        long t0 = System.currentTimeMillis();

        for (i = 0; i < DATA[0].length; i++) {

            OpenTSDBEvent e = new OpenTSDBEvent(); // TEMPLATE OBJECT

            // "sys.cpu.idle server=2,rack=1,ds=1";
            String name = row.getLabel();
            // String name = "sys.cpu.idle server=2,rack=1,ds=1";

//            System.out.println("[LABEL] " + row.getLabel() );

            String[] PARTS = name.split(" ");
            String metric = PARTS[0];
            e.metric = metric;

            String[] TAGS = PARTS[1].split(",");
            for (String tag : TAGS) {
                String[] KV = tag.split("=");
                e.tags.put(KV[0], KV[1]);
            }

            e.timestamp = "" + (long) (offset + (long) (resolution * DATA[0][i]));
            e.value = "" + DATA[1][i];


            out.write(("put " + e.asTelnetPutLoad() + "\n").getBytes());


            if (i % 100000 == 0) {

                long t1 = System.currentTimeMillis();

                long dt = t1 - t0;

                if ((int) (dt / 1000) > 0) {


                    System.out.println("\n # " + i + " events in " + (int) (dt / 1000) + " s");
                    System.out.println(" # " + i / (dt / 1000) + " events / s ");

                }

                System.out.print("\n" + i + "->");


            }


            if (i % 10000 == 0) {
                out.flush();
                System.out.print(".");
            }


        }


        out.close();

        long t2 = System.currentTimeMillis();

        long dt = t2 - t0;

        System.out.println(i + " events in " + (int) (dt / 1000) + " s");

        //if( dt != 0.0 )
        //    System.out.println(i / (dt / 1000) + " events / s ");


    }


    public static String streamEventsToOpenTSDB(TimeSeriesObject row, OpenTSDBConnector connector) {

        int nrEvents = 0;

        try {
        double resolution = 1000.0;

        double[][] DATA = row.getData();

        // Iterate over all data points ...
        int i = 0;


        System.out.println(">>> Connect to OpenTSDB Service on : " + OpenTSDBConnector.OPENTSDB_HOST + " using Telnet mode (ListStreamMode: dot = 10.000 points).");

        Socket pingSocket = null;

        pingSocket = new Socket(OpenTSDBConnector.OPENTSDB_HOST, 4242);
        OutputStream out = pingSocket.getOutputStream();

        for (i = 0; i < DATA[0].length; i++) {

            OpenTSDBEvent e = new OpenTSDBEvent(); // TEMPLATE OBJECT

            // "sys.cpu.idle server=2,rack=1,ds=1";
            String name = row.getLabel();
            // String name = "sys.cpu.idle server=2,rack=1,ds=1";

//            System.out.println("[LABEL] " + row.getLabel() );

            String[] PARTS = name.split(" ");
            String metric = PARTS[0];
            e.metric = metric;

            String[] TAGS = PARTS[1].split(",");
            for (String tag : TAGS) {
                String[] KV = tag.split("=");
                e.tags.put(KV[0], KV[1]);
            }

            Long l = (new Double(DATA[0][i])).longValue();

            e.timestamp = l.toString();
            e.value = "" + DATA[1][i];


            out.write(("put " + e.asTelnetPutLoad() + "\n").getBytes());

            System.out.println("*** put " + e.asTelnetPutLoad() + "\n");

        }

        nrEvents = i+1;

        out.flush();
        out.close();

        return "Transfer sucessfull. {" + nrEvents + " events }";

        }
        catch(Exception ex) {

            return "!!! Transfer FAILED !!! [i=" + nrEvents + "] {" + ex.getMessage() + "}";

        }

    }



    //
    // We need bulk load to OpenTSDB:
    //
    // tsd.http.request.enable_chunked=true
    // tsd.mode=ro => READ ONLY !!! needs to be set to RW
    //
    // docker container ls
    // docker exec -it 6b5162c943bd /bin/bash
    // vi /etc/opentsdb/opentsdb.conf
    //
    private static List<OpenTSDBEvent> getEventsFrom(TimeSeriesObject row, long offset) throws CloneNotSupportedException {

        double resolution = 1000.0;

        // System.out.print( row );

        List<OpenTSDBEvent> liste = new ArrayList<OpenTSDBEvent>();

        double[][] DATA = row.getData();

        // Iterate over all data points ...
        int i = 0;

        for (i = 0; i < DATA[0].length; i++) {

            OpenTSDBEvent e = new OpenTSDBEvent(); // TEMPLATE OBJECT

            // "sys.cpu.idle server=2,rack=1,ds=1";
            String name = row.getLabel();
            // String name = "sys.cpu.idle server=2,rack=1,ds=1";

//            System.out.println("[LABEL] " + row.getLabel() );

            String[] PARTS = name.split(" ");
            String metric = PARTS[0];
            e.metric = metric;

            String[] TAGS = PARTS[1].split(",");
            for (String tag : TAGS) {
                String[] KV = tag.split("=");
                e.tags.put(KV[0], KV[1]);
            }

            e.timestamp = "" + (long) (offset + (long) (resolution * DATA[0][i]));
            e.value = "" + DATA[1][i];

            liste.add(e);

            i++;

        }

        return liste;

    }

    private static String initTSToSend() {

        String s = null;

        try {

            s = new String(Files.readAllBytes(Paths.get("/GITHUB/cuda-org.opentsx.tsa/src/main/resources/datapoint")));

            System.out.println(s);

            System.out.println("\n***************************************************");


        } catch (IOException e) {
            e.printStackTrace();
        }

        return s;

    }

    public static TimeSeriesObject readTimeSeriesForMetric(String metric, String aggregator, String periode, OpenTSDBConnector connector) throws Exception {
        // http://localhost:4242/api/query?start=1h-ago&m=sum:rate:proc.stat.cpu{host=foo,type=idle}
        // http://cc-poc-mk-3.gce.cloudera.com:4242/api/query?start=2017/08/05-00:00:00&end=2017/08/07-20:05:00&m=sum:demo2%7Bdistr=sine%7D&o=&yrange=%5B0:%5D&wxh=1850x879&style=linespoint

        // String periode = "start=2017/08/05-00:00:00&end=2017/08/08-20:05:00";

        String queryString = "?" + periode + "&m=" + aggregator + ":" + metric;

        URL url4Call = new URL(connector.queryUrl + queryString);

        System.out.println("> " + connector.queryUrl + queryString);

        HttpURLConnection httpCon = (HttpURLConnection) url4Call.openConnection();

        InputStream ins = httpCon.getInputStream();

        BufferedReader bins = new BufferedReader(new InputStreamReader(ins));

        StringBuffer sb = new StringBuffer();
        while (bins.ready()) {
            sb.append(bins.readLine() + "\n");
        }

        if (OpenTSDBConnector.debug)
            System.out.println(" RESPONSE: " + sb.toString());

        return OpenTSDBResponseTransformer.getMessreiheForJSONResponse(sb.toString());
    }

    // Connection for a permanent writers such as Flume or Spark Streaming ...
    public void close() throws IOException {

        System.out.println(">>> Close OpenTSDBConnector ... ");

        out.flush();
        out.close();


    }

    public void openSocket() {

        try {
            System.out.println(">>> Open OpenTSDBConnector ... ");
            System.out.println(">>> Connect to OpenTSDB Service on : " + OpenTSDBConnector.OPENTSDB_HOST + " using Telnet mode (ListStreamMode: dot = 10.000 points).");


            pingSocket = new Socket(OpenTSDBConnector.OPENTSDB_HOST, 4242);
            out = pingSocket.getOutputStream();

        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }

    public void sendEventViaSocket(OpenTSDBEvent ev) throws Exception {

        // an individual event travels to OpenTSDB using the telnet protocoll ...
        out.write(("put " + ev.asTelnetPutLoad() + "\n").getBytes());

    }
}

class TSReaderRunnable implements Runnable {

    public TimeSeriesObject mr;

    private Thread t;
    private String threadName;
    private OpenTSDBConnector connector;
    private TSBucket bucket;
    private String aggr;
    private String r;

    TSReaderRunnable(String m, String agg, String range, OpenTSDBConnector con, TSBucket bu) {
        threadName = m;
        System.out.println("> Creating TS Loader for :  " + threadName);
        connector = con;
        bucket = bu;
        aggr = agg;
        r = range;
    }


    public void run() {
        System.out.println("Running " + threadName);

        try {

            mr = OpenTSDBConnector.readTimeSeriesForMetric(threadName, aggr, r, connector);

            bucket.getBucketData().add(mr);

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        System.out.println("Thread " + threadName + " exiting.");

    }

    public void start() {
        System.out.println("Starting " + threadName);
        if (t == null) {
            t = new Thread(this, threadName);
            t.start();
        }
    }
}
