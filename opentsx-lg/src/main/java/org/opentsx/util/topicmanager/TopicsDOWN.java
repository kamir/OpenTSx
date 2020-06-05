package org.opentsx.util.topicmanager;

import org.opentsx.connectors.kafka.TopicsManagerTool;
import org.opentsx.util.OpenTSxClusterLink;

public class TopicsDOWN {

    public static void main(String[] ARGS) throws Exception {

        OpenTSxClusterLink.init();

        TopicsManagerTool.initTopicDefinitions( TopicsUP.get_TOPICS_DEF_FN() );
        TopicsManagerTool.deleteTopics();

        System.out.println("> All topics removed ! ");
        System.exit(0);

    }

}
