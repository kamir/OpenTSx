package org.opentsx.connectors.kafka.topicmanager;

import org.opentsx.connectors.kafka.TopicsManagerTool;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

public class TopicsDOWN {

    public static void main(String[] ARGS) throws Exception {

        System.out.println("[TopicsDOWN]");

        OpenTSxClusterLink.init();

        TopicsManagerTool.initTopicDefinitions( TopicsUP.get_TOPICS_DEF_FN() );
        TopicsManagerTool.deleteTopics();

        System.out.println("> All topics removed ! ");
        System.exit(0);

    }

}
