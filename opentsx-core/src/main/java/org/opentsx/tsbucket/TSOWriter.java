package org.opentsx.tsbucket;

import org.opentsx.connectors.cassandra.CassandraConnector;
import org.opentsx.connectors.kafka.TSOEventProducer;
import org.opentsx.connectors.kafka.TSOProducer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.semanpix.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.TSData;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.VectorWritable;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Vector;

public class TSOWriter{

    public static void persistBucket_CSV(Vector<TimeSeriesObject> mrs, String title, String tx, String ty, String fn, String csv) {
        MultiChart.store(mrs, title, tx, ty, false, ReferenceDataset.FILE_PATH, fn, "no comment");
    }

    public static void persistBucket_SequenceFile(Vector<TimeSeriesObject> show, String title, String fn, String seq) throws Exception {

        DecimalFormat df = new DecimalFormat("0.000");

        int ANZ = show.size();

        System.out.println("--> create bucket : TSBucket -> " + title);

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(ReferenceDataset.FILE_PATH + fn + ".tsb.vec." + seq);
        System.out.println("--> create bucket : " + path.toString());

        // write a SequenceFile form a Vector
        SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class);

        for (int i = 0; i < ANZ; i++) {

            TimeSeriesObject mr = show.get(i);

            TSData data = TSData.convertMessreihe(mr);

            System.out.print("   (" + i + ")\t");

            NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
            VectorWritable vec = new VectorWritable();
            vec.set(nv);

            writer.append(new Text(nv.getName()), vec);
        }

        writer.close();
        System.out.println("### DONE : " + path.toString());

    }

    public static void persistBucket_Cassandra(Vector<TimeSeriesObject> show, String title, String tablename) {

        int ANZ = show.size();

        System.out.println("--> populate Cassandra table : TSBucket -> " + tablename);

        CassandraConnector cc = new CassandraConnector();
        cc.connect();

        for (int i = 0; i < ANZ; i++) {

            TimeSeriesObject mr = show.get(i);

            cc.insertTSOByLabel(mr);

        }

        cc.close();

        System.out.println("### DONE: Pushed " + show.size() + " TSOs to topic: " + tablename + " ###");

    }

    public static void persistBucket_Kafka(Vector<TimeSeriesObject> show, String title, String topicname) {

        int ANZ = show.size();

        System.out.println("--> populate Kafka TSO topic : TSBucket -> " + topicname);

        TSOProducer tsop = new TSOProducer();
/*
        tsop.pushTSOItemsToKafka(show);
*/
        System.out.println("### !!! NOT YET DONE: Pushed " + show.size() + " TSOs to topic: ????" ); // + tsop.TOPIC + " ###");

    }


    static Configuration config = null;

    public static void setConfig(Configuration conf) {
        config = conf;
    }


    private static Configuration initConfig() {

        if (config == null) {
            System.out.println(">>> create new Configuration() ...");
            config = new Configuration();
        }

        config.set("fs.hdfs.impl",
                org.apache.hadoop.hdfs.DistributedFileSystem.class.getName()
        );
        config.set("fs.file.impl",
                org.apache.hadoop.fs.LocalFileSystem.class.getName()
        );

        return config;
    }

    private static FileSystem initFileSystem() throws IOException {

        FileSystem fs = null;

        try {

            fs = FileSystem.get(initConfig());

            if (initConfig() == null) {
                System.err.println("!!! ERROR !!! ");
            }

            if (fs == null) {
                System.err.println("!!! ERROR !!! ");
            }

            System.out.println(fs);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return fs;
    }

    public void persistEvents_Kafka(Vector<TimeSeriesObject> tsos, String title, String topicname) {

        int ANZ = tsos.size();

        System.out.println("--> populate Kafka events topic : TSBucket -> " + topicname);

        TSOEventProducer tsop = new TSOEventProducer();

        tsop.pushTSOItemsToKafka(tsos);

        System.out.println("### DONE: Pushed " + tsos.size() + " TSO-events to topic: " + tsop.TOPIC + " ###");

    }
}
