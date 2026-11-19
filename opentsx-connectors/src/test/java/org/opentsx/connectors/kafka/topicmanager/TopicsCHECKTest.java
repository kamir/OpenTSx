package org.opentsx.connectors.kafka.topicmanager;

import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.connectors.kafka.TopicsManagerTool;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicsCHECKTest {

    private static final Node BROKER = new Node(0, "127.0.0.1", 1);

    @TempDir
    Path dir;

    private String previousTopicList;

    @BeforeEach
    void useProbeTopicList() throws Exception {
        previousTopicList = TopicsManagerTool.TOPICS_DEF_FN;
        Path topics = dir.resolve("topiclist.def");
        Files.writeString(topics, "name,partitions,replication\ntsx_a,1,1\ntsx_b,1,1\n");
        TopicsManagerTool.TOPICS_DEF_FN = topics.toString();
    }

    @AfterEach
    void restoreTopicList() {
        TopicsManagerTool.TOPICS_DEF_FN = previousTopicList;
    }

    private static void addTopic(MockAdminClient admin, String name) {
        admin.addTopic(false, name, List.of(new TopicPartitionInfo(0, BROKER, List.of(BROKER), List.of(BROKER))), Map.of());
    }

    @Test
    void aMissingTopicExitsWithStatus1AndIsNamedOnStdout() throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER)) {
            addTopic(admin, "tsx_a");
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(1, TopicsCHECK.status(admin));
        }
        finally {
            System.setOut(previous);
        }
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains(TopicsManagerTool.TOPICS_DEF_FN
                + ": topics missing on the cluster [tsx_b]; create them with TopicsUP"), printed);
    }

    @Test
    void everyListedTopicExitsWithStatus0() throws Exception {
        try (MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER)) {
            addTopic(admin, "tsx_a");
            addTopic(admin, "tsx_b");
            assertEquals(0, TopicsCHECK.status(admin));
        }
    }
}
