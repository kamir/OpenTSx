package org.opentsx.lg.kping;

import org.apache.avro.generic.GenericRecord;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.kafka.clients.consumer.*;

import org.bouncycastle.math.ec.ScaleYPointMap;
import org.opentsx.connectors.kafka.kping.TSOEventAnalysisConsumer;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.util.OpenTSxClusterLink;
import org.semanpix.chart.simple.MultiChart;

import java.io.File;
import java.util.Vector;

public class EventFlowAnalysisConsumer {

    private final static String TOPIC = "OpenTSx_Events";

    static int GIVE_UP = 100;
    static int NR_OF_SAMPLES = 1000;

    public static void main(String[] args) throws InterruptedException {

        /**
         * Read environment variables to configure the tool ...
         */
        OpenTSxClusterLink.init();

        GIVE_UP = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "kping.consumer.giveUp") );
        NR_OF_SAMPLES = Integer.parseInt( OpenTSxClusterLink.getClientProperties().getProperty( "kping.consumer.nrOfSamples") );

        runConsumer();
    }

    static void runConsumer() throws InterruptedException {

        TimeSeriesObject tso = new TimeSeriesObject();

        tso.setLabel( "dt" );

        final Consumer<Long, GenericRecord> consumer = TSOEventAnalysisConsumer.createConsumer( TOPIC );

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

                    // System.out.printf("delay: %d [ms] : dt2: %d [ms] :: %d :: %d ::- offset = %d, key = %s, value = %s \n", dt, dt2, dt - dt2, tC - tEC, record.offset(), record.key(), record.value());

                });


                // consumer.commitAsync();

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

        System.out.println( comment );

        String title = "latency per message";
        String x = "relative offset";
        String y = "latency [ms]";

        boolean b = true;
        String folder = "./kping-out/";

        String filename = "lateny-by-message";

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

        //MultiChart.openAndStore( vtso, title, x, y, b, folder, filename, comment );
        MultiChart.open( vtso, title, x, y, b, comment, null );

        System.out.println("DONE");

    }

}
