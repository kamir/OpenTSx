package org.opentsx.kafka;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Index persisted as an append-only TSV file (one line per entry). Good enough for local experiments;
 * the file can always be rebuilt from the log with {@link EpisodeIndexer}.
 */
public final class FileEpisodeIndex extends InMemoryEpisodeIndex implements AutoCloseable {

    private static final String HEADER = "#episodeId\tseriesId\ttStartMicros\ttEndMicros\tbucketId\ttopic\tpartition\toffset";

    private final Path file;
    private final BufferedWriter out;

    public FileEpisodeIndex(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.isBlank() && !line.startsWith("#")) {
                        super.add(parse(line, i + 1));
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
            throw new UncheckedIOException("cannot open episode index " + file, e);
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
            throw new UncheckedIOException("cannot append to episode index " + file, ex);
        }
    }

    @Override
    public synchronized void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot close episode index " + file, e);
        }
    }

    private IndexEntry parse(String line, int number) {
        String[] f = line.split("\t", -1);
        if (f.length != 8) {
            throw new IllegalArgumentException(file + ":" + number + ": malformed index line \"" + line
                    + "\"; expected 8 tab-separated fields, see the header line");
        }
        try {
            return new IndexEntry(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3]), "-".equals(f[4]) ? null : f[4],
                    f[5], Integer.parseInt(f[6]), Long.parseLong(f[7]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(file + ":" + number + ": malformed index line \"" + line
                    + "\"; tStartMicros, tEndMicros, partition and offset must be whole numbers", e);
        }
    }
}
