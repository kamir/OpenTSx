package org.opentsx.windpark.catalog;

import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.SeriesKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Asset inventory of one park: park -> turbines -> channels (from the catalog). */
public record Park(String id, String name, double latitude, double longitude, List<Turbine> turbines) {

    public Park {
        turbines = List.copyOf(turbines);
    }

    /**
     * Grid layout: {@code rows x columns} turbines, spacing in rotor diameters. Turbine ids T01, T02, ... row-major.
     * Rows run east-west (columns spaced along x), rows are offset to the north (y).
     */
    public static Park grid(String id, String name, double lat, double lon, TurbineType type, int rows, int columns,
                            double spacingD, double rowSpacingD) {
        List<Turbine> ts = new ArrayList<>();
        int n = 1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                ts.add(new Turbine(String.format("T%02d", n++), type, c * spacingD * type.rotorDiameterM(),
                        r * rowSpacingD * type.rotorDiameterM()));
            }
        }
        return new Park(id, name, lat, lon, ts);
    }

    public Turbine turbine(String id) {
        return turbines.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown turbine " + id + " in park " + this.id));
    }

    /** Series identity of a channel of a turbine: metric + {park, turbine}. */
    public SeriesKey seriesKey(ChannelDef channel, String turbineId) {
        return SeriesKeys.of(channel.metric(), Map.of("park", id, "turbine", turbineId), channel.unit());
    }

    /** Series of an aggregate (e.g. 10-min mean) of a channel: extra tag {@code aggregation=mean.PT10M}. */
    public SeriesKey seriesKey(ChannelDef channel, String turbineId, String aggregationTag) {
        if (aggregationTag == null) {
            return seriesKey(channel, turbineId);
        }
        return SeriesKeys.of(channel.metric(), Map.of("park", id, "turbine", turbineId, "aggregation", aggregationTag), channel.unit());
    }

    /** Every (turbine, channel) series of the park - the inventory OpenMetadata and the simulator work from. */
    public List<SeriesKey> inventory(ChannelCatalog catalog) {
        List<SeriesKey> out = new ArrayList<>();
        for (Turbine t : turbines) {
            for (ChannelDef c : catalog.channels()) {
                out.add(seriesKey(c, t.id()));
            }
        }
        return out;
    }
}
