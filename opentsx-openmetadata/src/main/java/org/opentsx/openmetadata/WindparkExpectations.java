package org.opentsx.openmetadata;

import org.opentsx.stats.DataQuality;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.ChannelDef;
import org.opentsx.windpark.catalog.ChannelKind;

import java.util.LinkedHashMap;
import java.util.Map;

/** Data-quality expectations derived from the data-point catalog (plausible band, sampling interval). */
public final class WindparkExpectations {

    private WindparkExpectations() {
    }

    /**
     * Per metric: completeness against the catalog sampling, share outside [min, max], gaps. The flatline (stuck
     * sensor) check is only applied to analog meteorological channels (logical node WMET): power, speeds, pitch and
     * states are legitimately constant while a turbine stands still or runs at rated power.
     */
    public static Map<String, DataQuality.Expectation> of(ChannelCatalog catalog) {
        Map<String, DataQuality.Expectation> out = new LinkedHashMap<>();
        for (ChannelDef c : catalog.channels()) {
            DataQuality.Expectation e = DataQuality.Expectation.of(c.min(), c.max(), c.sampling());
            if (c.kind() != ChannelKind.ANALOG || !"WMET".equals(c.iec().logicalNode())) {
                e = e.withoutFlatline();
            }
            out.put(c.metric(), e);
        }
        return out;
    }
}
