package org.opentsx.market.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.opentsx.market.MarketSeries;
import org.opentsx.market.Micro;
import org.opentsx.market.Point;
import org.opentsx.market.SeriesDef;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The local history archive ("Datenpakete"), outside git (default {@code data/history/}):
 * <pre>
 * data/history/
 *   smard/day_ahead_price/2024.csv.gz          one file per series and UTC year
 *   smard/day_ahead_price/manifest.json        origin, licence, queries, rows, SHA-256, gaps, quality per year
 *   dwd-cdc/03166/air_temperature/2023.csv.gz  weather: station sub-directory
 * </pre>
 * A year file is gzip'ed CSV, interval start in UTC, interval length in seconds:
 * <pre>
 * t_utc,dt_s,value
 * 2024-01-01T00:00:00Z,3600,39.91
 * </pre>
 * The reader also accepts plain {@code <year>.csv} (hand-made slices, test fixtures).
 * Files are written to a temporary file and moved into place, so an interrupted backfill never leaves a
 * half-written year behind; gzip output has no timestamp, so the SHA-256 depends on the content only.
 */
public final class Archive {

    public static final String HEADER = "t_utc,dt_s,value";
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path root;

    public Archive(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path dir(SeriesDef def) {
        return root.resolve(def.source()).resolve(def.name());
    }

    public Path file(SeriesDef def, int year) {
        return dir(def).resolve(year + ".csv.gz");
    }

    public boolean exists(SeriesDef def, int year) {
        return Files.isRegularFile(file(def, year));
    }

    /** The year file as written ({@code .csv.gz}) or, for hand-made slices and fixtures, plain {@code .csv}. */
    Path readable(SeriesDef def, int year) {
        Path gz = file(def, year);
        return Files.isRegularFile(gz) ? gz : dir(def).resolve(year + ".csv");
    }

    /** Writes the points of one UTC year; returns the SHA-256 of the file. */
    public String write(SeriesDef def, int year, List<Point> points) {
        Path f = file(def, year);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), year + ".", ".tmp");
            try (Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(tmp)),
                    StandardCharsets.UTF_8))) {
                w.write(HEADER);
                w.write('\n');
                for (Point p : points) {
                    w.write(Micro.instant(p.tMicros()).toString());
                    w.write(',');
                    w.write(Long.toString(p.dtMicros() / Micro.SECOND));
                    w.write(',');
                    w.write(format(p.value()));
                    w.write('\n');
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return sha256(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Points of one year file, or an empty list if it does not exist. */
    public List<Point> read(SeriesDef def, int year) {
        Path f = readable(def, year);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        return readFile(f);
    }

    /** Reads a year file (gzip'ed or plain CSV in the archive format). */
    public static List<Point> readFile(Path f) {
        List<Point> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(f.toString().endsWith(".gz")
                ? new GZIPInputStream(Files.newInputStream(f)) : Files.newInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line == null || !line.equals(HEADER)) {
                throw new IllegalArgumentException(f + ": expected header " + HEADER);
            }
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(",", -1);
                out.add(new Point(Micro.of(Instant.parse(c[0])), Long.parseLong(c[1]) * Micro.SECOND,
                        Double.parseDouble(c[2])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The series between {@code from} and {@code to}, read from all year files that overlap. */
    public MarketSeries series(SeriesDef def, long from, long to) {
        List<Point> all = new ArrayList<>();
        for (int y = Micro.yearOf(from) - 1; y <= Micro.yearOf(to - 1); y++) {
            all.addAll(read(def, y));
        }
        return new MarketSeries(def, all).slice(from, to);
    }

    /** Every series of the archive that has a manifest, from the manifests themselves (catalog, weather, partner). */
    public List<SeriesDef> archivedSeries() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().equals(Manifest.FILE)).sorted().map(f -> {
                try {
                    Manifest m = JSON.readValue(f.toFile(), Manifest.class);
                    return new SeriesDef(m.source, m.series, m.metric, m.tags == null ? java.util.Map.of() : m.tags,
                            m.unit, m.origin, m.licence);
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read " + f, e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Manifest manifest(SeriesDef def) {
        return Manifest.load(dir(def).resolve(Manifest.FILE), def);
    }

    public static String sha256(Path f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(f));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.4f", v);
        int end = s.length();
        while (s.charAt(end - 1) == '0') {
            end--;
        }
        return s.substring(0, s.charAt(end - 1) == '.' ? end - 1 : end);
    }
}
