package org.opentsx.connectors.kafka.topicmanager;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.opentsx.connectors.kafka.TopicsManagerTool;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;

import java.io.IOException;
import java.util.Vector;

/**
 * Checks that every topic of the topic list exists. Exit status: 0 when all exist, 1 when one is missing or the check
 * fails.
 */
public class TopicsCHECK {

    public static void main(String[] ARGS) throws Exception {
        System.exit(status());
    }

    public static boolean execute() throws Exception {
        return status() == 0;
    }

    static int status() throws Exception {

        System.out.println("[TopicsCHECK]");

        OpenTSxClusterLink.init();

        TopicsManagerTool.initTopicDefinitions( TopicsUP.get_TOPICS_DEF_FN() );

        try (AdminClient admin = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            return status(admin);
        }

    }

    static int status(Admin admin) throws IOException, InterruptedException {

        Vector<String> missing = TopicsManagerTool.getMissingTopics(admin);
        if( missing.isEmpty() ) {
            System.out.println(">>> PASS CHECK. All topics available.");
            return 0;
        }
        System.out.println(TopicsManagerTool.TOPICS_DEF_FN + ": topics missing on the cluster " + missing
                + "; create them with TopicsUP");
        return 1;

    }

}
