package org.opentsx.udf;

import io.confluent.ksql.function.udf.Udf;
import io.confluent.ksql.function.udf.UdfDescription;
import io.confluent.ksql.function.udf.UdfParameter;

/**
 * select mdExtract( TSTART, TEND ) as LENGTH FROM EPISODES_STREAM EMIT CHANGES;
 */
@UdfDescription(name        = "mdExtract",
                description = "Extracts metadata from single episods.",
                author      = "Mirko Kämpf",
                version     = "3.0.1" )
public class EpisodesMetadataExtractor {

    @Udf(description = "Calculates length of an episode (in ms).")
    public double calc_length(
            @UdfParameter(value = "TSTART", description = "START time") final long TSTART,
            @UdfParameter(value = "TEND", description = "END time") final long TEND
    )
    {
        return  (double)(TEND - TSTART);
    }

}





