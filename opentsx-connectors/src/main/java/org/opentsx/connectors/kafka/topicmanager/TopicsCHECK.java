package org.opentsx.connectors.kafka.topicmanager;

import org.opentsx.connectors.kafka.TopicsManagerTool;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

public class TopicsCHECK {

    public static void main(String[] ARGS) throws Exception {

        System.out.println("[TopicsCHECK]");

        OpenTSxClusterLink.init();

        TopicsManagerTool.initTopicDefinitions( TopicsUP.get_TOPICS_DEF_FN() );

        boolean allAvailable = TopicsManagerTool.checkAllTopicsAvailable();

        if( allAvailable ) {
            System.out.println("");
            System.out.println(">>> PASS CHECK. All topics available.");
            System.out.println("");
            System.exit(0);
        }
        else {
            System.out.println("");
            System.out.println("!!! ERROR !!! >>> Missing topics. Can't execute demo.");
            System.out.println("");
            System.exit(-1);
        }



    }

    public static boolean execute() throws Exception {

        System.out.println("[TopicsCHECK]");

        OpenTSxClusterLink.init();

        TopicsManagerTool.initTopicDefinitions( TopicsUP.get_TOPICS_DEF_FN() );

        boolean allAvailable = TopicsManagerTool.checkAllTopicsAvailable();

        if( allAvailable ) {

            System.out.println("");
            System.out.println(">>> PASS CHECK. All topics available.");
            System.out.println("");

            return true;
        }
        else {
            System.out.println("");
            System.out.println("!!! ERROR !!! >>> Missing topics. Can't execute processing scenario.");
            System.out.println("");

            return false;

        }



    }


}
