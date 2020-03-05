package org.opentsx.lg;

import org.opentsx.connectors.kafka.TopicsManagerTool;

import java.io.File;

/**
 * An experiment with a floating-workload comes with a:
 *    TopiscUP and a
 *    TopicsDOWN program.
 *
 * The two programs allow the preparation of the required topics, so that the data producer and the
 * workload container have their places to store and to retrieve data.
 */

public class TopicsUP {

    public static void init_TOPICS_DEF_FN(String fn){
        File f = new File(fn);
        System.out.println( "> TOPICS_DEF_FN Path: " + f.getAbsolutePath() );
        if ( f.canRead() )
            TOPICS_DEF_FN = fn;
        else {
            System.out.println("> Attempt to overwrite TOPICS_DEF_FN in org.opentsx.lg.TopicsUP failed.");
        }
        System.out.println("> TOPICS_DEF_FN=" + TOPICS_DEF_FN);
    };
    private static String TOPICS_DEF_FN = "config/topiclist.def";
    public static String get_TOPICS_DEF_FN() {
        return  TOPICS_DEF_FN;
    };

    public static void main(String[] ARGS) throws Exception{

        OpenTSxClusterLink.init();

        TopicsManagerTool.initTopicDefinitions( TOPICS_DEF_FN );
        TopicsManagerTool.createTopics();

        System.out.println("> All topics created ! ");

        TopicsManagerTool.listTopics();

        System.exit(0);

    }
}
