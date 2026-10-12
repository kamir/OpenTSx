package org.opentsx.market.archive;

import org.opentsx.market.Collector;
import org.opentsx.market.MarketSeries;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;
import org.opentsx.market.SeriesDef;
import org.opentsx.market.source.MissingCredentials;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Downloads series year by year into the {@link Archive}, with resume: a year marked {@code complete} in the
 * manifest whose file still has the recorded SHA-256 is skipped; the running year (and years fetched before they
 * settled) are fetched again. Politeness (pause between requests, retries with backoff) lives in the
 * {@link org.opentsx.market.source.Http} of the {@link Collector}. A series whose source has no credentials is
 * skipped with the reason; a failing year is reported and the run continues.
 */
public final class Backfill {

    /** What happened to one series-year. */
    public enum Status { FETCHED, SKIPPED_COMPLETE, NO_CREDENTIALS, EMPTY, FAILED }

    public record Outcome(SeriesDef def, int year, Status status, long rows, String message) {
    }

    private final Archive archive;
    private final Collector collector;
    private final Map<String, String> env;
    private final PrintStream log;

    public Backfill(Archive archive, Collector collector, Map<String, String> env, PrintStream log) {
        this.archive = archive;
        this.collector = collector;
        this.env = env;
        this.log = log;
    }

    /** Days after the end of a year before its data counts as settled (quality-assured TSO data comes late). */
    static long settleDays(SeriesDef def) {
        return def.source().equals("netztransparenz") ? 60 : 3;
    }

    public List<Outcome> run(List<SeriesDef> defs, int fromYear, int toYear, boolean force, Instant now) {
        List<Outcome> out = new ArrayList<>();
        for (SeriesDef def : defs) {
            String why = Collector.unavailable(def, env);
            if (why != null) {
                log.println("skip " + def.path() + ": " + why);
                out.add(new Outcome(def, fromYear, Status.NO_CREDENTIALS, 0, why));
                continue;
            }
            for (int y = fromYear; y <= toYear; y++) {
                Outcome o = year(def, y, force, now);
                out.add(o);
                log.printf("%-40s %d  %-16s %8d rows  %s%n", def.path(), y, o.status(), o.rows(), o.message());
                if (o.status() == Status.NO_CREDENTIALS) {
                    break;
                }
            }
        }
        return out;
    }

    Outcome year(SeriesDef def, int y, boolean force, Instant now) {
        Manifest m = archive.manifest(def);
        String key = Integer.toString(y);
        Manifest.Entry e = m.years.get(key);
        if (!force && e != null && e.complete() && archive.exists(def, y)
                && Archive.sha256(archive.file(def, y)).equals(e.sha256())) {
            return new Outcome(def, y, Status.SKIPPED_COMPLETE, e.rows(), "already archived");
        }
        long from = Micro.utcYear(y);
        long yearEnd = Micro.utcYear(y + 1);
        long nowMicros = Micro.of(now);
        if (from >= nowMicros + 2 * Micro.DAY) {
            return new Outcome(def, y, Status.EMPTY, 0, "in the future");
        }
        long to = Math.min(yearEnd, nowMicros + 2 * Micro.DAY);   // day-ahead prices and forecasts reach into tomorrow
        Collector.Fetched f;
        try {
            f = collector.fetch(def, from, to);
        } catch (MissingCredentials ex) {
            return new Outcome(def, y, Status.NO_CREDENTIALS, 0, ex.getMessage());
        } catch (RuntimeException ex) {
            return new Outcome(def, y, Status.FAILED, 0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        MarketSeries s = f.series();
        if (s.isEmpty()) {
            return new Outcome(def, y, Status.EMPTY, 0, "no data from the source");
        }
        return store(m, def, y, s, f.queries(), now, Math.min(to, nowMicros));
    }

    /** Writes one year (from any origin: online fetch, offline import) and updates the manifest. */
    public Outcome store(SeriesDef def, int y, MarketSeries s, List<String> queries, Instant now, long qualityTo) {
        return store(archive.manifest(def), def, y, s, queries, now, qualityTo);
    }

    private Outcome store(Manifest m, SeriesDef def, int y, MarketSeries all, List<String> queries, Instant now,
                          long qualityTo) {
        long from = Micro.utcYear(y);
        long yearEnd = Micro.utcYear(y + 1);
        MarketSeries s = all.slice(from, yearEnd);
        List<Point> pts = s.points();
        String sha = archive.write(def, y, pts);
        boolean complete = Micro.of(now) >= yearEnd + settleDays(def) * Micro.DAY;
        QualityReport q = QualityReport.of(s, from, Math.min(yearEnd, Math.max(qualityTo, from + 1)));
        long bytes;
        try {
            bytes = Files.size(archive.file(def, y));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        List<String> shown = queries.size() > 6 ? List.of(queries.get(0), queries.get(1), "...",
                queries.get(queries.size() - 1)) : List.copyOf(queries);
        m.years.put(Integer.toString(y), new Manifest.Entry(archive.file(def, y).getFileName().toString(), pts.size(),
                sha, bytes, pts.isEmpty() ? null : Micro.instant(pts.get(0).tMicros()).toString(),
                pts.isEmpty() ? null : Micro.instant(pts.get(pts.size() - 1).tMicros()).toString(), now.toString(),
                complete, queries.size(), shown, q, null));
        m.save(archive.dir(def));
        return new Outcome(def, y, Status.FETCHED, pts.size(), q.summary());
    }
}
