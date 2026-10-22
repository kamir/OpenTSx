package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
import org.opentsx.market.archive.Manifest;
import org.opentsx.market.archive.WeatherBackfill;
import org.opentsx.market.source.Fetcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentsx.market.ParsersTest.t;

/**
 * Every weather parameter of the backfill reaches the right CDC directory, takes the right column and lands in the
 * archive with its unit and time convention; the CLI picks stations and years as documented.
 */
class WeatherJobsTest {

    /** Product file per CDC directory, in the column layout of the real files (values invented, two stamps each). */
    static final Map<String, String> PRODUCTS = new LinkedHashMap<>();

    static {
        PRODUCTS.put("hourly/air_temperature/", "STATIONS_ID;MESS_DATUM;QN_9;TT_TU;RF_TU;eor\n"
                + "       1759;2024010101;    3;   1.5;  88.0;eor\n"
                + "       1759;2024010102;    3;   1.2;  89.0;eor\n");
        PRODUCTS.put("hourly/wind/", "STATIONS_ID;MESS_DATUM;QN_3;   F;   D;eor\n"
                + "       1759;2024010101;   10;   4.2; 250;eor\n"
                + "       1759;2024010102;   10;   4.8; 260;eor\n");
        PRODUCTS.put("10_minutes/wind/", "STATIONS_ID;MESS_DATUM;  QN;FF_10;DD_10;eor\n"
                + "       1759;202401010010;    3;   5.1; 240;eor\n"
                + "       1759;202401010020;    3;   5.3; 245;eor\n");
        PRODUCTS.put("hourly/extreme_wind/", "STATIONS_ID;MESS_DATUM;QN_8;FX_911;eor\n"
                + "       1759;2024010101;    3;   9.9;eor\n"
                + "       1759;2024010102;    3;  11.0;eor\n");
        PRODUCTS.put("hourly/pressure/", "STATIONS_ID;MESS_DATUM;QN_8;   P;  P0;eor\n"
                + "       1759;2024010101;    3; 1020.0; 1012.3;eor\n"
                + "       1759;2024010102;    3; 1020.4; 1012.7;eor\n");
        PRODUCTS.put("hourly/precipitation/", "STATIONS_ID;MESS_DATUM;QN_8;  R1;RS_IND;WRTR;eor\n"
                + "       1759;2024010101;    3;   0.4;   1;   6;eor\n"
                + "       1759;2024010102;    3;  -999;   0;   0;eor\n");
        PRODUCTS.put("10_minutes/solar/", "STATIONS_ID;MESS_DATUM;  QN;DS_10;GS_10;SD_10;LS_10;eor\n"
                + "       1759;202401010010;    3;   2.0;   6.0;   0.1;-999;eor\n"
                + "       1759;202401010020;    3;   2.0;   7.2;   0.2;-999;eor\n");
    }

    /** parameter -> {CDC directory, file prefix, first interval start, its length in s, its value}. */
    record Expect(String dir, String prefix, String start, long dtS, double value) {
    }

    static final Map<String, Expect> EXPECT = new LinkedHashMap<>();

    static {
        EXPECT.put("air_temperature", new Expect("hourly/air_temperature/", "stundenwerte_TU_", "2024-01-01T01:00:00Z", 3600, 1.5));
        EXPECT.put("relative_humidity", new Expect("hourly/air_temperature/", "stundenwerte_TU_", "2024-01-01T01:00:00Z", 3600, 88.0));
        EXPECT.put("wind_speed", new Expect("hourly/wind/", "stundenwerte_FF_", "2024-01-01T00:00:00Z", 3600, 4.2));
        EXPECT.put("wind_direction", new Expect("hourly/wind/", "stundenwerte_FF_", "2024-01-01T00:00:00Z", 3600, 250));
        EXPECT.put("wind_speed_10min", new Expect("10_minutes/wind/", "10minutenwerte_wind_", "2024-01-01T00:00:00Z", 600, 5.1));
        EXPECT.put("wind_gust", new Expect("hourly/extreme_wind/", "stundenwerte_FX_", "2024-01-01T00:00:00Z", 3600, 9.9));
        EXPECT.put("pressure", new Expect("hourly/pressure/", "stundenwerte_P0_", "2024-01-01T01:00:00Z", 3600, 1012.3));
        EXPECT.put("precipitation", new Expect("hourly/precipitation/", "stundenwerte_RR_", "2024-01-01T00:00:00Z", 3600, 0.4));
        EXPECT.put("global_radiation", new Expect("10_minutes/solar/", "10minutenwerte_SOLAR_", "2024-01-01T00:00:00Z", 600, 100.0));
    }

