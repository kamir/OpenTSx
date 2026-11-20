package org.opentsx.connectors.kafka;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.opentsx.tsa.rng.ReferenceDataset;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.core.TSData;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.VectorWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Vector;

public class TSOWriter{

    private static final Logger LOG = LoggerFactory.getLogger(TSOWriter.class);

    public static void persistBucket_CSV(Vector<TimeSeriesObject> mrs, String title, String tx, String ty, String fn, String csv) {
        MultiChart.store(mrs, title, tx, ty, false, ReferenceDataset.FILE_PATH, fn, "no comment");
    }

    public static void persistBucket_SequenceFile(Vector<TimeSeriesObject> show, String title, String fn, String seq) throws Exception {

        int ANZ = show.size();

        Configuration config = initConfig();
        FileSystem fs = initFileSystem();

        Path path = new Path(ReferenceDataset.FILE_PATH + fn + ".tsb.vec." + seq);

        try (SequenceFile.Writer writer = new SequenceFile.Writer(fs, config, path, Text.class, VectorWritable.class)) {
            for (int i = 0; i < ANZ; i++) {

                TimeSeriesObject mr = show.get(i);

                TSData data = TSData.convertMessreihe(mr);

                NamedVector nv = new NamedVector(new DenseVector(data.getData()), data.label);
                VectorWritable vec = new VectorWritable();
                vec.set(nv);

                writer.append(new Text(nv.getName()), vec);
            }
        }
        LOG.debug("bucket written title={} path={} series={}", title, path, ANZ);

    }



    /**
     * Never implemented; it stores nothing and throws. Use the v2 path instead: {@code org.opentsx.bridge.LegacyBridge}
     * (or {@code LegacyBucketImport} for TSBucket files) to create episodes and
     * {@code org.opentsx.kafka.BucketWriter} to store them.
     */
    @Deprecated
    public static void persistBucket_Kafka(Vector<TimeSeriesObject> show, String title, String topicname) {
        throw new UnsupportedOperationException(topicname + ": persistBucket_Kafka stores nothing; use"
                + " org.opentsx.bridge.LegacyBridge and org.opentsx.kafka.BucketWriter");
    }


    static Configuration config = null;

    public static void setConfig(Configuration conf) {
        config = conf;
    }


    private static Configuration initConfig() {

        if (config == null) {
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
        return FileSystem.get(initConfig());
    }

    public void persistEvents_Kafka(Vector<TimeSeriesObject> tsos, String title, String topicname) {

        TSOEventProducer tsop = new TSOEventProducer();

        tsop.pushTSOItemsToKafka(tsos, topicname);

        LOG.debug("events sent topic={} series={}", topicname, tsos.size());

    }
}
