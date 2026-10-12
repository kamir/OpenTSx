package org.opentsx.market;

import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.SeriesKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a collected series means: archive name, v2 metric and tags, unit and origin.
 *
 * @param source  archive source directory, e.g. {@code smard}
 * @param name    archive series name, e.g. {@code day_ahead_price} (may contain {@code /} for station sub-directories)
 * @param metric  v2 metric, e.g. {@code market.price.day_ahead}
 * @param tags    v2 tags (identity), e.g. {@code area=DE-LU}
 * @param unit    UCUM-like unit as used in the project ({@code EUR/MWh}, {@code MW}, {@code Cel}, {@code W/m2}, {@code m/s})
 * @param origin  {@code REPORTED} (market results, TSO publications), {@code MEASURED} (weather stations),
 *                {@code FORECAST} (published forecasts)
 * @param licence licence and attribution text written into manifests and episode labels
 */
public record SeriesDef(String source, String name, String metric, Map<String, String> tags, String unit,
                        String origin, String licence) {

    public SeriesDef {
        tags = Map.copyOf(tags);
    }

    public SeriesKey key() {
        Map<String, String> t = new LinkedHashMap<>(tags);
        t.put("source", source);
        return SeriesKeys.of(metric, t, unit);
    }

    /** {@code source/name}, the archive path of the series. */
    public String path() {
        return source + "/" + name;
    }
}
