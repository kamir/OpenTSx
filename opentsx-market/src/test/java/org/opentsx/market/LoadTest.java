package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.iceberg.IcebergSeriesStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentsx.market.ParsersTest.t;

class LoadTest {

    @Test
    void archiveLoadsAsEpisodesIntoIceberg(@TempDir Path dir) {
        Archive a = new Archive(dir.resolve("history"));
        Backfill b = new Backfill(a, null, Map.of(), new PrintStream(new ByteArrayOutputStream()));
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        List<Point> price = new ArrayList<>();
        for (int i = 0; i < 48; i++) {   // two UTC days, hourly
            price.add(new Point(t("2024-03-30T00:00:00Z") + i * Micro.HOUR, Micro.HOUR, 50 + i));
        }
        SeriesDef p = MarketCatalog.require("smard/day_ahead_price");
        b.store(p, 2024, new MarketSeries(p, price), List.of("fixture"), now, t("2024-04-01T00:00:00Z"));
        SeriesDef temp = MarketCatalog.dwd("03166", "air_temperature");
        b.store(temp, 2024, new MarketSeries(temp, price.subList(0, 24)), List.of("fixture"), now, t("2024-03-31T00:00:00Z"));
        assertEquals(List.of("dwd-cdc/03166/air_temperature", "smard/day_ahead_price"),
                a.archivedSeries().stream().map(SeriesDef::path).toList());

        String iceberg = "catalog-impl=org.apache.iceberg.jdbc.JdbcCatalog,uri=jdbc:sqlite:" + dir.resolve("c.db")
                + ",jdbc.schema-version=V1,warehouse=" + dir.resolve("wh").toUri();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, MarketCli.run("load", Map.of("archive", a.root().toString(), "iceberg", iceberg), new PrintStream(out,
                true, StandardCharsets.UTF_8), Map.of(), now));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("loaded 3 episodes, 72 values"));
        try (var store = IcebergSeriesStore.open(MarketCli.props(iceberg), "tsx")) {
            assertEquals(2, store.listSeries(SeriesSelector.all()).size());
            List<Episode> eps = store.read(EpisodeQuery.of(SeriesSelector.metric("market.price.day_ahead"), 0, Long.MAX_VALUE));
            assertEquals(2, eps.size());
            assertEquals("REPORTED", eps.get(0).getLabels().get("origin"));
            assertEquals(50.0, eps.get(0).getValues().get(0));
            List<Episode> w = store.read(EpisodeQuery.of(SeriesSelector.metric("weather.air_temperature"), 0, Long.MAX_VALUE));
            assertEquals("MEASURED", w.get(0).getLabels().get("origin"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aRepeatedLoadAddsNothingAndTheDayWindowLimitsIt(@TempDir Path dir) {
        Archive a = new Archive(dir.resolve("history"));
        Backfill b = new Backfill(a, null, Map.of(), new PrintStream(new ByteArrayOutputStream()));
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        List<Point> price = new ArrayList<>();
        for (int i = 0; i < 72; i++) {   // three UTC days, hourly
            price.add(new Point(t("2024-03-29T00:00:00Z") + i * Micro.HOUR, Micro.HOUR, 50 + i));
        }
        SeriesDef p = MarketCatalog.require("smard/day_ahead_price");
        b.store(p, 2024, new MarketSeries(p, price), List.of("fixture"), now, t("2024-04-01T00:00:00Z"));
        String iceberg = "catalog-impl=org.apache.iceberg.jdbc.JdbcCatalog,uri=jdbc:sqlite:" + dir.resolve("c.db")
                + ",jdbc.schema-version=V1,warehouse=" + dir.resolve("wh").toUri();
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        Map<String, String> window = Map.of("archive", a.root().toString(), "iceberg", iceberg,
                "from-day", "2024-03-30", "to-day", "2024-03-31");
        assertEquals(0, MarketCli.run("load", window, quiet, Map.of(), now));
        assertEquals(1, priceEpisodes(iceberg));
        Map<String, String> all = Map.of("archive", a.root().toString(), "iceberg", iceberg);
        assertEquals(0, MarketCli.run("load", all, quiet, Map.of(), now));
        assertEquals(0, MarketCli.run("load", all, quiet, Map.of(), now));
        assertEquals(3, priceEpisodes(iceberg));
    }

    @Test
    void loadTakesAnArchivedWeatherSeriesByPath(@TempDir Path dir) {
        Archive a = new Archive(dir.resolve("history"));
        Backfill b = new Backfill(a, null, Map.of(), new PrintStream(new ByteArrayOutputStream()));
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        List<Point> temp = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            temp.add(new Point(t("2024-03-30T00:00:00Z") + i * Micro.HOUR, Micro.HOUR, 5 + i));
        }
        SeriesDef d = MarketCatalog.dwd("02878", "wind_speed");
        b.store(d, 2024, new MarketSeries(d, temp), List.of("fixture"), now, t("2024-03-31T00:00:00Z"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, MarketCli.run("load", Map.of("archive", a.root().toString(), "series", "dwd-cdc/02878/wind_speed"),
                new PrintStream(out, true, StandardCharsets.UTF_8), Map.of(), now));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("loaded 1 episodes, 24 values"), out.toString(StandardCharsets.UTF_8));
    }

    static int priceEpisodes(String iceberg) {
        try (var store = IcebergSeriesStore.open(MarketCli.props(iceberg), "tsx")) {
            return store.read(EpisodeQuery.of(SeriesSelector.metric("market.price.day_ahead"), 0, Long.MAX_VALUE)).size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
