package org.opentsx.kstreams;

import com.google.gson.Gson;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.opentsx.core.TSData;
import org.opentsx.data.series.TimeSeriesObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


import static org.apache.kafka.streams.Topology.AutoOffsetReset.LATEST;

public class StateStoreExample4 {

  private static final Logger LOG = LoggerFactory.getLogger(StateStoreExample4.class);

  public static void main(String[] args) throws Exception {

    String EXP_OFFSET = "RefDS.EXP_OFFSET";



    /**
     *
     * Collect datapoints per metric and create TSOs ...
     *
     * During stream processing we use only String and JSON representation, not yet a
     * real custom SerDe.
     *
     *
     */

    StreamsConfig streamsConfig = new StreamsConfig(getProperties());

    StreamsBuilder builder = new StreamsBuilder();

    Serde<String> stringSerde1 = Serdes.String();
    Serde<String> stringSerde2 = Serdes.String();

    Serde<String> stringSerde3 = Serdes.String();
    Serde<String> stringSerde4 = Serdes.String();

    long twoMinutes = 1000 * 60 * 1;
    long fiveMinutes = 1000 * 60 * 2;

    Initializer<String> initializer = new Initializer<String>() {
      @Override
      public String apply() {
        return new String();
      }
    };

    Aggregator<? super String, ? super String, String> aggregator = new Aggregator<String, String, String>() {
      @Override
      public String apply(String key, String value, String agg) {
        agg = agg.concat( "," + value );
        return agg;
      }
    };


    Merger<? super String, String> merger = new Merger<String, String>() {
      @Override
      public String apply(String key, String a, String b) {
        a = a.concat( b );
        return a;
      }
    };

    KTable<Windowed<String>, String> measuredDataPointGroups = builder.stream("refds_events_topic_" + EXP_OFFSET, Consumed.with(stringSerde1, stringSerde2).withOffsetResetPolicy(LATEST))
                    .groupBy((key, dp) -> getTSOKey( key ),
                            Serialized.with(stringSerde3, stringSerde4))
                    // session window comment line below and uncomment another line below for a different window example
                    .windowedBy(SessionWindows.with(twoMinutes).until(fiveMinutes)).aggregate(
                            initializer,
                            aggregator,
                            merger);

    measuredDataPointGroups.toStream()
            .mapValues( (k,v) -> createTSO(k,v) )
            .to("TSO-collection-" + EXP_OFFSET);
    // .print(Printed.<Windowed<String>, String>toSysOut().withLabel("measuredDataPointGroups"));





    //The following are examples of different windows examples

    //Tumbling window with timeout 15 minutes
    //.windowedBy(TimeWindows.of(twentySeconds).until(fifteenMinutes)).count();

    //Tumbling window with default timeout 24 hours
    //.windowedBy(TimeWindows.of(twentySeconds)).count();

    //Hopping window
    //.windowedBy(TimeWindows.of(twentySeconds).advanceBy(fiveSeconds).until(fifteenMinutes)).count();


            //.print(Printed.toSysOut().withLabel("measuredDataPointGroups"));

    KafkaStreams kafkaStreams = new KafkaStreams(builder.build(), streamsConfig);
    kafkaStreams.cleanUp();

    kafkaStreams.setUncaughtExceptionHandler((t, e) -> {
      LOG.error("had exception ", e);
    });

    LOG.info("Starting CountingWindowing and KTableJoins Example");
    kafkaStreams.cleanUp();
    kafkaStreams.start();
    Thread.sleep(65000);
    LOG.info("Shutting down the CountingWindowing and KTableJoins Example Application now");
    kafkaStreams.close();

    
  }


  private static String createTSO(Windowed<String> k, String v) {

    TimeSeriesObject tso = new TimeSeriesObject();

    Gson gson = new Gson();

    if ( v != null ) {

      tso.setLabel( k.key() );

      String data = "{ points: [" + v.substring(1, v.length()) + "] }";


      Points p = gson.fromJson(data, Points.class);
      Hashtable<Double,Double> pointsHT = new Hashtable<>();

      for( Point point : p.points ) {
        pointsHT.put( point.ts , point.value );
      }
      List<Double> keyList = new ArrayList<Double>();
      keyList.addAll( pointsHT.keySet() );
      Collections.sort( keyList );
      for(Double keyTemp : keyList ) {
          tso.addValuePair( keyTemp, pointsHT.get( keyTemp ));
      }
    }
    else {
      tso.setLabel("empty");
    }

    TSData data = TSData.convertMessreihe( tso );

    String dataJSON = gson.toJson(data);

    return dataJSON;

  }

  private static String getTSOKey(String key) {

    int end = key.lastIndexOf("_");
    String groupKey = key.substring(0,end);
    return groupKey;

  }

  public static Properties getProperties() {

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "StateStoreExample_04_" + System.currentTimeMillis() );
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

    return props;

  }




}

class Points {
  public Point[] points;
}

class Point {
  public double ts;
  public double value;
}
