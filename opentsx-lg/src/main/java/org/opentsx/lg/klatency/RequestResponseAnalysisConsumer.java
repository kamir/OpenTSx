package org.opentsx.lg.klatency;

import org.apache.avro.generic.GenericRecord;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.klatency.LatencyTestEventConsumer;
import org.opentsx.connectors.kafka.klatency.LatencyTestEventProducer;
import org.opentsx.connectors.kafka.kping.TSOEventAnalysisConsumer;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.model.LatencyTesterEvent;
import org.opentsx.data.series.TimeSeriesObject;

import java.io.File;
import java.util.Vector;
import java.util.logging.Logger;

public class RequestResponseAnalysisConsumer {

    static Logger log = Logger.getLogger(RequestResponseAnalysisConsumer.class.getName());

    private final static String TOPIC = LatencyTestEventConsumer.TOPIC_for_response_events_string;
    // private final static String TOPIC = LatencyTestEventProducer.TOPIC_for_request_events_string;

    static int GIVE_UP = 100;

    static int NR_OF_SAMPLES = 1000;

    public static void main(String[] args) throws InterruptedException {

        log.info( "Start RequestResponseAnalysisConsumer ..." );

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        GIVE_UP = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "klatency.consumer.giveUp") );
        NR_OF_SAMPLES = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "klatency.consumer.nrOfSamples") );

        runConsumer();

    }

    static void runConsumer() throws InterruptedException {


        TimeSeriesObject tso = new TimeSeriesObject();

        final Consumer<String, String> consumer = LatencyTestEventConsumer.createConsumer( TOPIC );

        final int giveUp = GIVE_UP;

        int noRecordsCount = 0;

        try {

            while (true) {

                final ConsumerRecords<String, String> consumerRecords =
                        consumer.poll(50);

                /**
                 * We count every empty poll and if this counter is larger
                 * than our give-up threshold we simple give up and stop the consumer.
                 */
                if (consumerRecords.count() == 0) {
                    noRecordsCount++;
                    if (noRecordsCount > giveUp) break;
                    else {
                        System.out.println( "---------------------------------------------------------------------- ");
                        System.out.println( ">>> " + (100.0 * ((double)noRecordsCount / (double)giveUp)) + " % of IDLE TIME IS OVER " + giveUp + " - " + noRecordsCount + " . ");
                        System.out.println( "---------------------------------------------------------------------- ");
                        continue;
                    }
                }

                consumerRecords.forEach(record -> {

                    long tC = System.currentTimeMillis();

                    LatencyTesterEvent e = LatencyTesterEvent.fromJson( record.value() );

                    e.trackResultReceivedTS();

                    tso.addValuePair( (double)e.sendTS, (double)e.getEnd2EndLatency() );

                    System.out.println( e.getStats() );

                    // System.out.printf("delay: %d [ms] : dt2: %d [ms] :: %d :: %d ::- offset = %d, key = %s, value = %s \n", dt, dt2, dt - dt2, tC - tEC, record.offset(), record.key(), record.value());

                });




            }

            consumer.commitAsync();
            consumer.close();


        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

        Vector<TimeSeriesObject> vtso = new Vector<TimeSeriesObject>();

        System.out.println( tso.getStatisticData( ">> ") );

        String comment = OpenTSxClusterLink.getPropsAsString_NO_SECURITY_PROPS();

        /**
         * the ENV VARIABLE OPENTSX_SHOW_GUI can turn off the GUI.
         */
        String gui_prop = System.getenv("OPENTSX_SHOW_GUI");


        System.out.println( comment );

        String title = "latency per message";
        String x = "relative offset";
        String y = "latency [ms]";

        boolean b = true;
        String folder = "./opentsx/plots";

        long t0 = System.currentTimeMillis();
        String filename = "latency-by-message-" + t0;

        Percentile p75 = new Percentile( 75.0 );
        Percentile p95 = new Percentile( 95.0 );
        Percentile p99 = new Percentile( 99.0 );

        p75.setData( tso.getYData() );
        p95.setData( tso.getYData() );
        p99.setData( tso.getYData() );

        System.out.println( "Min: " + tso.getMinY() );
        System.out.println( "Max: " + tso.getMaxY() );
        System.out.println( "P75: " + p75.evaluate() );
        System.out.println( "P95: " + p95.evaluate() );
        System.out.println( "P99: " + p99.evaluate() );
        System.out.println( "#  : " + tso.getXValues().size() );


        comment = comment.concat("\n");
        comment = comment.concat("Min: " + tso.getMinY() + "\n" );
        comment = comment.concat("Max: " + tso.getMaxY() + "\n" );
        comment = comment.concat("P75: " + p75.evaluate() + "\n" );
        comment = comment.concat("P95: " + p95.evaluate() + "\n" );
        comment = comment.concat("P99: " + p99.evaluate() + "\n" );
        comment = comment.concat("#  : " + tso.getXValues().size() + "\n" );


        vtso = tso.getSeriesWithPercentiles(100);

        if ( gui_prop.equals( "false" ) ) {

            File f = new File( folder + "/" + filename );
            MeasurementTable t = new MeasurementTable();
            t.setMessReihen( vtso );
            t.writeToFile( f );

        }
        else {

            MultiChart.openAndStore(vtso, title, x, y, b, folder, filename, comment);
            MultiChart.open(vtso, title, x, y, b, comment, null);

        }
        System.out.println("DONE");

    }

}
