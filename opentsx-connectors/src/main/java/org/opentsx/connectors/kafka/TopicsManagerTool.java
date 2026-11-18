package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class TopicsManagerTool {

    /**
     * This is the default TOPIC-DEFINITION-File
     */
    public static String TOPICS_DEF_FN = "config/topiclist.def";

    public static Vector<String> getTopicList() throws InterruptedException {

        Vector<String> liste = new Vector<>();

        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
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

        for( String tn : liste ) {
            System.out.println("TOPIC: --> " + tn);
        }

        return liste;
    }

    public static Vector<String> listTopics() throws InterruptedException {

        Vector<String> l = getTopicList();

        for( String n : l )
            System.out.println( "> TOPIC --> {" + n + "}" );

        return l;

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

        System.out.println("*********> CREATE TOPICS : " + newTopics.size());

        try (AdminClient adminClient = AdminClient.create(OpenTSxClusterLink.getClientProperties())) {
            createTopics(adminClient, newTopics);
        }

        System.out.println("*********> List topics ...");

        TopicsManagerTool.listTopics();

    }

    /** Creates each topic that does not exist yet; an existing topic is kept as it is. */
    public static void createTopics(Admin adminClient, List<NewTopic> newTopics) throws InterruptedException {
        for( NewTopic nt : newTopics ) {
            System.out.println("--->>>" + nt.toString() );
            try {
                adminClient.createTopics(List.of(nt)).all().get();
            }
            catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw new IllegalStateException(nt.name() + ": could not create the topic; "
                            + e.getCause().getMessage(), e.getCause());
                }
                System.out.println("> topic exists: " + nt.name());
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
                System.out.println("*** DELETE TOPIC: *** [" + tn + "] deleted");
            }
            catch (ExecutionException e) {
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw new IllegalStateException(tn + ": could not delete the topic; "
                            + e.getCause().getMessage(), e.getCause());
                }
                System.out.println("*** DELETE TOPIC: *** [" + tn + "] does not exist");
            }
        }
    }

    public static Vector<String> getTopicNames() throws IOException {
        File f = new File(TOPICS_DEF_FN);
        FileReader fr = new FileReader( f );
        BufferedReader br = new BufferedReader( fr );

        // we skip line 0
        String lin0 = br.readLine();
        System.out.println( TOPICS_DEF_FN + " => (R: " + f.canRead() +")" );
        System.out.println( "[HEADER] ");

        Vector<String> topicNames = new Vector<String>();
        boolean go = true;
        while( go ) {

            String line = br.readLine();

            if (!(line==null) ) {

                String name = line.split(",")[0];
                topicNames.add(name);

            }
            else {

                go = false;

            }

        }
        return topicNames;
    }

    public static Vector<NewTopic> getTopicDefs() throws IOException {
        File f = new File(TOPICS_DEF_FN);
        FileReader fr = new FileReader( f );
        BufferedReader br = new BufferedReader( fr );

        // we skip line 0
        String line0 = br.readLine();
        System.out.println( "[TOPIC DEFINITION FILE] ");
        System.out.println( TOPICS_DEF_FN + "(r: " + f.canRead() +")" );
        System.out.println( "[HEADER] ");
        System.out.println( line0 );

        System.out.println( "[CONTENT] ");
        Vector<NewTopic> topics = new Vector<NewTopic>();
        boolean go = true;
        while( go ) {
            String line = br.readLine();
            if (!(line==null) ) {
                System.out.println(line);

                String[] FIELDS = line.split(",");
                String name = FIELDS[0];
                Integer partitions = Integer.parseInt(FIELDS[1]);
                Integer repl = Integer.parseInt(FIELDS[2]);
                NewTopic nt = new NewTopic(name, partitions, (short) repl.intValue());
                topics.add(nt);
            }
            else go = false;
        }
        System.out.println( "{NUMBER OF TOPICS: " + topics.size() + "}");


        return topics;
    }

    public static Vector<String> initTopicDefinitions(String topicsDefFn) {

        System.out.println( ">>> Overwrite topicsDefFN ... ");
        System.out.println( ">>> OLD: " + TOPICS_DEF_FN );
        TOPICS_DEF_FN = topicsDefFn;
        System.out.println( ">>> NEW: " + TOPICS_DEF_FN );
        System.out.println( " " );

        return getExpectedTopicList();

    }

    private static Vector<String> getExpectedTopicList() {
        return null;
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

        Vector<String> listOfKnownTopics = TopicsManagerTool.getTopicList();
        Vector<String> listOfExpectedTopics = TopicsManagerTool.getTopicNames();

        Vector<String> missingTopics = new Vector<String>();

        for( String expectedName : listOfExpectedTopics ) {
            boolean isAvailable = listOfKnownTopics.contains( expectedName );
            System.out.println( "TOPIC : " + expectedName + " => " + isAvailable );
            listOfKnownTopics.remove( expectedName );
            if ( !isAvailable )
                missingTopics.add( expectedName );
        }

        System.out.println( "[MISSING TOPICS]: => " + missingTopics.size() );
        for( String m : missingTopics ) {
            System.out.println  ( "  - " + m );
        }

        return missingTopics.size() == 0;
    }
}
