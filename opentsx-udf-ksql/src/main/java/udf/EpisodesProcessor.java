package udf;

import io.confluent.ksql.function.udf.Udf;
import io.confluent.ksql.function.udf.UdfDescription;
import io.confluent.ksql.function.udf.UdfParameter;
import org.apache.kafka.connect.data.Struct;

/**
 *
 * select EPISODES_processor( ??? ) as RESULT FROM EPISODES_STREAM EMIT CHANGES;
 *
 */
@UdfDescription(name = "episodes_processor",
        description = "calculates arbitrary results from single episodes", author = "Mirko Kämpf", version = "3.0.0" )
public class EpisodesProcessor {

    @Udf(description = "Calculate arbitrary statistics for an episode object (Impl 1).")
    public double stats(

            @UdfParameter(value = "TSTART", description = "the TSTART") final long TSTART,
            @UdfParameter(value = "TEND", description = "the TEND") final long TEND,
            @UdfParameter(value = "INCREMENT", description = "the INCREMENT") final int INCREMENT,
            @UdfParameter(schema = "ARRAY<STRUCT<TIMESTAMP BIGINT, URI VARCHAR, VALUE DOUBLE>>") final Struct OBSERVATIONS)

    {



        // return OBSERVATIONS.getArray("OBSERVATIONARRAY" ).size();
        return 12345.67890;

    }


    @Udf(description = "Calculate arbitrary statistics for an episodes object (Impl 2).")
    public double stats(

            @UdfParameter(schema = "ARRAY<STRUCT<TIMESTAMP BIGINT, URI VARCHAR, VALUE DOUBLE>>") final Struct OBSERVATIONS)

    {

        return 0.987654321;

    }

    @Udf(description = "Calculate arbitrary statistics for an episodes object (Impl 3).")
    public double statsFromString(

            @UdfParameter(value = "DATA", description = "the DATA as JSON_STRING") final String data)

    {

        return 111.222;

    }



}