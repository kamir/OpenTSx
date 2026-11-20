package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.admin.MockAdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicsManagerToolTest {

    private static final Node BROKER = new Node(0, "127.0.0.1", 1);

    @Test
    void aFailedTopicCreationThrowsAndNamesTheTopic() throws Exception {
        try (MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER)) {
            admin.timeoutNextRequest(1);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> TopicsManagerTool.createTopics(admin, List.of(new NewTopic("tsx_probe", 1, (short) 1))));
            assertTrue(e.getMessage().startsWith("tsx_probe: "), e.getMessage());
            assertTrue(admin.listTopics().names().get().isEmpty());
        }
    }

    @Test
    void anExistingTopicIsNotCreatedAgainAndIsNoError() throws Exception {
        try (MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER)) {
            admin.addTopic(false, "tsx_probe", List.of(new TopicPartitionInfo(0, BROKER, List.of(BROKER), List.of(BROKER))), Map.of());
            TopicsManagerTool.createTopics(admin, List.of(new NewTopic("tsx_probe", 1, (short) 1),
                    new NewTopic("tsx_other", 1, (short) 1)));
            assertEquals(Set.of("tsx_probe", "tsx_other"), admin.listTopics().names().get());
        }
    }

    @Test
    void aFailedTopicDeletionThrowsAndNamesTheTopic() throws Exception {
        try (MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER)) {
            admin.addTopic(false, "tsx_probe", List.of(new TopicPartitionInfo(0, BROKER, List.of(BROKER), List.of(BROKER))), Map.of());
            admin.timeoutNextRequest(1);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> TopicsManagerTool.deleteTopics(admin, List.of("tsx_probe")));
            assertTrue(e.getMessage().startsWith("tsx_probe: "), e.getMessage());
            assertEquals(Set.of("tsx_probe"), admin.listTopics().names().get());
        }
    }

    @Test
    void anAbsentTopicIsNoErrorWhenDeleting() throws Exception {
        try (MockAdminClient admin = new MockAdminClient(List.of(BROKER), BROKER)) {
            TopicsManagerTool.deleteTopics(admin, List.of("tsx_absent"));
            assertTrue(admin.listTopics().names().get().isEmpty());
        }
    }

    @Test
    void theTopicListIsReadFromConfigInTheWorkingDirectory(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/topiclist.def"), "name,partitions,replication\ntsx_a,1,1\ntsx_b,2,1\n");
        Path output = dir.resolve("output.txt");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path"),
                PrintTopicNames.class.getName()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("OPENTSX_TOPIC_MAP_FILE_NAME");
        int status = builder.start().waitFor();
        String out = Files.readString(output);
        assertEquals(0, status, out);
        assertTrue(out.contains("TOPICS=[tsx_a, tsx_b]"), out);
    }

    @Test
    void initTopicDefinitionsReturnsTheTopicsOfTheList(@TempDir Path dir) throws Exception {
        Path list = dir.resolve("topiclist.def");
        Files.writeString(list, "name,partitions,replication\ntsx_a,1,1\ntsx_b,2,1\n");
        String previous = TopicsManagerTool.TOPICS_DEF_FN;
        try {
            assertEquals(List.of("tsx_a", "tsx_b"), TopicsManagerTool.initTopicDefinitions(list.toString()));
            assertEquals(list.toString(), TopicsManagerTool.TOPICS_DEF_FN);
        }
        finally {
            TopicsManagerTool.TOPICS_DEF_FN = previous;
        }
    }

    public static class PrintTopicNames {
        public static void main(String[] args) throws Exception {
            System.out.println("TOPICS=" + TopicsManagerTool.getTopicNames());
        }
    }
}
