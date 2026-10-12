package org.opentsx.market.archive;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.opentsx.market.SeriesDef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code manifest.json} of one archive series: what it is, where it comes from, and per year the file, its
 * SHA-256, row count, retrieval time, the queries (secrets redacted) and the {@link QualityReport}. Format
 * {@code opentsx-history/1}; an example is checked in under {@code docs/devops/history-manifest-example.json}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Manifest {

    public static final String FILE = "manifest.json";
    public static final String FORMAT = "opentsx-history/1";

    /** One year file. {@code complete}: the year is over and was fetched after its end (resume skips it). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String file, long rows, String sha256, long bytes, String first, String last,
                        String retrievedAt, boolean complete, int queryCount, List<String> queries,
                        QualityReport quality, String note) {
    }

    public String format = FORMAT;
    public String source;
    public String series;
    public String metric;
    public Map<String, String> tags;
    public String unit;
    public String origin;
    public String licence;
    public String timeConvention = "interval start in UTC, interval length dt_s in seconds; year files are UTC years";
    public Map<String, Entry> years = new TreeMap<>();

    public Manifest() {
    }

    static Manifest load(Path f, SeriesDef def) {
        Manifest m;
        if (Files.isRegularFile(f)) {
            try {
                m = Archive.JSON.readValue(f.toFile(), Manifest.class);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + f, e);
            }
        } else {
            m = new Manifest();
        }
        m.source = def.source();
        m.series = def.name();
        m.metric = def.metric();
        m.tags = new TreeMap<>(def.tags());
        m.unit = def.unit();
        m.origin = def.origin();
        m.licence = def.licence();
        return m;
    }

    public void save(Path dir) {
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "manifest.", ".tmp");
            Archive.JSON.writeValue(tmp.toFile(), this);
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
