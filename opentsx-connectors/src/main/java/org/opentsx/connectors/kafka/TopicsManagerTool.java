package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class TopicsManagerTool {

    private static final Logger LOG = LoggerFactory.getLogger(TopicsManagerTool.class);

    /**
     * This is the default TOPIC-DEFINITION-File
     */
    public static String TOPICS_DEF_FN = "config/topiclist.def";

    public static Vector<String> getTopicList() throws InterruptedException {
        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            return getTopicList(adminClient);
        }
    }

    public static Vector<String> getTopicList(Admin adminClient) throws InterruptedException {

        Vector<String> liste = new Vector<>();

        try {
            for ( TopicListing tl : adminClient.listTopics().listings().get() ) {
                if ( !tl.isInternal() )
                    if ( !tl.name().startsWith("_") )
                        liste.add( tl.name() );
            }
        }
        catch (ExecutionException e) {
            throw new IllegalStateException(OpenTSxClusterLink.get_PROPS_FN() + ": could not list the topics; "
                    + e.getCause().getMessage(), e.getCause());
        }

        LOG.debug("topics listed count={} topics={}", liste.size(), liste);

        return liste;
    }

    public static Vector<String> listTopics() throws InterruptedException {
        return getTopicList();
    }

    public static void createTopic(String tn, int partitions, int repl, int min_isr) throws InterruptedException {
        createTopic(new NewTopic(tn, partitions, (short)repl));
    }

    public static void createTopic(NewTopic newTopic) throws InterruptedException {
        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            createTopics(adminClient, List.of(newTopic));
        }
    }

    public static void createTopics(List<NewTopic> newTopics) throws InterruptedException {
        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            createTopics(adminClient, newTopics);
        }
    }

    /** Creates each topic that does not exist yet; an existing topic is kept as it is. */
    public static void createTopics(Admin adminClient, List<NewTopic> newTopics) throws InterruptedException {
        for( NewTopic nt : newTopics ) {
            LOG.debug("creating the topic topic={} partitions={} replication={}", nt.name(), nt.numPartitions(),
                    nt.replicationFactor());
            try {
                adminClient.createTopics(List.of(nt)).all().get();
            }
            catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw new IllegalStateException(nt.name() + ": could not create the topic; "
                            + e.getCause().getMessage(), e.getCause());
                }
                LOG.debug("topic exists, kept topic={}", nt.name());
            }
        }
    }

    public static void deleteTopic(String tn) throws InterruptedException {
        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            deleteTopics(adminClient, List.of(tn));
        }
    }

    /** Deletes each topic; a topic that does not exist is reported and skipped. */
    public static void deleteTopics(Admin adminClient, List<String> names) throws InterruptedException {
        for( String tn : names ) {
            try {
                adminClient.deleteTopics(List.of(tn)).all().get();
                LOG.debug("topic deleted topic={}", tn);
            }
            catch (ExecutionException e) {
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw new IllegalStateException(tn + ": could not delete the topic; "
                            + e.getCause().getMessage(), e.getCause());
                }
                LOG.debug("topic does not exist, skipped topic={}", tn);
            }
        }
    }

    public static Vector<String> getTopicNames() throws IOException {
        Vector<String> topicNames = new Vector<String>();
        try (BufferedReader br = new BufferedReader( new FileReader( TOPICS_DEF_FN ) )) {
            // we skip line 0
            br.readLine();
            String line;
            while( (line = br.readLine()) != null ) {
                topicNames.add(line.split(",")[0]);
            }
        }
        LOG.debug("topic list read path={} topics={}", TOPICS_DEF_FN, topicNames);
        return topicNames;
    }

    public static Vector<NewTopic> getTopicDefs() throws IOException {
        Vector<NewTopic> topics = new Vector<NewTopic>();
        try (BufferedReader br = new BufferedReader( new FileReader( TOPICS_DEF_FN ) )) {
            // we skip line 0
            br.readLine();
            String line;
            while( (line = br.readLine()) != null ) {
                String[] FIELDS = line.split(",");
                String name = FIELDS[0];
                Integer partitions = Integer.parseInt(FIELDS[1]);
                Integer repl = Integer.parseInt(FIELDS[2]);
                NewTopic nt = new NewTopic(name, partitions, (short) repl.intValue());
                topics.add(nt);
            }
        }
        LOG.debug("topic definitions read path={} count={}", TOPICS_DEF_FN, topics.size());
        return topics;
    }

    /** Sets the topic list file and returns the names of its topics. */
    public static Vector<String> initTopicDefinitions(String topicsDefFn) throws IOException {

        TOPICS_DEF_FN = topicsDefFn;

        return getTopicNames();

    }

    public static void createTopics() throws IOException, InterruptedException {

        Vector<NewTopic> topics =  TopicsManagerTool.getTopicDefs();

        TopicsManagerTool.createTopics( topics );

    }

    public static void deleteTopics() throws IOException, InterruptedException {

        Vector<String> liste = TopicsManagerTool.getTopicNames();

        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            deleteTopics(adminClient, liste);
        }

    }


    public static boolean checkAllTopicsAvailable() throws IOException, InterruptedException {
        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            return checkAllTopicsAvailable(adminClient);
        }
    }

    public static boolean checkAllTopicsAvailable(Admin adminClient) throws IOException, InterruptedException {
        return getMissingTopics(adminClient).isEmpty();
    }

    /** The topics of the topic list that the cluster does not have, in the order of the list. */
    public static Vector<String> getMissingTopics(Admin adminClient) throws IOException, InterruptedException {

        Vector<String> listOfKnownTopics = TopicsManagerTool.getTopicList(adminClient);

        Vector<String> missingTopics = new Vector<String>();

        for( String expectedName : TopicsManagerTool.getTopicNames() ) {
            if ( !listOfKnownTopics.contains( expectedName ) ) {
                missingTopics.add( expectedName );
            }
        }

        return missingTopics;
    }
}
