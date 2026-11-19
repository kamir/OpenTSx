package org.opentsx.store.opentsdb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Store contract against the embedded emulator, or against a real OpenTSDB when OPENTSDB_URL is set
 * (an OpenTSDB instance you run yourself; docker-compose.local.yml has no OpenTSDB service; use a fresh instance, the contract expects an empty store).
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

    @Test
    void aBadRequestOtherThanAnUnknownMetricFailsTheRead() {
        assumeTrue(emulator != null, "needs the emulator to answer a bad tag filter with 400 Invalid tag value");
        store.write(fixture());
        SeriesSelector bad = SeriesSelector.metric(WIND_T01.getMetric()).tag("turbine", "T#01");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.read(EpisodeQuery.of(bad, T0, T0 + 3600 * SEC)));
        assertEquals("OpenTSDB POST " + emulator.uri() + "api/query returned 400: "
                + "{\"error\":{\"code\":400,\"message\":\"Invalid tag value (\\\"T#01\\\"): illegal character: #\"}}", e.getMessage());
    }

    @Test
    void aFailedConnectionNamesTheUrl() throws IOException {
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        OpenTsdbSeriesStore closed = new OpenTsdbSeriesStore(URI.create("http://127.0.0.1:" + port + "/"));
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> closed.read(EpisodeQuery.of(SeriesSelector.metric(WIND_T01.getMetric()), T0, T0 + SEC)));
        assertEquals("OpenTSDB POST http://127.0.0.1:" + port + "/api/query failed", e.getMessage());
    }

    @Test
    void aBodyThatCannotBeEncodedNamesTheMethodAndTheUrl() {
        OpenTsdbSeriesStore unused = new OpenTsdbSeriesStore(URI.create("http://127.0.0.1:4242/"));
        ObjectNode body = JsonNodeFactory.instance.objectNode().putPOJO("value", new Object());
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> unused.post("/api/put?summary", body, false));
        assertEquals("OpenTSDB POST http://127.0.0.1:4242/api/put?summary: cannot encode the request body as JSON",
                e.getMessage());
        assertInstanceOf(JsonProcessingException.class, e.getCause());
    }

    @Test
    void aMetricListThatReachesTheSuggestLimitFails() {
        assumeTrue(emulator != null, "needs the emulator to list 10000 metrics");
        List<Episode> fixture = fixture();
        store.write(fixture);
        long written = fixture.stream().map(e -> e.getSeries().getMetric()).distinct().count();
        for (int i = 0; i < 10_000 - written; i++) {
            emulator.suggestOnly.add(String.format("synthetic.m%05d", i));
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.read(EpisodeQuery.of(SeriesSelector.all(), T0, T0 + 3600 * SEC)));
        assertEquals("metrics: /api/suggest returned 10000 metrics, its limit, so the list may be incomplete;"
                + " select a metric in the SeriesSelector", e.getMessage());
    }

    @Test
    void twoIdenticalReadsAreEqual() {
        store.write(fixture());
        EpisodeQuery q = EpisodeQuery.of(SeriesSelector.all(), T0, T0 + 3600 * SEC);
        assertEquals(store.read(q), store.read(q));
    }

    @Test
    void noSourceFileCarriesALiteralProducerVersion() throws IOException {
        Pattern literal = Pattern.compile("opentsx-[a-z0-9-]+/[0-9]");
        List<Path> scanned;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            scanned = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(scanned.contains(Path.of("src/main/java/org/opentsx/store/opentsdb/OpenTsdbSeriesStore.java")), scanned.toString());
        List<String> hits = new ArrayList<>();
        for (Path f : scanned) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (literal.matcher(lines.get(i)).find()) {
                    hits.add(f + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), hits);
    }
}
