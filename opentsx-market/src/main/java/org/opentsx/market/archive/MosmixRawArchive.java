package org.opentsx.market.archive;

import org.opentsx.market.Licences;
import org.opentsx.market.source.Fetcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Forward archive of DWD MOSMIX_L station forecasts as raw files. The DWD keeps only the latest runs on
 * opendata.dwd.de, so forecast history exists only if it is collected while it is published: run this every
 * 6 hours (MOSMIX_L is issued at 03, 09, 15 and 21 UTC) or at least daily. Files are kept as delivered (KMZ);
 * parsing into series is the job of {@code opentsx-dwd}.
 * <pre>
 * data/history/dwd-mosmix/raw/&lt;station&gt;/&lt;issue time&gt;.kmz
 * data/history/dwd-mosmix/raw/index.tsv    issue, station, file, bytes, sha256, retrieved, url, licence
 * </pre>
 */
public final class MosmixRawArchive {

    public static final String URL = "https://opendata.dwd.de/weather/local_forecasts/mos/MOSMIX_L/single_stations/"
            + "%1$s/kml/MOSMIX_L_LATEST_%1$s.kmz";
    /** MOSMIX ids of the sites (DWD MOSMIX station catalogue): Marienberg, Greifswalder Oie, Kiel-Holtenau, Kieler Bucht. */
    public static final List<String> DEFAULT_STATIONS = List.of("10579", "10097", "10046", "E5411");
    private static final Pattern ISSUE = Pattern.compile("<dwd:IssueTime>([^<]+)</dwd:IssueTime>");

    /** Result per station: issue time, whether it was new, file. */
    public record Result(String station, String issue, boolean added, Path file) {
    }

    private final Path dir;
    private final Fetcher fetcher;

    public MosmixRawArchive(Path historyRoot, Fetcher fetcher) {
        this.dir = historyRoot.resolve("dwd-mosmix").resolve("raw");
        this.fetcher = fetcher;
    }

    public List<Result> collect(List<String> stations, Instant now) {
        List<Result> out = new ArrayList<>();
        for (String st : stations) {
            String url = String.format(URL, st);
            byte[] kmz = fetcher.getBytes(url);
            String issue = issueTime(kmz);
            Path f = dir.resolve(st).resolve(issue.replace(":", "") + ".kmz");
            boolean added = !Files.exists(f);
            if (added) {
                try {
                    Files.createDirectories(f.getParent());
                    Files.write(f, kmz);
                    String sha = Archive.sha256(f);
                    Path index = dir.resolve("index.tsv");
                    if (!Files.exists(index)) {
                        Files.writeString(index, "issue\tstation\tfile\tbytes\tsha256\tretrieved\turl\tlicence\n");
                    }
                    Files.writeString(index, String.join("\t", issue, st, dir.relativize(f).toString(),
                            Long.toString(kmz.length), sha, now.toString(), url, Licences.DWD) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            out.add(new Result(st, issue, added, f));
        }
        return out;
    }

    /** Issue time of a MOSMIX KMZ (from the KML inside), e.g. {@code 2026-10-12T09:00:00.000Z}. */
    static String issueTime(byte[] kmz) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(kmz))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().endsWith(".kml")) {
                    byte[] head = z.readNBytes(64 * 1024);
                    Matcher m = ISSUE.matcher(new String(head, StandardCharsets.UTF_8));
                    if (m.find()) {
                        return Instant.parse(m.group(1)).toString();
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("not a MOSMIX KMZ", e);
        }
        throw new IllegalArgumentException("no IssueTime in the MOSMIX KMZ");
    }
}
