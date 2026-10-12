package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
import org.opentsx.market.archive.WeatherBackfill;
import org.opentsx.market.source.Fetcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentsx.market.ParsersTest.t;

class WeatherBackfillTest {

    static byte[] zip(String product) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("Metadaten_Geographie_03166.txt"));
            z.write("ignored".getBytes(StandardCharsets.ISO_8859_1));
            z.putNextEntry(new ZipEntry("produkt_tu_stunde_x_03166.txt"));
            z.write(product.getBytes(StandardCharsets.ISO_8859_1));
        }
        return b.toByteArray();
    }

    @Test
    void readsHistoricalAndRecentZipsFromTheListingAndArchivesUtcYears(@TempDir Path dir) throws Exception {
        String hist = "STATIONS_ID;MESS_DATUM;QN_9;TT_TU;RF_TU;eor\n"
                + "       3166;2023123122;    3;  -2.0;  90.0;eor\n"
                + "       3166;2023123123;    3;  -2.5;  91.0;eor\n"
                + "       3166;2024010100;    3;  -3.0;  92.0;eor\n"
                + "       3166;2024010101;    3;  -999;  92.0;eor\n"
                + "       3166;2024010102;    3;  -3.5;  93.0;eor\n";
        String recent = "STATIONS_ID;MESS_DATUM;QN_9;TT_TU;RF_TU;eor\n"
                + "       3166;2024010102;    1;  -3.4;  93.0;eor\n"
                + "       3166;2024010103;    1;  -3.6;  93.0;eor\n";
        String base = "https://cdc.example/";
        List<String> requests = new ArrayList<>();
        Fetcher f = new Fetcher() {
            @Override
            public String get(String uri, Map<String, String> headers) {
                requests.add(uri);
                if (uri.endsWith("historical/")) {
                    return "<a href=\"stundenwerte_TU_03166_19820101_20241231_hist.zip\">x</a>"
                            + "<a href=\"stundenwerte_TU_01358_19510101_20241231_hist.zip\">y</a>"
                            + "<a href=\"stundenwerte_TU_03166_19500101_19811231_hist.zip\">old</a>";
                }
                return "<a href=\"stundenwerte_TU_03166_akt.zip\">r</a>";
            }

            @Override
            public byte[] getBytes(String uri) {
                requests.add(uri);
                try {
                    return zip(uri.contains("_hist") ? hist : recent);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        Archive a = new Archive(dir);
        WeatherBackfill w = new WeatherBackfill(a, f, base, new PrintStream(new ByteArrayOutputStream()));
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        List<Backfill.Outcome> r = w.run("03166", "air_temperature", 2023, 2024, false, now);
        assertEquals(List.of(Backfill.Status.FETCHED, Backfill.Status.FETCHED), r.stream().map(Backfill.Outcome::status).toList());
        assertTrue(requests.contains(base + "hourly/air_temperature/historical/stundenwerte_TU_03166_19820101_20241231_hist.zip"));
        assertTrue(requests.stream().noneMatch(u -> u.contains("19500101") || u.contains("01358")));   // other years, other station
        SeriesDef def = MarketCatalog.dwd("03166", "air_temperature");
        assertEquals(2, a.read(def, 2023).size());
        List<Point> y24 = a.read(def, 2024);
        assertEquals(List.of(new Point(t("2024-01-01T00:00:00Z"), Micro.HOUR, -3.0),
                new Point(t("2024-01-01T02:00:00Z"), Micro.HOUR, -3.4),     // recent replaces historical
                new Point(t("2024-01-01T03:00:00Z"), Micro.HOUR, -3.6)), y24);
        assertEquals("MEASURED", a.manifest(def).origin);
        assertTrue(a.manifest(def).licence.contains("Deutscher Wetterdienst"));
        int n = requests.size();
        r = w.run("03166", "air_temperature", 2023, 2024, false, now);   // both years complete: no download
        assertTrue(r.stream().allMatch(o -> o.status() == Backfill.Status.SKIPPED_COMPLETE));
        assertEquals(n, requests.size());
    }
}
