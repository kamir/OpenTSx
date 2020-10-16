package org.opentsx.connectors.yahoofin;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.opentsx.data.series.TimeSeriesObject;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import scala.Tuple2;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * @author Mirko Kaempf
 */
public class StockDataLoader2 {

    private static final Logger LOG = Logger.getLogger(StockDataLoader2.class.getName());

    private static void loadStockTradingDataForYearFromCache(int year, String label, String column) {

        label = label.concat(".tsv");

        try {

            StockDataLoader2 sDL_2 = StockDataLoader2.getLocalLoader(year + "-01-01", year + "-12-31", label);
            sDL_2.initColumn(column);

            sDL_2.showCharts("C");

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    private static void loadStockTradingDataForYearFromCache(int year) {

        String column = "Close";
        String label = "UK.tsv";

        try {

            StockDataLoader2 sDL_2 = StockDataLoader2.getLocalLoader(year + "-01-01", year + "-12-31", label);
            sDL_2.initColumn(column);

            sDL_2.showCharts("C");

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public static void loadAndCacheStockTradingDataForYears(int[] years, String column, String label) {

        label = label + ".tsv";

        for (int year : years) {

            try {

                StockDataLoader2 sDL_1 = StockDataLoader2.getOnlineLoader(column, year + "-01-01", year + "-12-31", label, year);
                sDL_1.showCharts("W");

            } catch (IOException ex) {
                ex.printStackTrace();
            }

        }
    }

    public static void loadAndCacheStockTradingDataForYears(int year1, int year2, String column, String label) {

        label = label + ".tsv";

        try {

            StockDataLoader2 sDL_1 = StockDataLoader2.getOnlineLoader(column, year1 + "-01-01", year2 + "-12-31", label, year1, year2);
            sDL_1.showCharts("W");

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void loadAndCacheStockTradingDataForYears(int[] years) {

        String column = "Close";
        String label = "UK.tsv";

        for (int year : years) {

            try {

                StockDataLoader2 sDL_1 = StockDataLoader2.getOnlineLoader(column, year + "-01-01", year + "-12-31", label, year);
                sDL_1.showCharts("W");

            } catch (IOException ex) {
                ex.printStackTrace();
            }

        }
    }

    public static Vector<TimeSeriesObject> concatRowsForYearsFromCache_2(int[] YEARS, String column, String label) {

        Vector<TimeSeriesObject> y1 = getRowsForYearFromCache(YEARS[0], column, label);
        Vector<TimeSeriesObject> y2 = getRowsForYearFromCache(YEARS[1], column, label);
//        Vector<TimeSeriesObject> y3 = getRowsForYearFromCache( YEARS[2] );
//        Vector<TimeSeriesObject> y4 = getRowsForYearFromCache( YEARS[3] );

        if (conf == null) {

            conf = new SparkConf().setAppName("StockData-TOOL").setMaster("local[3]");
            sc = new JavaSparkContext(conf);

        }

        List<TimeSeriesObject> d1 = y1.subList(0, y1.size() - 1);
        List<TimeSeriesObject> d2 = y2.subList(0, y2.size() - 1);
//        List<TimeSeriesObject> d3 = y3.subList(0, y3.size()-1);
//        List<TimeSeriesObject> d4 = y4.subList(0, y4.size()-1);

        JavaRDD<TimeSeriesObject> distD1 = sc.parallelize(d1);
        JavaRDD<TimeSeriesObject> distD2 = sc.parallelize(d2);
//        JavaRDD<TimeSeriesObject> distD3 = sc.parallelize(d3);
//        JavaRDD<TimeSeriesObject> distD4 = sc.parallelize(d4);

        // now I have to key the rdds by the TimeSeriesObject label (all 4)
//        JavaPairRDD<String, TimeSeriesObject> p1 = distD1.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//        JavaPairRDD<String, TimeSeriesObject> p2 = distD2.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//        JavaPairRDD<String, TimeSeriesObject> p3 = distD3.mapToPair( w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//        JavaPairRDD<String, TimeSeriesObject> p4 = distD4.mapToPair( w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));

        // let join them and concat the results (1/3 phases)
//        JavaPairRDD<String, Tuple2<TimeSeriesObject, TimeSeriesObject>> pA = p1.join(p2);
//        JavaPairRDD<String, Tuple2<TimeSeriesObject,TimeSeriesObject>> pB = p3.join(p4);

//        JavaPairRDD<String, Tuple2< Tuple2<TimeSeriesObject,TimeSeriesObject>,Tuple2<TimeSeriesObject,TimeSeriesObject>>> pALL = pA.join(pB);
//        JavaPairRDD<String,TimeSeriesObject> rows = pALL.mapValues( w -> concatMR2( w ) );
//        JavaPairRDD<String, TimeSeriesObject> rows = pA.mapValues(w -> concatMR(w));

//        rows.count();

//        Map<String, TimeSeriesObject> all = rows.collectAsMap();
//        Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();

//        for (TimeSeriesObject m : all.values()) {
//            mrv.add(m);
//        }

        return null;
    }

    static SparkConf conf = null;
    static JavaSparkContext sc = null;

    public static Vector<TimeSeriesObject> concatRowsForYearsFromCache_4(int[] YEARS, String column, String label) {

        Vector<TimeSeriesObject> y1 = getRowsForYearFromCache(YEARS[0], column, label);
        Vector<TimeSeriesObject> y2 = getRowsForYearFromCache(YEARS[1], column, label);
        Vector<TimeSeriesObject> y3 = getRowsForYearFromCache(YEARS[2], column, label);
        Vector<TimeSeriesObject> y4 = getRowsForYearFromCache(YEARS[3], column, label);
        if (conf == null) {
            conf = new SparkConf().setAppName("StockData-TOOL").setMaster("local[3]");
            sc = new JavaSparkContext(conf);
        }
        List<TimeSeriesObject> d1 = y1.subList(0, y1.size() - 1);
        List<TimeSeriesObject> d2 = y2.subList(0, y2.size() - 1);
        List<TimeSeriesObject> d3 = y3.subList(0, y3.size() - 1);
        List<TimeSeriesObject> d4 = y4.subList(0, y4.size() - 1);
        JavaRDD<TimeSeriesObject> distD1 = sc.parallelize(d1);
        JavaRDD<TimeSeriesObject> distD2 = sc.parallelize(d2);
        JavaRDD<TimeSeriesObject> distD3 = sc.parallelize(d3);
        JavaRDD<TimeSeriesObject> distD4 = sc.parallelize(d4);

//        JavaPairRDD<String, TimeSeriesObject> p1 = distD1.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//        JavaPairRDD<String, TimeSeriesObject> p2 = distD2.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//        JavaPairRDD<String, TimeSeriesObject> p3 = distD3.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//        JavaPairRDD<String, TimeSeriesObject> p4 = distD4.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//
//        JavaPairRDD<String, Tuple2<TimeSeriesObject, TimeSeriesObject>> pA = p1.join(p2);
//        JavaPairRDD<String, Tuple2<TimeSeriesObject, TimeSeriesObject>> pB = p3.join(p4);
//
//        JavaPairRDD<String, Tuple2< Tuple2<TimeSeriesObject, TimeSeriesObject>, Tuple2<TimeSeriesObject, TimeSeriesObject>>> pALL = pA.join(pB);
//        JavaPairRDD<String, TimeSeriesObject> rows = pALL.mapValues(w -> concatMR2(w));
//
//        Map<String, TimeSeriesObject> all = rows.collectAsMap();
//        Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();
//
//        for (TimeSeriesObject m : all.values()) {
//            mrv.add(m);
//        }


        return null;
    }

    public static Vector<TimeSeriesObject> concatRowsForYearsFromCache_ALL(int[] y, String column, String label) {

//        if (conf == null) {
//            conf = new SparkConf().setAppName("StockData-TOOL").setMaster("local[3]");
//            sc = new JavaSparkContext(conf);
//        }
//
//        int i = 0;
//
//        Vector<TimeSeriesObject> y1 = getRowsForYearFromCache(y[i], column, label);
//
//        List<TimeSeriesObject> d1 = y1.subList(0, y1.size() - 1);
//
//        for (int j = 1; j < y.length; j++) {
//
//            JavaRDD<TimeSeriesObject> distD1 = sc.parallelize(d1);
//
//            JavaPairRDD<String, TimeSeriesObject> p1 = distD1.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//
//            Vector<TimeSeriesObject> y2 = getRowsForYearFromCache(y[j], column, label);
//
////            javax.swing.JOptionPane.showMessageDialog(null, y2.size() + " " + y[j]);
//            if (y2 != null && y2.size() > 0) {
//
//                List<TimeSeriesObject> d2 = y2.subList(0, y2.size() - 1);
//
//                JavaRDD<TimeSeriesObject> distD2 = sc.parallelize(d2);
//                JavaPairRDD<String, TimeSeriesObject> p2 = distD2.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//
//                JavaPairRDD<String, Tuple2<TimeSeriesObject, TimeSeriesObject>> pA = p1.join(p2);
//
//                JavaPairRDD<String, TimeSeriesObject> rows = pA.mapValues(w -> concatMR_MANY(w));
//
//
//                distD1 = rows.values();
////                javax.swing.JOptionPane.showMessageDialog(null,"*** " + distD1.count() + " *** " + y[j]);
//
//                d1 = distD1.collect();
//
//
////                Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();
////
////                for (TimeSeriesObject m : d1) {
////                    mrv.add(m);
////                }
////
////                MultiChart.open(mrv, true, y[j] + " " + d1.size() + " ...");
//
//            }
//        }
//
//        Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();
//
//        for (TimeSeriesObject m : d1) {
//            mrv.add(m);
//        }
        return null;
    }

    public static Vector<TimeSeriesObject> concatRowsForYearsFromCache(int[] YEARS) {

//        Vector<TimeSeriesObject> y1 = getRowsForYearFromCache(YEARS[0]);
//        Vector<TimeSeriesObject> y2 = getRowsForYearFromCache(YEARS[1]);
////        Vector<TimeSeriesObject> y3 = getRowsForYearFromCache( YEARS[2] );
////        Vector<TimeSeriesObject> y4 = getRowsForYearFromCache( YEARS[3] );
//
//        SparkConf conf = new SparkConf().setAppName("StockData-TOOL").setMaster("local[3]");
//        JavaSparkContext sc = new JavaSparkContext(conf);
//
//        List<TimeSeriesObject> d1 = y1.subList(0, y1.size() - 1);
//        List<TimeSeriesObject> d2 = y2.subList(0, y2.size() - 1);
////        List<TimeSeriesObject> d3 = y3.subList(0, y3.size()-1);
////        List<TimeSeriesObject> d4 = y4.subList(0, y4.size()-1);
//
//        JavaRDD<TimeSeriesObject> distD1 = sc.parallelize(d1);
//        JavaRDD<TimeSeriesObject> distD2 = sc.parallelize(d2);
////        JavaRDD<TimeSeriesObject> distD3 = sc.parallelize(d3);
////        JavaRDD<TimeSeriesObject> distD4 = sc.parallelize(d4);
//
//        // now I have to key the rdds by the TimeSeriesObject label (all 4)
//        JavaPairRDD<String, TimeSeriesObject> p1 = distD1.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//        JavaPairRDD<String, TimeSeriesObject> p2 = distD2.mapToPair(w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
////        JavaPairRDD<String, TimeSeriesObject> p3 = distD3.mapToPair( w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
////        JavaPairRDD<String, TimeSeriesObject> p4 = distD4.mapToPair( w -> new Tuple2<String, TimeSeriesObject>(w.getLabel(), w));
//
//        // let join them and concat the results (1/3 phases)
//        JavaPairRDD<String, Tuple2<TimeSeriesObject, TimeSeriesObject>> pA = p1.join(p2);
////        JavaPairRDD<String, Tuple2<TimeSeriesObject,TimeSeriesObject>> pB = p3.join(p4);
//
////        JavaPairRDD<String, Tuple2< Tuple2<TimeSeriesObject,TimeSeriesObject>,Tuple2<TimeSeriesObject,TimeSeriesObject>>> pALL = pA.join(pB);
////        JavaPairRDD<String,TimeSeriesObject> rows = pALL.mapValues( w -> concatMR2( w ) );
//        JavaPairRDD<String, TimeSeriesObject> rows = pA.mapValues(w -> concatMR(w));
//
//        rows.count();
//
//        Map<String, TimeSeriesObject> all = rows.collectAsMap();
//        Vector<TimeSeriesObject> mrv = new Vector<TimeSeriesObject>();
//
//        for (TimeSeriesObject m : all.values()) {
//            mrv.add(m);
//        }

        return null;
    }

    private static TimeSeriesObject concatMR_MANY(Tuple2<TimeSeriesObject, TimeSeriesObject> w) {

        TimeSeriesObject r = w._1;

        r.setLabel(w._1.getLabel() );

        r.addValues(w._2);

        return r;
    }

    private static TimeSeriesObject concatMR(Tuple2<TimeSeriesObject, TimeSeriesObject> w) {

        TimeSeriesObject r = w._1;

        r.setLabel(w._1.getLabel() + "_" + w._2.getLabel());

        r.addValues(w._2);

        return r;
    }

    private static TimeSeriesObject concatMR2(Tuple2<Tuple2<TimeSeriesObject, TimeSeriesObject>, Tuple2<TimeSeriesObject, TimeSeriesObject>> w) {

        TimeSeriesObject r = w._1._1;

        r.setLabel(w._1._1.getLabel() + "_" + w._1._2.getLabel() + "_" + w._2._1.getLabel() + "_" + w._2._2.getLabel());

        r.addValues(w._1._2);
        r.addValues(w._2._1);
        r.addValues(w._2._2);

        return r;
    }

    boolean debug = false;

    /**
     * The local cache functionality is considered to be useful for the local
     * workstation mode.
     *
     * For cluster processing we create RDDs from the locally cached data.
     *
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void initLocalCache() throws FileNotFoundException, IOException {
        loadListe();
        loadDataFromFile();
    }

    Vector<String> liste = new Vector<String>(); // name list ...
    Hashtable<String, String> hash = new Hashtable<String, String>();
    Hashtable<String, TimeSeriesObject> hashMR = new Hashtable<String, TimeSeriesObject>();

    // name of the label listfile for which the stock-prices are loaded
    String label = "UK.tsv";

    // query parameters 
    String stock = "MSFT";
    String startDate = "2009-01-01";
    String endDate = "2009-12-31";

    String column = "Close";

    // local time series container which becomes an RDD later on.
    Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();
    int y1 = 2001;
    int y2 = 2001;

    /**
     * Loads locally cached data
     *
     * @param from
     * @param to
     * @param lab
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    public static StockDataLoader2 getLocalLoader(String from, String to, String lab) throws FileNotFoundException, IOException {
        StockDataLoader2 l = new StockDataLoader2();
        l.startDate = from;
        l.endDate = to;
        l.label = lab;
        l.initLocalCache();
        return l;
    }

    /**
     * Loads data from Yahoo Financial Services ...
     *
     * @param column
     * @param from
     * @param to
     * @param lab
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    public static StockDataLoader2 getOnlineLoader(String column, String from, String to, String lab, int year) throws FileNotFoundException, IOException {
        StockDataLoader2 l = new StockDataLoader2();
        l.startDate = from;
        l.endDate = to;
        l.y1 = year;
        l.y2 = year;
        l.label = lab;
        l.initCacheFromWeb(column);
        return l;
    }

    public static StockDataLoader2 getOnlineLoader(String column, String from, String to, String lab, int y1, int y2) throws FileNotFoundException, IOException {
        StockDataLoader2 l = new StockDataLoader2();
        l.startDate = from;
        l.endDate = to;
        l.y1 = y1;
        l.y2 = y2;
        l.label = lab;
        l.initCacheFromWeb(column);
        return l;
    }

    public static Vector<TimeSeriesObject> getRowsForYearFromCache(int y1, int y2, String label, String column) {

        StockDataLoader2 sDL_2 = null;

        try {

            sDL_2 = StockDataLoader2.getLocalLoader(y1 + "-01-01", y2 + "-12-31", label);
            sDL_2.initColumn(column);

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if (sDL_2 != null) {
            return sDL_2.vmr;
        } else {
            return null;
        }
    }

    public static Vector<TimeSeriesObject> getRowsForYearFromCache(int year) {

        StockDataLoader2 sDL_2 = null;

        String column = "Close";
        String label = "UK.tsv";

        try {

            sDL_2 = StockDataLoader2.getLocalLoader(year + "-01-01", year + "-12-31", label);
            sDL_2.initColumn(column);

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if (sDL_2 != null) {
            return sDL_2.vmr;
        } else {
            return null;
        }
    }

    public static Vector<TimeSeriesObject> getRowsForYearFromCache(int year, String column, String label) {

        StockDataLoader2 sDL_2 = null;

        label = label + ".tsv";

        try {

            sDL_2 = StockDataLoader2.getLocalLoader(year + "-01-01", year + "-12-31", label);
            sDL_2.initColumn(column);

        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if (sDL_2 != null) {

//            MultiChart.open(sDL_2.vmr, true, label + " " + year);
            return sDL_2.vmr;
        } else {
            return null;
        }
    }

    /**
     * For each year in the years array we load the time series from Yahoo
     * Financial Services.
     *
     * @param arg
     * @throws IOException
     */
    public static void main(String[] arg) throws IOException {

//        int[] years = {1995,1996,1997,1998};
//        int[] years = {1999,2000,2001,2002};
//        int[] years = {2003,2004,2005,2006};
//        int[] years = {2007,2008,2009,2010};
        int[] years = {2011, 2012, 2013, 2014};
//        int[] years = {1999 };

        loadAndCacheStockTradingDataForYears(years);

        for (int year : years) {
            loadStockTradingDataForYearFromCache(year);
        }

        /**
         * Example 1: Load the Close price for 2012 from web and cache locally
         */
//        String column = "Close";
//        
//        String label = "UK.tsv";
//
//        
//        StockDataLoader sDL_1 = StockDataLoader.getOnlineLoader(column, "2012-01-01", "2012-12-31", label, 2012);
//        
//        sDL_1.initColumn("Adj_Close");
//        sDL_1.showCharts();
//        
//        sDL_1.initColumn("High");
//        sDL_1.showCharts();
//        
//        int i=0;
//        for (String s : sDL_1.bad) {
//            i++;
//            System.out.println("> Could not load data for: " + s);
//        }
//        System.out.println("> Total Errors: " + i);
//        /**
//         * Now we extract other columns from local files ...
//         */
//        StockDataLoader sdl = StockDataLoader.getLocalLoader("2012-01-01", "2012-12-31", label);
//        sdl.initColumn("Close");
//        sdl.showCharts();
//
//        StockDataLoader sdl2 = StockDataLoader.getLocalLoader("2012-01-01", "2012-12-31", label);
//        sdl2.initColumn("Adj_Close");
//        sdl2.showCharts();
//
//        StockDataLoader sdl3 = StockDataLoader.getLocalLoader("2012-01-01", "2012-12-31", label);
//        sdl3.initColumn("High");
//        sdl3.showCharts();
//
//        StockDataLoader sdl4 = StockDataLoader.getLocalLoader("2012-01-01", "2012-12-31", label);
//        sdl4.initColumn("Low");
//        sdl4.showCharts();
//
//        StockDataLoader sdl5 = StockDataLoader.getLocalLoader("2012-01-01", "2012-12-31", label);
//        sdl5.initColumn("Open");
//        sdl5.showCharts();
//
//        StockDataLoader sdl6 = StockDataLoader.getLocalLoader("2012-01-01", "2012-12-31", label);
//        sdl6.initColumn("Volume");
//        sdl6.showCharts();
    }

    public void loadForSymbol(String symbol, String selectedColumn, BufferedWriter bw) throws IOException {

//        String callUrl = "http://query.yahooapis.com/v1/public/yql?q=select * from yahoo.finance.historicaldata where symbol in (" + key + "\") and startDate=\"" + startDate + "\" and endDate=\"" + endDate + "\"&diagnostics=true&env=http://datatables.org/alltables.env&format=json";
//        String callUrl = "http://query.yahooapis.com/v1/public/yql?q=select%20*%20from%20yahoo.finance.historicaldata%20where%20symbol%20in%20%28%22" + symbol + "%22%29%20and%20startDate=%222009-01-01%22%20and%20endDate=%222009-12-31%22&diagnostics=true&env=http://datatables.org/alltables.env&format=json";
        String callUrl = "http://query.yahooapis.com/v1/public/yql?q=select%20*%20from%20yahoo.finance.historicaldata%20where%20symbol%20in%20%28%22" + symbol + "%22%29%20and%20startDate=%22" + y1 + "-01-01%22%20and%20endDate=%22" + y2 + "-12-31%22&diagnostics=true&env=http://datatables.org/alltables.env&format=json";

        System.out.println(callUrl);

        
        bw.write(symbol + "\t");
        bw.write(callUrl + "\t");

        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpGet httpget = new HttpGet(callUrl);

        HttpResponse response = httpClient.execute(httpget);

        if (debug) {
            System.out.println(response.getProtocolVersion());
            System.out.println(response.getStatusLine().getStatusCode());
            System.out.println(response.getStatusLine().getReasonPhrase());
            System.out.println(response.getStatusLine().toString());
        }

        BufferedReader br = new BufferedReader(
                new InputStreamReader((response.getEntity().getContent())));

        StringBuffer buffer = new StringBuffer();

        String output;
        String s = "";

        if (debug) {
            System.out.println("Output from Server .... \n");
        }

        while ((output = br.readLine()) != null) {
            System.out.println(output);
            buffer = buffer.append(output);
        }

        try {
            s = buffer.toString();
            _temp.put(symbol, s);

            bw.write(s + "\n");

            Object obj = JSONValue.parse(s);
            JSONObject jsonObject = (JSONObject) obj;
            JSONObject query = (JSONObject) jsonObject.get("query");
            JSONObject res = (JSONObject) query.get("results");
            JSONArray qt = (JSONArray) res.get("quote");

//            System.out.println("*** [" + qt + "]");
            String s2 = res.toJSONString();
            int i = 0;

            // This is the time series we are working with
            TimeSeriesObject mr = new TimeSeriesObject();
            mr.setLabel(symbol + "_" + selectedColumn);

            /**
             * TODO: Add better org.opentsx.tsbucket.metadata handling *
             */
            Hashtable<Long, Double> data = new Hashtable<Long, Double>();

            while (i < qt.size()) {
                JSONObject val = (JSONObject) qt.get(i);
                String b = (String) val.get(selectedColumn);

                // this fails sometimes ... it worked with "date" and "Date" ...
                String a = (String) val.get("Date");

                DateFormat df = new SimpleDateFormat("yyyy-MM-dd");

                Date date = df.parse(a);

                data.put(date.getTime(), Double.parseDouble(b));

//                System.out.println(date.getTime() + " # " + a + " : " + b);
//                System.out.println("{" + qt.get(i) + "}");
                i++;
            }

            Set<Long> k = data.keySet();
            ArrayList<Long> liste = new ArrayList<Long>();
            liste.addAll(k);

            Collections.sort(liste);

            for (Long key2 : k) {
//                System.out.println(key2 + " : " + data.get(key2));
                mr.addValuePair(key2, data.get(key2));
            }

            vmr.add(mr);

        } catch (Exception pe) {
            
            // Logger.getLogger(StockDataLoader2.class.getName()).log(Level.INFO, null, pe);

            System.err.println( "#### MISSING SYMBOL: " + symbol + " ####");
            System.err.println( s );
            
            bad.add(symbol);
        }
    }

    // lets track the symbols for which no data was collected.
    Vector<String> bad = new Vector<String>();

    boolean isFilled = false;

    /**
     * The list of Symbols is loaded locally.
     *
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void loadListe() throws FileNotFoundException, IOException {
        if (isFilled) {
            return;
        }
        String file = "./example-data/stocks/" + label;
        File f = new File(file);

        FileReader fr = new FileReader(f);
        BufferedReader br = new BufferedReader(fr);
        while (br.ready()) {
            String line = br.readLine();

            if (line.startsWith("#")) {
            } else {

                StringTokenizer st = new StringTokenizer(line, "\t");
                System.out.println(st.countTokens() + ":" + line);
                String tok1 = st.nextToken();
                String tok2 = st.nextToken();
                System.out.println("(" + tok1 + ") [" + tok2 + "]");

                hash.put(tok1, tok2);

                if (!liste.contains(tok2)) {
                    liste.add(tok2);
                }
            }
        }
//        hash.put("Visa_Inc.","V");
//        hash.put("Walmart","WMT");
//        hash.put("The_Walt_Disney_Company","DIS");

        System.out.println(">>> Stock symbol list is loaded : " + f.getAbsolutePath());
        System.out.println(">   " + f.getAbsolutePath());

        System.out.println(liste.size() + " items.");

    }

    Hashtable<String, String> _temp = null;

    private void getDataCacheWeb(String column) throws IOException {
        _temp = new Hashtable<String, String>();

        int i = 1;

        FileWriter fw = new FileWriter(getFilename());

        BufferedWriter bw = new BufferedWriter(fw);

        for (String s : hash.values()) {

            System.out.println("(" + i + ") SYMBOL: " + s);

            loadForSymbol(s, column, bw);

            i++;
        }
        bw.flush();
        bw.close();
        
        Enumeration en = bad.elements();
        
        int j = 0;
        while (en.hasMoreElements()) {

            System.out.println("(" + j + ") MISSING SYMBOL: " + en.nextElement() );
            j++;
        }
        
    }

    private void loadDataFromWeb(String column) throws IOException {

        System.out.println("> loadDataFromWeb() : cache here ->" + getFilename());

        _temp = new Hashtable<String, String>();

        int i = 1;

        FileWriter fw = new FileWriter(getFilename());

        BufferedWriter bw = new BufferedWriter(fw);

        for (String s : hash.values()) {
            System.out.println("(" + i + ") SYMBOL: " + s);
            loadForSymbol(s, column, bw);
            i++;
        }
        bw.flush();
        bw.close();
    }

    private void initCacheFromWeb(String col) throws FileNotFoundException, IOException {
        column = col;
        loadListe();
        loadDataFromWeb(column);
    }

    private void loadDataFromFile() throws FileNotFoundException, IOException {

        if (isFilled) {
            return;
        }

        System.out.println(">>> loadDataFromFile() ");
        System.out.println(">     " + getFilename());

        _temp = new Hashtable<String, String>();
        vmr = new Vector<TimeSeriesObject>();

        BufferedReader br = new BufferedReader(new FileReader(getFilename()));
        while (br.ready()) {
            String line = br.readLine();
            StringTokenizer st = new StringTokenizer(line, "\t");
            int s = st.countTokens();
            String k = st.nextToken();
            String req = st.nextToken();
            String resp = st.nextToken();
            // System.out.println(s + " :" + k + "\n\t" + req + "\n\t\t" + resp);
            _temp.put(k, resp);
        }
        System.out.println(">>> WS-Responses reloaded from local cache file:");
        System.out.println(">   " + getFilename());

        isFilled = true;
    }

    /**
     * Just show the chart with our default settings. We do not add an
     * additional label.
     *
     */
    private void showCharts() {
        showCharts("");
    }

    /**
     * Here we do add the "debug" label.
     *
     */
    private void getRows(String hlLabel) {

        System.out.println("> TSBucket :: " + vmr.size() + "; column:" + column);
        System.out.println("> HL-label :: " + hlLabel);

        if (vmr.size() < 1) {
            return;
        }
        try {
            System.out.println(">          : " + vmr.elementAt(0).toString());
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        File folder = new File(getFilename()).getParentFile();
        String file = label + "_" + startDate + "_" + endDate + "_" + column;
        String comment = "";

        // MultiTimeSeriesChart.open(vmr, hlLabel + "#" + label + "[" + startDate + " ... " + endDate + "]", "t", column, true);
        // MultiTimeSeriesChart.openAndStore(vmr, hlLabel + "#" + label + "[" + startDate + " ... " + endDate + "]", "t", column, true, folder.getAbsolutePath(), file, comment);
    }

    /**
     * Here we do add the "debug" label.
     *
     */
    private void showCharts(String hlLabel) {

        System.out.println("> TSBucket :: " + vmr.size() + "; column:" + column);
        System.out.println("> HL-label :: " + hlLabel);

        if (vmr.size() < 1) {
            return;
        }
        try {
            System.out.println(">          : " + vmr.elementAt(0).toString());
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        File folder = new File(getFilename()).getParentFile();
        String file = label + "_" + startDate + "_" + endDate + "_" + column;
        String comment = "";

//         MultiTimeSeriesChart.open(vmr, hlLabel + "#" + label + "[" + startDate + " ... " + endDate + "]", "t", column, true);
//         MultiTimeSeriesChart.openAndStore(vmr, hlLabel + "#" + label + "[" + startDate + " ... " + endDate + "]", "t", column, true, folder.getAbsolutePath(), file, comment);
    }

    public String getFilename() {
        File f = new File(".sdl-cache");
        if (!f.exists()) {
            f.mkdir();
        }
        return ".sdl-cache/RDD-stockdata-" + this.startDate + "_" + this.endDate + "_" + label;
    }

    private void initColumn(String col) {

        bad = new Vector();

        vmr = new Vector();

        column = col;
        for (String key : _temp.keySet()) {
            String s = _temp.get(key);

            try {
                Object obj = JSONValue.parse(s);
                JSONObject jsonObject = (JSONObject) obj;
                JSONObject query = (JSONObject) jsonObject.get("query");

                JSONObject res = (JSONObject) query.get("results");
                JSONArray qt = (JSONArray) res.get("quote");

                String s2 = res.toJSONString();
                int i = 0;

                TimeSeriesObject mr = new TimeSeriesObject();
                mr.setLabel(key + "_" + column);

                Hashtable<Long, Double> data = new Hashtable<Long, Double>();

               
                
                JSONObject val = null;

                while (i < qt.size()) {
                    try {
                        val = (JSONObject) qt.get(i);
                        String b = (String) val.get(column);
                        String a = (String) val.get("Date");

                        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");

                        Date date;

                        date = df.parse(a);

                        data.put(date.getTime(), Double.parseDouble(b));

                        if (debug) {

                            String log1 = date.getTime() + " # " + a + " : " + b;
                            String log2 = "{" + qt.get(i) + "}";

                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();

                        String a = (String) val.get("date");
                        System.out.println(a + " => could work." + val);

                    }
                    i++;
                }

                Set<Long> k = data.keySet();
                ArrayList<Long> liste = new ArrayList<Long>();
                liste.addAll(k);

                Collections.sort(liste);

                for (Long key2 : liste) {
                    //System.out.println(key2 + " : " + data.get(key2));
                    mr.addValuePair(key2, data.get(key2));
                }
                vmr.add(mr);

            } catch (Exception ex) {
                System.out.println(key + "   ==> " + ex.getMessage());
                bad.add(key);
            }
        }
    }
}