    static byte[] zip(String product) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("Metadaten_Geographie_01759.txt"));
            z.write("ignored".getBytes(StandardCharsets.ISO_8859_1));
            z.putNextEntry(new ZipEntry("produkt_x_01759.txt"));
            z.write(product.getBytes(StandardCharsets.ISO_8859_1));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }

    /** Serves a listing with one recent zip per directory, and that directory's product file for any zip in it. */
    static Fetcher cdc(String base, List<String> requests) {
        return new Fetcher() {
            @Override
            public String get(String uri, Map<String, String> headers) {
                requests.add(uri);
                if (uri.endsWith("historical/")) {
                    return "";
                }
                String dir = uri.substring(base.length(), uri.length() - "recent/".length());
                String prefix = EXPECT.values().stream().filter(e -> e.dir().equals(dir)).findFirst().orElseThrow().prefix();
                return "<a href=\"" + prefix + "01759_akt.zip\">r</a>";
            }

            @Override
            public byte[] getBytes(String uri) {
                requests.add(uri);
                String dir = PRODUCTS.keySet().stream().filter(d -> uri.startsWith(base + d)).findFirst().orElseThrow();
                return zip(PRODUCTS.get(dir));
            }
        };
    }

    @Test
    void everyParameterComesFromItsDirectoryColumnAndConvention(@TempDir Path dir) {
        assertEquals(List.copyOf(EXPECT.keySet()).stream().sorted().toList(),
                WeatherBackfill.parameters().stream().sorted().toList());
        String base = "https://cdc.example/";
        Archive a = new Archive(dir);
        Instant now = Instant.parse("2026-10-22T00:00:00Z");
        for (Map.Entry<String, Expect> e : EXPECT.entrySet()) {
            List<String> requests = new ArrayList<>();
            WeatherBackfill w = new WeatherBackfill(a, cdc(base, requests), base, new PrintStream(new ByteArrayOutputStream()));
            List<Backfill.Outcome> r = w.run("01759", e.getKey(), 2024, 2024, false, now);
            Expect x = e.getValue();
            assertEquals(Backfill.Status.FETCHED, r.get(0).status(), e.getKey() + ": " + r.get(0).message());
            assertTrue(requests.contains(base + x.dir() + "recent/" + x.prefix() + "01759_akt.zip"), e.getKey() + " " + requests);
            SeriesDef def = MarketCatalog.dwd("01759", e.getKey());
            Point first = a.read(def, 2024).get(0);
            assertEquals(t(x.start()), first.tMicros(), e.getKey());
            assertEquals(x.dtS() * Micro.SECOND, first.dtMicros(), e.getKey());
            assertEquals(x.value(), first.value(), 1e-9, e.getKey());
            Manifest m = a.manifest(def);
            assertEquals(def.unit(), m.unit, e.getKey());
            assertEquals("MEASURED", m.origin);
            assertEquals("weather." + e.getKey(), m.metric);
        }
        // -999 in the precipitation file is a gap, not a value
        assertEquals(1, a.read(MarketCatalog.dwd("01759", "precipitation"), 2024).size());
        assertTrue(Files.isRegularFile(a.file(MarketCatalog.dwd("01759", "wind_gust"), 2024)));
    }

    @Test
    void defaultStationsComeFromTheSitesFileAndStationsCanBeNamed() {
        List<String> jobs = MarketCli.weatherJobs(Map.of()).stream().map(j -> j[0] + ":" + j[1]).toList();
        assertEquals(List.of("03166:air_temperature", "03166:wind_speed", "01358:global_radiation",
                "01759:air_temperature", "01759:wind_speed", "00183:global_radiation",
                "02564:air_temperature", "02564:wind_speed", "06163:global_radiation"), jobs);
        List<String[]> named = MarketCli.weatherJobs(Map.of("stations", "01759:pressure, 01759:wind_gust,01759:pressure"));
        assertEquals(2, named.size());   // duplicates once
        assertArrayEquals(new String[]{"01759", "wind_gust"}, named.get(1));
        assertThrows(IllegalArgumentException.class, () -> MarketCli.weatherJobs(Map.of("stations", "01759:sunshine")));
        assertThrows(IllegalArgumentException.class, () -> MarketCli.weatherJobs(Map.of("stations", "01759")));
    }

    @Test
    void dailyJobsTakeThePreviousAndTheRunningYear() {
        Instant now = Instant.parse("2027-01-02T06:23:00Z");
        assertArrayEquals(new int[]{2026, 2027}, MarketCli.years("weather-forward", Map.of(), now));
        assertArrayEquals(new int[]{2026, 2027}, MarketCli.years("forward", Map.of(), now));
        assertArrayEquals(new int[]{2019, 2027}, MarketCli.years("weather-backfill", Map.of(), now));
        assertArrayEquals(new int[]{2022, 2025}, MarketCli.years("backfill", Map.of("from-year", "2022", "to-year", "2025"), now));
    }
}
