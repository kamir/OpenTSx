package org.opentsx.tsa.rng;

public class RefDS {

    public static String EXP_OFFSET = "XYZ";

    public static String CASSANDRA_KS = "ks1";
    public static String CASSANDRA_TN = "refds_small_bucket_table_" + EXP_OFFSET;

    public static String topicname = "refds_small_bucket_topic_" + EXP_OFFSET;
    public static String event_topicname = "refds_events_topic_" + EXP_OFFSET;

    public static String FILE_PATH = "./data/out/tsb/ref_ds/";  //must end with "/"
    public static String fn = "refds_small_bucket_" + EXP_OFFSET;

    public static String tablename = "refds_small_bucket_tab_" + EXP_OFFSET;


}
