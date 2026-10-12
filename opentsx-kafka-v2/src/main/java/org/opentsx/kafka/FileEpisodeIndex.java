package org.opentsx.kafka;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Index persisted as an append-only TSV file (one line per entry). Good enough for local experiments;
 * the file can always be rebuilt from the log with {@link EpisodeIndexer}.
 */
public final class FileEpisodeIndex extends InMemoryEpisodeIndex implements AutoCloseable {

    private static final String HEADER = "#episodeId\tseriesId\ttStartMicros\ttEndMicros\tbucketId\ttopic\tpartition\toffset";

    private final BufferedWriter out;

    public FileEpisodeIndex(Path file) {
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        super.add(parse(line));
                    }
                }
            }
            boolean fresh = !Files.exists(file);
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                out.write(HEADER);
                out.newLine();
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void add(IndexEntry e) {
        super.add(e);
        try {
            out.write(String.join("\t", e.episodeId(), e.seriesId(), Long.toString(e.tStartMicros()),
                    Long.toString(e.tEndMicros()), e.bucketId() == null ? "-" : e.bucketId(), e.topic(),
                    Integer.toString(e.partition()), Long.toString(e.offset())));
            out.newLine();
            out.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public synchronized void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static IndexEntry parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 8) {
            throw new IllegalArgumentException("malformed index line: " + line);
        }
        return new IndexEntry(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3]), "-".equals(f[4]) ? null : f[4],
                f[5], Integer.parseInt(f[6]), Long.parseLong(f[7]));
    }
}
