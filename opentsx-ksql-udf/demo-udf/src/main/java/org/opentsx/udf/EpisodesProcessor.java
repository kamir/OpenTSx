package org.opentsx.udf;

import com.google.gson.Gson;
import io.confluent.ksql.function.udf.Udf;
import io.confluent.ksql.function.udf.UdfDescription;
import io.confluent.ksql.function.udf.UdfParameter;
import org.apache.kafka.connect.data.Struct;

import java.math.BigInteger;

/**
 *
 * select EPISODES_processor( ??? ) as RESULT FROM EPISODES_STREAM EMIT CHANGES;
 *
 */
@UdfDescription(name        = "episodeProcessor",
                description = "calculates arbitrary results from single episodes",
                author      = "Mirko Kämpf",
                version     = "3.0.2" )
public class EpisodesProcessor {


    Gson gson = new Gson();

    @Udf(description = "Calculate arbitrary statistics for an episode object (Impl 1).")
    public String stats1(
            @UdfParameter(value = "TSTART", description = "the TSTART") final Double TSTART,
            @UdfParameter(value = "TEND", description = "the TEND") final Double TEND,
            @UdfParameter(value = "INCREMENT", description = "the INCREMENT") final BigInteger INCREMENT )
    {
        return ( TEND - TSTART ) / (1.0 * INCREMENT.doubleValue()) +"";
    }


    @Udf(description = "Calculate arbitrary statistics for an episode object (Impl 1).")
    public String stats1(
            @UdfParameter(value = "TSTART", description = "the TSTART") final long TSTART,
            @UdfParameter(value = "TEND", description = "the TEND") final long TEND,
            @UdfParameter(value = "INCREMENT", description = "the INCREMENT") final int INCREMENT,
            @UdfParameter(value = "OBSERVATIONS", schema = "ARRAY<STRUCT<TIMESTAMP BIGINT, URI VARCHAR, VALUE DOUBLE>>", description = "the observations array") final Struct OBSERVATIONS)
    {
        return gson.toJson( OBSERVATIONS );
    }

// CREATE STREAM epsidoes_data_A2 (LABEL STRING, TSTART DOUBLE, TEND DOUBLE, ZOBSERVATIONS BIGINT, INCREMENT BIGINT, URI STRING, OBSERVATIONARRAY ARRAY<STRUCT<TIMESTAMP BIGINT, URI VARCHAR, VALUE DOUBLE>> ) WITH (kafka_topic='OpenTSx_Episodes_A', value_format='AVRO');
// SELECT episodeProcessor(TSTART,TEND,INCREMENT) FROM epsidoes_data_A3 emit changes;
// SELECT episodeProcessor(OBSERVATIONARRAY) FROM epsidoes_data_A3 emit changes;


    @Udf(description = "Calculate arbitrary statistics for an episodes object (Impl 2).")
    public double stats2(

            @UdfParameter(value = "OBSERVATIONS", schema = "ARRAY<STRUCT<TIMESTAMP BIGINT, URI VARCHAR, VALUE DOUBLE>>") final Struct OBSERVATIONS)

    {

        return 0.987654321;

    }



    @Udf(description = "Calculate arbitrary statistics for an episodes object (Impl 3).")
    public double statsFromString(
            @UdfParameter(value = "DATA", description = "the DATA as JSON_STRING") final String data)
    {

        return 123.456;

    }



}