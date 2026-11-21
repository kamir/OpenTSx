package org.opentsx.lg.klatency;

import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.opentsx.chart.simple.MultiChart;
import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.klatency.LatencyTestEventConsumer;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.model.LatencyTesterEvent;
import org.opentsx.data.series.TimeSeriesObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Vector;
import java.util.function.Function;

public class RequestResponseAnalysisConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(RequestResponseAnalysisConsumer.class);

    private final static String TOPIC = LatencyTestEventConsumer.TOPIC_for_response_events_string;

    static int GIVE_UP = 100;

    static int NR_OF_SAMPLES = 1000;

    static Function<String, Consumer<String, String>> consumers = LatencyTestEventConsumer::createConsumer;

    public static void main(String[] args) {

        LOG.info("starting RequestResponseAnalysisConsumer");

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        GIVE_UP = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "klatency.consumer.giveUp") );
        NR_OF_SAMPLES = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "klatency.consumer.nrOfSamples") );

        int status = run(consumers.apply( TOPIC ), System.getenv("OPENTSX_SHOW_GUI"), plotFolder());
        if (status != 0) {
            System.exit(status);
        }

    }

    static String plotFolder() {
        return System.getProperty("user.home") + "/opentsx/plots";
    }

    /**
     * Runs {@link #runConsumer} and returns 0, or 1 after one ERROR line when it throws IllegalStateException, as it
     * does when no response arrived.
     */
    static int run(Consumer<String, String> consumer, String showGui, String folder) {
        try {
            runConsumer(consumer, showGui, folder);
            return 0;
        } catch (IllegalStateException failed) {
            LOG.error("no latency measured topic={} cause={}", TOPIC, failed.getMessage());
            LOG.debug("no latency measured topic={}", TOPIC, failed);
            return 1;
        }
    }

    /**
     * Measures the end-to-end latency of the responses until more than {@link #GIVE_UP} polls in total are empty, then
     * writes the table to {@code folder} or, with {@code showGui} true, opens and stores the chart. When no response
     * arrived, getStatisticData throws IllegalStateException and nothing is written.
     */
    static void runConsumer(Consumer<String, String> consumer, String showGui, String folder) {

        TimeSeriesObject tso = new TimeSeriesObject();

        tso.setLabel( TOPIC );

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
                        LOG.debug("empty poll topic={} emptyPolls={} giveUp={}", TOPIC, noRecordsCount, giveUp);
                        continue;
                    }
                }

                consumerRecords.forEach(record -> {

                    long tC = System.currentTimeMillis();

                    LatencyTesterEvent e = LatencyTesterEvent.fromJson( record.value() );

                    e.trackResultReceivedTS();

                    tso.addValuePair( (double)e.sendTS, (double)e.getEnd2EndLatency() );

                    System.out.println( e.getStats() );

                });




            }

            consumer.commitAsync();

        }
        finally {
            consumer.close();
        }

        Vector<TimeSeriesObject> vtso = new Vector<TimeSeriesObject>();

        System.out.println( tso.getStatisticData( ">> ") );

        String comment = OpenTSxClusterLink.getPropsAsString_NO_SECURITY_PROPS();

        System.out.println( comment );

        String title = "latency per message";
        String x = "relative offset";
        String y = "latency [ms]";

        boolean b = true;

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

        if ( !Boolean.parseBoolean( showGui ) ) {

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
