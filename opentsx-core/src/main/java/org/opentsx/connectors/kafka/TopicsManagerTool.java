package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.admin.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class TopicsManagerTool {



    /**
     * This is the TOPIC-DEFINITION-File
     */
    public static String TOPICS_DEF_FN = "/Users/mkampf/GITHUB.public/OpenTSx/config/topiclist.def";

    public static void listTopics() {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(TSOProducer.get_PROPS_FN() )));
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        AdminClient adminClient = AdminClient.create(properties);

        ListTopicsResult ltr = adminClient.listTopics();

        while( !ltr.namesToListings().isDone() ) {
            try {
                Thread.sleep(1000); //sleep for 1 millisecond before checking again
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        };

        try {
            Collection<TopicListing> col = ltr.listings().get();
            for ( TopicListing tl : col ) {
                if ( !tl.isInternal() )
                    if ( !tl.name().startsWith("_") )
                        System.out.println( tl.toString() );
            }
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        adminClient.close();

    }

    public static void createTopic(String tn, int partitions, int repl, int min_isr) {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(TSOProducer.get_PROPS_FN() )));
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        AdminClient adminClient = AdminClient.create(properties);
        NewTopic newTopic = new NewTopic(tn, partitions, (short)repl);

        List<NewTopic> newTopics = new ArrayList<NewTopic>();
        newTopics.add(newTopic);

        adminClient.createTopics(newTopics);
        adminClient.close();

    }

    public static void createTopic(NewTopic newTopic) {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(TSOProducer.get_PROPS_FN() )));
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        AdminClient adminClient = AdminClient.create(properties);

        List<NewTopic> newTopics = new ArrayList<NewTopic>();
        newTopics.add(newTopic);

        adminClient.createTopics(newTopics);
        adminClient.close();

    }

    public static void createTopics(List<NewTopic> newTopics) {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(TSOProducer.get_PROPS_FN() )));
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        AdminClient adminClient = AdminClient.create(properties);

        CreateTopicsResult ctr = adminClient.createTopics(newTopics);

        System.out.println( ctr );
        System.out.println(" >>> Task is not completed yet....");

        while (!ctr.all().isDone()) {

            try {
                Thread.sleep(1000); //sleep for 1 millisecond before checking again
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        System.out.println(" >>> Task is done.");

        adminClient.close();

    }

    public static void deleteTopic(String tn) {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(TSOProducer.get_PROPS_FN() )));
            System.out.println(">>> DELETE TOPIC : " + tn);
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        AdminClient adminClient = AdminClient.create(properties);

        List<String> topicNames = new ArrayList<String>();
        topicNames.add(tn);

        adminClient.deleteTopics( topicNames );
        adminClient.close();

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

    public static void initTopicDefinitions(String topicsDefFn) {
        System.out.println( ">>> Overwrite topicsDefFN ... ");
        System.out.println( ">>> OLD: " + TOPICS_DEF_FN );
        TOPICS_DEF_FN = topicsDefFn;
        System.out.println( ">>> NEW: " + TOPICS_DEF_FN );
        System.out.println( " " );
    }

    public static void createTopics() throws IOException {

        Vector<NewTopic> topics =  TopicsManagerTool.getTopicDefs();

        TopicsManagerTool.createTopics( topics );

    }

    public static void deleteTopics() throws IOException {

        Vector<String> liste = TopicsManagerTool.getTopicNames();

        for( String tn : liste) {
            System.out.print("*** DELETE TOPIC: *** ["+tn+"]");
            TopicsManagerTool.deleteTopic(tn);
            System.out.println(" DONE ");
        }

    }
}
