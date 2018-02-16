package org.apache.hadoopts.hadoopts.loader;

import org.apache.hadoopts.chart.simple.MultiChart;
import org.apache.hadoopts.data.series.TimeSeriesObject;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.client.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;
import java.util.logging.Logger;

/**
 * @author Mirko Kaempf
 */
public class KuduTelemetryDataLoader {

    private static final Logger LOG = Logger.getLogger(KuduTelemetryDataLoader.class.getName());

    private static String KUDU_MASTER = System.getProperty("kuduMaster", "quickstart.cloudera");
    private static String KUDU_TABLE = System.getProperty("kuduTable", "telemetry");

    boolean debug = false;

    Hashtable<String, String> hash = new Hashtable<String, String>();

    Hashtable<String, TimeSeriesObject> hashMR = new Hashtable<String, TimeSeriesObject>();

    static Vector<String> metricsVector = null;

    String label = "default";

    static long delta = 300 * 1000; // 5 min in ms

    static String metricsList = null;
    static String[] metrics = null;

    static long startTime = 0;
    static long endTime = 0;

    static KuduClient client = null;

    // local time series container which becomes an RDD later on.
    Vector<TimeSeriesObject> vmr = new Vector<TimeSeriesObject>();

    public static void main(String[] arg) throws IOException {

        long t0 = System.currentTimeMillis();

        long t1 = t0 - delta;

        startTime = t0;
        endTime = t1;

        setupKuduClient( "cc-poc-mk-1.gce.cloudera.com" );

        metricsVector = readAllMetricsFromKudu();
        System.out.println("> available metrics in telemetry data:");
        for( String m : metricsVector ) {
            System.out.println( " " + m );
        }



        System.out.println("\n> create some noise:");
        metrics = new String[]{"a_x", "a_y", "a_z"};

        for( String m : metrics ) {
            metricsList = metricsList + "_" + m;
        }

        // createSomeNoise();

        Vector<TimeSeriesObject> tso = readAllSeriesFromKudu();

        MultiChart.open(tso,true);

    }

    private static void createSomeNoise() {

        try {

            List<ColumnSchema> columns = new ArrayList(2);

            columns.add(new ColumnSchema.ColumnSchemaBuilder("id", Type.INT64)
                    .key(true)
                    .build());

            columns.add(new ColumnSchema.ColumnSchemaBuilder("time", Type.STRING)
                    .key(true)
                    .build());

            columns.add(new ColumnSchema.ColumnSchemaBuilder("metric", Type.STRING)
                    .key(true)
                    .build());

            columns.add(new ColumnSchema.ColumnSchemaBuilder("value", Type.DOUBLE)
                    .build());

            List<String> rangeKeys = new ArrayList<>();

            rangeKeys.add("id");

            Schema schema = new Schema(columns);

//            client.createTable(KUDU_TABLE, schema, new CreateTableOptions().setRangePartitionColumns(rangeKeys));

            KuduTable table = client.openTable(KUDU_TABLE);

            KuduSession session = client.newSession();

            /**
             * TODO : Needs bulk mode for upload
             */
            for(String m : metrics ) {

                long t = System.currentTimeMillis();

                for (int i = 0; i < 1000; i++) {

                    System.out.println( "i: " + i );

                    Insert insert = table.newInsert();

                    PartialRow row = insert.getRow();
                    row.addLong(0, 1);
                    row.addString(1, ""+(t+1));
                    row.addString(2, m);
                    row.addDouble(3, Math.random());

                    session.apply(insert);


                }

            }

        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            try {
                client.shutdown();
            }
            catch(Exception ex) {
                ex.printStackTrace();
            }
        }

    }

    private static void setupKuduClient(String km) {
        KUDU_MASTER = km;
        setupKuduClient();
    }

    private static void setupKuduClient() {

        System.out.println("-----------------------------------------------");
        System.out.println("Will try to connect to Kudu master at " + KUDU_MASTER + " using table " + KUDU_TABLE );
        System.out.println("Run with -DkuduMaster=myHost:port -DkuduTable=telemetry to override.");
        System.out.println("-----------------------------------------------");

        client = new KuduClient.KuduClientBuilder(KUDU_MASTER).build();

    }

    private static Vector<TimeSeriesObject> readAllSeriesFromKudu() {

        Hashtable<String,TimeSeriesObject> temp = new Hashtable<String,TimeSeriesObject>();

        for ( String m : metricsVector ) {

            System.out.println( " > m : " + m );

            TimeSeriesObject tso = new TimeSeriesObject();
            tso.setLabel( m );
            temp.put( m, tso );

        }

        try {

            List<String> projectColumns = new ArrayList<>(1);

            projectColumns.add("metric");
            projectColumns.add("time");
            projectColumns.add("value");

            client = new KuduClient.KuduClientBuilder(KUDU_MASTER).build();

            KuduSession session = client.newSession();

            KuduTable table = client.openTable(KUDU_TABLE);

            KuduScanner scanner = client.newScannerBuilder(table)
                    .setProjectedColumnNames(projectColumns)
                    .build();

            while (scanner.hasMoreRows()) {

                RowResultIterator results = scanner.nextRows();

                while (results.hasNext()) {

                    RowResult result = results.next();

                    String m = result.getString("metric");
                    String t = result.getString("time"  );
                    double v = result.getDouble("value" );

                    System.out.println( m + " : " + t + " : " + v );

                    temp.get(m).addValuePair(Double.parseDouble(t),v);

                }

            }

        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

        Vector<TimeSeriesObject> vTSO = new Vector<TimeSeriesObject>();

        for( String key : temp.keySet() ) {
            vTSO.add( temp.get(key));
        }

        return vTSO;

    }


    private static Vector<String> readAllMetricsFromKudu() {

        Vector<String> mTemp = new Vector<String>();

        try {

            List<String> projectColumns = new ArrayList<>(1);

            projectColumns.add("metric");

            client = new KuduClient.KuduClientBuilder(KUDU_MASTER).build();

            KuduSession session = client.newSession();

            KuduTable table = client.openTable(KUDU_TABLE);

            KuduScanner scanner = client.newScannerBuilder(table)
                    .setProjectedColumnNames(projectColumns)
                    .build();

            while (scanner.hasMoreRows()) {

                RowResultIterator results = scanner.nextRows();

                while (results.hasNext()) {

                    RowResult result = results.next();

                    String m = result.getString(0);

                    if ( !mTemp.contains( m )  ) {
                        mTemp.add( m );
                    }

                    System.out.println();

                }

            }

        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

        return mTemp;

    }


    /**
     * Here we do add the "debug" label.
     *
     */
    private void showCharts(String hlLabel) {

        System.out.println("> TSBucket :: " + vmr.size() + "; metrics:" + metricsList);
        System.out.println("> HL-label :: " + hlLabel);

        if (vmr.size() < 1) {
            return;
        }
        try {
            System.out.println(">          : " + vmr.elementAt(0).toString());
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

        File folder = new File(getFilename()).getParentFile();
        String file = label + "_" + startTime + "_" + endTime + "_" + metricsList;

        String comment = "";

        // MultiTimeSeriesChart.open(vmr, hlLabel + "#" + label + "[" + startTime + " ... " + endTime + "]", "t", metric, true);

    }

    public String getFilename() {
        File f = new File(".sdl-cache");
        if (!f.exists()) {
            f.mkdir();
        }
        return ".sdl-cache/RDD-telemetry-kudu-" + KUDU_MASTER + "_" + startTime + "_" + endTime + "_" + label;
    }


}
