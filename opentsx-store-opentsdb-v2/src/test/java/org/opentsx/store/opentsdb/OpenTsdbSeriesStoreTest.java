package org.opentsx.store.opentsdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.SeriesStoreContract;
import org.opentsx.store.WriteResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Store contract against the embedded emulator, or against a real OpenTSDB when OPENTSDB_URL is set
 * (e.g. the opentsdb service of docker-compose.local.yml; use a fresh instance, the contract expects an empty store).
 */
class OpenTsdbSeriesStoreTest extends SeriesStoreContract {

    private OpenTsdbEmulator emulator;

    @Override
    protected SeriesStore newStore() {
        String url = System.getenv("OPENTSDB_URL");
        if (url != null && !url.isBlank()) {
            return new OpenTsdbSeriesStore(URI.create(url.endsWith("/") ? url : url + "/"));
        }
        try {
            emulator = new OpenTsdbEmulator();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new OpenTsdbSeriesStore(emulator.uri());
    }

    @AfterEach
    void stopEmulator() {
        if (emulator != null) {
            emulator.close();
        }
    }

    @Test
    void nanPointsAreDroppedAndCountedAndWritesAreBatched() {
        Episode e = Episodes.regular(WIND_T01, T0, SEC, new double[120]).build();
        double[] v = Episodes.values(e);
        v[3] = Double.NaN;
        Episode withNan = Episodes.regular(WIND_T01, T0, SEC, v).build();
        WriteResult r = store.write(List.of(withNan));
        assertEquals(1, r.droppedPoints());
        if (emulator != null) {
            assertEquals(3, emulator.putRequests); // 119 points in batches of 50
        }
        assertEquals(119, Episodes.values(store.read(EpisodeQuery.of(SeriesSelector.metric(WIND_T01.getMetric()), T0, T0 + 3600 * SEC)).get(0)).length);
    }

    @Test
    void unitRoundTripsAsReservedTagAndInvalidNamesAreRejected() {
        store.write(List.of(Episodes.regular(POWER_T01, T0, SEC, new double[]{1, 2}).build()));
        assertEquals("kW", store.listSeries(SeriesSelector.metric(POWER_T01.getMetric())).get(0).getUnit());
        assertEquals(POWER_T01.getSeriesId(), store.listSeries(SeriesSelector.all()).get(0).getSeriesId());

        Episode bad = Episodes.regular(SeriesKeys.of("wind speed", Map.of("t", "1")), T0, SEC, new double[]{1}).build();
        assertThrows(IllegalArgumentException.class, () -> store.write(List.of(bad)));
        Episode noTags = Episodes.regular(SeriesKeys.of("wind.speed", Map.of()), T0, SEC, new double[]{1}).build();
        assertThrows(IllegalArgumentException.class, () -> store.write(List.of(noTags)));
    }
}
