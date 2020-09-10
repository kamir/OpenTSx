package org.opentsx.tsa.rng;

public class ReferenceDataset {

    // public static String EXPERIMENT__TAG = "meetup_09_2020";
    public static String EXPERIMENT__TAG = "ccloud_demo_09_2020";




    public static String CASSANDRA_KS = "cks1";
    public static String CASSANDRA_TN = "refds_small_bucket_table_" + EXPERIMENT__TAG;

    public static String topicname = "refds_small_bucket_topic_" + EXPERIMENT__TAG;

    public static String event_topicname = "refds_events_topic_" + EXPERIMENT__TAG;

    public static String FILE_PATH = "./data/out/tsb/ref_ds/";  //must end with "/"
    public static String fn = "refds_small_bucket_" + EXPERIMENT__TAG;

    // public static String tablename = "refds_small_bucket_tab_" + EXPERIMENT__TAG;


}
