package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.BrokerPublish;
import org.opentsx.market.archive.WeatherBackfill;
import org.opentsx.market.source.Fetcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerPublishTest {

    static Archive threeDays(Path dir) {
        StringBuilder hist = new StringBuilder("STATIONS_ID;MESS_DATUM;QN_9;TT_TU;RF_TU;eor\n");
        for (int day = 1; day <= 3; day++) {
            for (int h = 0; h < 24; h++) {
                hist.append(String.format("       3166;202401%02d%02d;    3;  -2.0;  90.0;eor%n", day, h));
            }
        }
        String recent = "STATIONS_ID;MESS_DATUM;QN_9;TT_TU;RF_TU;eor\n";
        Fetcher f = new Fetcher() {
            @Override
            public String get(String uri, Map<String, String> headers) {
                if (uri.endsWith("historical/")) {
                    return "<a href=\"stundenwerte_TU_03166_19820101_20241231_hist.zip\">x</a>";
                }
                return "<a href=\"stundenwerte_TU_03166_akt.zip\">r</a>";
            }

            @Override
            public byte[] getBytes(String uri) {
                try {
                    return WeatherBackfillTest.zip(uri.contains("_hist") ? hist.toString() : recent);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        Archive a = new Archive(dir);
        new WeatherBackfill(a, f, "https://cdc.example/", new PrintStream(new ByteArrayOutputStream()))
                .run("03166", "air_temperature", 2024, 2024, false, Instant.parse("2026-10-12T00:00:00Z"));
        return a;
    }

    static String dryRun(Archive a, String fromDay) {
        Map<String, String> o = new HashMap<>();
        o.put("series", MarketCatalog.dwd("03166", "air_temperature").path());
        o.put("domain", "test");
        o.put("dry-run", "");
        if (fromDay != null) {
            o.put("from-day", fromDay);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        assertEquals(0, BrokerPublish.run(a, o, new PrintStream(b, true, StandardCharsets.UTF_8)));
        return b.toString(StandardCharsets.UTF_8);
    }

    @Test
    void fromDaySkipsEpisodesThatBeginBeforeThatUtcDay(@TempDir Path dir) {
        Archive a = threeDays(dir);
        String all = dryRun(a, null);
        assertTrue(all.contains("dry run: 3 episodes would go to tsx.test.episodes.v2"), all);
        String last = dryRun(a, "2024-01-03");
        assertTrue(last.contains("dry run: 1 episodes would go to tsx.test.episodes.v2"), last);
    }
}
