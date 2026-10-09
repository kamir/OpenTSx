import cassandra.CassandraConnector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;

import org.opentsx.data.series.TimeSeriesObject;


import java.io.IOException;

import java.util.Vector;

public class TSOWriter4Cassandra {

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


}
