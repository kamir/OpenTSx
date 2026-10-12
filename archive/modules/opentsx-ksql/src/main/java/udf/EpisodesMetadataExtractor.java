package udf;

import io.confluent.ksql.function.udf.Udf;
import io.confluent.ksql.function.udf.UdfDescription;
import io.confluent.ksql.function.udf.UdfParameter;

/**
 * select EPISODES_MD( TSTART, TEND ) as MD FROM EPISODES_STREAM EMIT CHANGES;
 *
 * select ROWKEY, TSTART, TEND, INCREMENT EPISODES_MD( TSTART, TEND ) as MD1, EPISODES_MD( TSTART, TEND, INCREMENT ) as MD2 FROM EPISODES_STREAM EMIT CHANGES;
 */
@UdfDescription(name        = "mdExtract",
                description = "Extracts metadata from single episodes.",
                author      = "Mirko Kämpf",
                version     = "3.0.1" )
public class EpisodesMetadataExtractor {

    @Udf(description = "Extracts metadata from episode object.")
    public double extractMD_nr_of_values(
            @UdfParameter(value = "TSTART", description = "START time") final long TSTART,
            @UdfParameter(value = "TEND", description = "END time") final long TEND,
            @UdfParameter(value = "INCREMENT", description = "time INCREMENT") final int INCREMENT
    )
    {
        return  (double)(TEND - TSTART) / (double)INCREMENT;
    }

}