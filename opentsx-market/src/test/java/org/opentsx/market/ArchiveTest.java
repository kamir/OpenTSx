package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
import org.opentsx.market.archive.Manifest;
import org.opentsx.market.archive.QualityReport;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentsx.market.ParsersTest.t;

class ArchiveTest {

    static final SeriesDef PRICE = MarketCatalog.require("smard/day_ahead_price");

    static List<Point> hourly(String fromIso, int n, double v) {
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Point(t(fromIso) + i * Micro.HOUR, Micro.HOUR, v + i % 24));
        }
        return out;
    }

    @Test
    void yearFilesRoundTripWithStableChecksum(@TempDir Path dir) throws Exception {
        Archive a = new Archive(dir);
        List<Point> pts = List.of(new Point(t("2024-01-01T00:00:00Z"), Micro.HOUR, 39.91),
                new Point(t("2024-01-01T01:00:00Z"), Micro.HOUR, -0.125), new Point(t("2024-01-01T02:00:00Z"),
                        Micro.QUARTER, 1e-4));
        String sha = a.write(PRICE, 2024, pts);
        assertEquals(pts, a.read(PRICE, 2024));
        assertEquals(sha, a.write(PRICE, 2024, pts));   // no timestamp in the gzip header
        assertEquals(dir.resolve("smard/day_ahead_price/2024.csv.gz"), a.file(PRICE, 2024));
        assertTrue(Files.list(a.dir(PRICE)).noneMatch(p -> p.toString().endsWith(".tmp")));
    }

    @Test
    void qualityReportFindsGapsDstDaysAndImplausibleValues() {
        List<Point> pts = new ArrayList<>(hourly("2024-01-01T00:00:00Z", 8784, 50));
        pts.removeIf(p -> p.tMicros() >= t("2024-05-01T10:00:00Z") && p.tMicros() < t("2024-05-01T13:00:00Z"));
        pts.set(100, new Point(pts.get(100).tMicros(), Micro.HOUR, 5000));
        QualityReport q = QualityReport.of(new MarketSeries(PRICE, pts), Micro.utcYear(2024), Micro.utcYear(2025));
        assertEquals(1, q.gapCount());
        assertEquals(3.0, q.gaps().get(0).hours());
        assertEquals(2, q.dstDays().size());
        assertEquals(new QualityReport.DstDay("2024-03-31", 23, 23), q.dstDays().get(0));
        assertEquals(new QualityReport.DstDay("2024-10-27", 25, 25), q.dstDays().get(1));
        assertTrue(q.dstOk());
        assertEquals(1, q.incompleteDays());   // 1 May
        assertEquals(1, q.implausible());
        assertEquals(Map.of("PT1H", 8781L), q.resolutions());
        assertTrue(q.stats().get("p50") > 50 && q.stats().get("p50") < 74);
    }

    @Test
    void qualityReportSeesAMissingDstHour() {
        List<Point> pts = new ArrayList<>(hourly("2024-01-01T00:00:00Z", 8784, 50));
        pts.removeIf(p -> p.tMicros() == t("2024-10-27T01:00:00Z"));   // the second 02:00 local
        QualityReport q = QualityReport.of(new MarketSeries(PRICE, pts), Micro.utcYear(2024), Micro.utcYear(2025));
        assertFalse(q.dstOk());
        assertEquals(24.0, q.dstDays().get(1).coveredHours());
    }

    @Test
    void episodesSplitAtDayBoundariesAndAtTheMtuSwitch() {
        List<Point> pts = new ArrayList<>(hourly("2025-09-30T20:00:00Z", 2, 90));
        for (int i = 0; i < 8; i++) {
            pts.add(new Point(t("2025-09-30T22:00:00Z") + i * Micro.QUARTER, Micro.QUARTER, 80 + i));
        }
        List<Episode> eps = EpisodeMapper.episodes(new MarketSeries(PRICE, pts), "test");
        assertEquals(2, eps.size());   // 20-22h hourly, then quarter-hours (the UTC day also changes at 00:00)
        assertEquals(Micro.HOUR, eps.get(0).getIntervalMicros());
        assertEquals(Micro.QUARTER, eps.get(1).getIntervalMicros());
        assertEquals("REPORTED", eps.get(1).getLabels().get("origin"));
        assertEquals("PT15M", eps.get(1).getLabels().get("resolution"));
        assertTrue(eps.get(1).getLabels().get("licence").contains("SMARD"));
        assertEquals("market.price.day_ahead", eps.get(0).getSeries().getMetric());
        assertEquals(Map.of("area", "DE-LU", "source", "smard"), eps.get(0).getSeries().getTags());
        eps.forEach(Episodes::validate);
        List<Point> gap = new ArrayList<>(hourly("2024-01-01T21:00:00Z", 6, 1));
        gap.remove(1);
        assertEquals(3, EpisodeMapper.episodes(new MarketSeries(PRICE, gap), "t").size());   // gap and midnight
    }

    @Test
    void backfillResumesCompleteYearsAndRefetchesChangedFiles(@TempDir Path dir) {
        String w = "1759096800000";
        Fixtures.FakeFetcher smard = new Fixtures.FakeFetcher()
                .serve("index_hour.json", "{\"timestamps\":[" + w + "]}")
                .serve("index_quarterhour.json", "{\"timestamps\":[" + w + "]}")
                .serve("4169_DE-LU_hour_" + w + ".json", "smard-4169-hour-" + w + ".json")
                .serve("4169_DE-LU_quarterhour_" + w + ".json", "smard-4169-quarterhour-" + w + ".json");
        Collector c = new Collector(smard, null, null, null, Map.of());
        Archive a = new Archive(dir);
        PrintStream log = new PrintStream(new ByteArrayOutputStream());
        Backfill b = new Backfill(a, c, Map.of(), log);
        Instant now = Instant.parse("2026-01-10T00:00:00Z");
        List<SeriesDef> defs = List.of(PRICE, MarketCatalog.require("entsoe/day_ahead_price"));

        List<Backfill.Outcome> r = b.run(defs, 2025, 2025, false, now);
        assertEquals(Backfill.Status.FETCHED, r.get(0).status());
        assertEquals(18, r.get(0).rows());
        assertEquals(Backfill.Status.NO_CREDENTIALS, r.get(1).status());
        Manifest m = a.manifest(PRICE);
        Manifest.Entry e = m.years.get("2025");
        assertTrue(e.complete());
        assertEquals(Archive.sha256(a.file(PRICE, 2025)), e.sha256());
        assertEquals(4, e.queryCount());
        assertEquals("REPORTED", m.origin);
        assertTrue(m.licence.contains("CC BY 4.0"));
        assertTrue(e.quality().gapCount() > 0);   // the fixture covers six hours of the year

        int requests = smard.requests.size();
        assertEquals(Backfill.Status.SKIPPED_COMPLETE, b.run(List.of(PRICE), 2025, 2025, false, now).get(0).status());
        assertEquals(requests, smard.requests.size());

        a.write(PRICE, 2025, List.of(new Point(0, Micro.HOUR, 1)));   // someone changed the file
        assertEquals(Backfill.Status.FETCHED, b.run(List.of(PRICE), 2025, 2025, false, now).get(0).status());
        assertEquals(18, a.read(PRICE, 2025).size());

        Instant early = Instant.parse("2025-10-01T12:00:00Z");      // running year: not complete, fetched again
        new Archive(dir.resolve("b"));
        Backfill b2 = new Backfill(new Archive(dir.resolve("b")), c, Map.of(), log);
        b2.run(List.of(PRICE), 2025, 2025, false, early);
        assertFalse(new Archive(dir.resolve("b")).manifest(PRICE).years.get("2025").complete());
    }
}
