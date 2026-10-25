package org.opentsx.store.iceberg;

import org.opentsx.store.SeriesStore;
import java.util.Map;

public final class IcebergSeriesStore {
    public static SeriesStore open(Map<String, String> properties, String namespace) {
        throw new IllegalStateException("writer factory selected");
    }

    public static SeriesStore openExisting(Map<String, String> properties, String namespace) {
        if (!properties.equals(Map.of("name", "synthetic")) || !namespace.equals("synthetic")) {
            throw new AssertionError("catalog properties or namespace changed");
        }
        throw new IllegalStateException("existing-only factory selected");
    }
}
