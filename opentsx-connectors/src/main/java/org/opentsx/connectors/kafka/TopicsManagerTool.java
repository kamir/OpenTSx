package org.opentsx.connectors.kafka;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class TopicsManagerTool {

    /**
     * This is the default TOPIC-DEFINITION-File
     */
    public static String TOPICS_DEF_FN = "/Users/mkampf/GITHUB.public/OpenTSx/config/topiclist.def";

    public static Vector<String> getTopicList() {

        Vector<String> liste = new Vector<>();

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN() )));
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
                        liste.add( tl.name() );
            }
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        for( String tn : liste ) {
            System.out.println("TOPIC: --> " + tn);
        }


        adminClient.close();

        return liste;
    }

    public static Vector<String> listTopics() {

        Vector<String> l = getTopicList();

        for( String n : l )
            System.out.println( "> TOPIC --> {" + n + "}" );

        return l;

    }

    public static void createTopic(String tn, int partitions, int repl, int min_isr) {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN() )));
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
            properties.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN() )));
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

        System.out.println(" ");
        System.out.println(" ");
        System.out.println("*********> CREATE TOPICS : " + newTopics.size());
        System.out.println(" ");
        System.out.println(" ");

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN() )));
        }
        catch (IOException e) {
            e.printStackTrace();
        }

        AdminClient adminClient = AdminClient.create(properties);


        for( NewTopic nt : newTopics ) {

            System.out.println("--->>>" + nt.toString() );

            Vector<NewTopic> vnt = new Vector<NewTopic>();
            vnt.add( nt );

            Map<String, KafkaFuture<Void>> createFutures = adminClient.createTopics(vnt).values();

            System.out.println(" ");
            for( String s : createFutures.keySet() ) {
                System.out.println(">>>>>>>>>>" + s );
                KafkaFuture<Void> a = createFutures.get(s);
                while( !a.isDone() ) {

                    try {
                        System.out.println(" . ");
                        Thread.sleep(1000); //sleep for 1 second before checking again
                    }
                    catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
                System.out.println( a.toString() );
            }
            System.out.println(" ");

            int z = 0;
          /*  while (!ctr.all().isDone()) {

                System.out.println( "---------> " + z );
                z++;

                try {
                    Thread.sleep(1000); //sleep for 1 second before checking again
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
*/


        }

        System.out.println("*********> Task is done.");

        System.out.println("*********> List topics ...");

        TopicsManagerTool.listTopics();

        adminClient.close();

    }

    public static void deleteTopic(String tn) {

        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(OpenTSxClusterLink.get_PROPS_FN() )));
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


    public static boolean checkAllTopicsAvailable() {

        Vector<String> listOfKnownTopics = TopicsManagerTool.getTopicList();
        Vector<String> listOfExpectedTopics = null;
        try {

            listOfExpectedTopics = TopicsManagerTool.getTopicNames();

        }
        catch (IOException e) {
            e.printStackTrace();
        }

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
