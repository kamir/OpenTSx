package tswb.tsa.examples;

import connectors.opentsdb.OpenTSDBConnector;

import org.apache.spark.SparkConf;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.logging.Level;

public class OpenTSDBStreamer {

    public static void main(String[] args ) throws Exception {

        SLF4JBridgeHandler.install();
        java.util.logging.LogManager.getLogManager().getLogger("").setLevel( Level.INFO);

        // Create a local StreamingContext with two working thread and batch interval of 1 second
        SparkConf conf = new SparkConf().setMaster("local[2]").setAppName("NetworkWordCount");

        JavaStreamingContext jssc = new JavaStreamingContext(conf, Durations.seconds(1));



        OpenTSDBConnector connector = new OpenTSDBConnector();
        connector.openSocket();



        JavaReceiverInputDStream<String> lines = jssc.socketTextStream("localhost", 9999);

        //JavaDStream<String> words = lines.flatMap(x -> Arrays.asList(x.split(" ")).iterator());

        //JavaPairDStream<String, Integer> pairs = words.mapToPair(s -> new Tuple2<>(s, 1));
        //JavaPairDStream<String, Integer> wordCounts = pairs.reduceByKey((i1, i2) -> i1 + i2);

        // Print the first ten elements of each RDD generated in this DStream to the console
        //wordCounts.print();

        // OpenTSDBEvent ev = new OpenTSDBEvent();
        // connector.sendEventViaSocket( ev );

        jssc.start();              // Start the computation
        jssc.awaitTermination();   // Wait for the computation to terminate


        // Whatever comes in ...

        // We generate OpenTSDBEvents and persist them using the OpenTSDBConnector in Telnet Streaming Mode ...





        connector.close();

    }

}
