package org.opentsx.lg.kping;

import org.apache.avro.generic.GenericRecord;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.kafka.clients.consumer.*;

import org.opentsx.connectors.kafka.OpenTSxClusterLink;
import org.opentsx.connectors.kafka.kping.TSOEventAnalysisConsumer;
import org.opentsx.data.exporter.MeasurementTable;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.chart.simple.MultiChart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Vector;

public class EventFlowAnalysisConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(EventFlowAnalysisConsumer.class);

    private final static String TOPIC = "OpenTSx_Events";

    static int GIVE_UP = 100;
    static int NR_OF_SAMPLES = 1000;

    static final String PLOT_FOLDER = System.getProperty("user.home") + "/opentsx/plots";

    public static void main(String[] args) {

        LOG.info("starting EventFlowAnalysisConsumer");

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        GIVE_UP = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "kping.consumer.giveUp") );
        NR_OF_SAMPLES = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "kping.consumer.nrOfSamples") );

        runConsumer(TSOEventAnalysisConsumer.createConsumer( TOPIC ), System.getenv("OPENTSX_SHOW_GUI"), PLOT_FOLDER);

    }

    /**
     * Measures the latency of the events until {@link #GIVE_UP} polls in a row are empty, then writes the table to
     * {@code folder} or, with {@code showGui} true, opens and stores the chart.
     */
    static void runConsumer(Consumer<Long, GenericRecord> consumer, String showGui, String folder) {

        TimeSeriesObject tso = new TimeSeriesObject();

        tso.setLabel( TOPIC );

        final int giveUp = GIVE_UP;   int noRecordsCount = 0;

        try {

            while (true) {

                final ConsumerRecords<Long, GenericRecord> consumerRecords =
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

                    long tP2 = (long) record.value().get("producer_timestamp");
                    long tEC = (long) record.value().get("timestamp");

                    long tP = record.timestamp();

                    long dt = tC - tP;
                    long dt2 = tC - tP2;

                    tso.addValue( dt );

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


        comment = comment.concat("\n");
        comment = comment.concat("Min: " + tso.getMinY() + "\n" );
        comment = comment.concat("Max: " + tso.getMaxY() + "\n" );
        comment = comment.concat("P75: " + p75.evaluate() + "\n" );
        comment = comment.concat("P95: " + p95.evaluate() + "\n" );
        comment = comment.concat("P99: " + p99.evaluate() + "\n" );

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
