package udf;

import io.confluent.ksql.function.udf.Udf;
import io.confluent.ksql.function.udf.UdfDescription;
import io.confluent.ksql.function.udf.UdfParameter;

import java.math.BigInteger;

/**
 *
 * select EPISODES_MD( TSTART, TEND ) as MD FROM EPISODES_STREAM EMIT CHANGES;
 *
 * select ROWKEY, TSTART, TEND, INCREMENT EPISODES_MD( TSTART, TEND ) as MD1, EPISODES_MD( TSTART, TEND, INCREMENT ) as MD2 FROM EPISODES_STREAM EMIT CHANGES;
 *
 */
@UdfDescription(name = "episodes_md",
        description = "calculates metadata for single episodes", author = "Mirko Kämpf", version = "3.0.0" )
public class EpisodesMetadata {

    @Udf(description = "Calculate descriptive statistics for an episode object.")
    public double descriptiveStats(

            @UdfParameter(value = "TSTART", description = "the TSTART") final long TSTART,
            @UdfParameter(value = "TEND", description = "the TEND") final long TEND,
            @UdfParameter(value = "INCREMENT", description = "the INCREMENT") final int INCREMENT

    )
    {

        return  (double)(TEND - TSTART) / (double)INCREMENT;

    }

    @Udf(description = "Calculate descriptive statistics for an episodes object.")
    public long descriptiveStats(

            @UdfParameter(value = "TSTART", description = "the TSTART") final long TSTART,
            @UdfParameter(value = "TEND", description = "the TEND") final long TEND

    )
    {

        return TEND - TSTART;

    }

}