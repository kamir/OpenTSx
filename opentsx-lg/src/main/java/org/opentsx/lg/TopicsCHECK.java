package org.opentsx.lg;

import org.opentsx.connectors.kafka.TopicsManagerTool;

public class TopicsCHECK {

    public static void main(String[] ARGS) throws Exception {

        OpenTSxClusterLink.init();

        TopicsManagerTool.initTopicDefinitions( TopicsUP.get_TOPICS_DEF_FN() );

        boolean allAvailable = TopicsManagerTool.checkAllTopicsAvailable();

        if( allAvailable ) {
            System.out.println(">>> PASS CHECK. All topics available.");
            System.exit(0);
        }
        else {
            System.out.println("!!! ERROR !!! >>> Missing topics. Can't execute demo.");
            System.exit(-1);
        }

    }

}
