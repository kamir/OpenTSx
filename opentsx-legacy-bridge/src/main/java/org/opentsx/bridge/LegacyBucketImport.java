package org.opentsx.bridge;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.opentsx.model.Episodes;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a legacy TSBucket file ({@code *.tsb.vec.seq}: Hadoop SequenceFile, key = series label as {@code Text},
 * value = Mahout {@code VectorWritable} with the values) and turns every series into a REGULAR v2 episode.
 *
 * <p>TSBucket files store no time axis, so the caller provides {@code t0} and the sampling interval. Keys of the
 * form {@code prefix###label} (TSBucket key prefixes) keep the prefix as tag {@code group}. Write the result with
 * {@code org.opentsx.kafka.BucketWriter} to move a bucket to KafScale.
 */
public final class LegacyBucketImport {

    private static final String KEY_PREFIX_DELIMITER = "###";

    private LegacyBucketImport() {
    }

    public static List<Episode> read(Path file, long t0Micros, long intervalMicros, String unit, String source) {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", "file:///");
        List<Episode> out = new ArrayList<>();
        try (SequenceFile.Reader reader = new SequenceFile.Reader(conf,
                SequenceFile.Reader.file(new org.apache.hadoop.fs.Path(file.toUri())))) {
            Text key = new Text();
            VectorWritable value = new VectorWritable();
            while (reader.next(key, value)) {
                Vector v = value.get();
                double[] values = new double[v.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = v.get(i);
                }
                out.add(Episodes.regular(seriesKey(key.toString(), unit), t0Micros, intervalMicros, values)
                        .source(source)
                        .producer(LegacyBridge.PRODUCER)
                        .label("legacy.file", file.getFileName().toString())
                        .build());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read TSBucket file " + file, e);
        }
        return out;
    }

    static SeriesKey seriesKey(String rawKey, String unit) {
        int d = rawKey.indexOf(KEY_PREFIX_DELIMITER);
        if (d < 0) {
            return LegacyBridge.seriesKeyFromLabel(rawKey, unit);
        }
        SeriesKey base = LegacyBridge.seriesKeyFromLabel(rawKey.substring(d + KEY_PREFIX_DELIMITER.length()), unit);
        Map<String, String> tags = new LinkedHashMap<>(base.getTags());
        tags.put("group", rawKey.substring(0, d));
        return SeriesKeys.of(base.getMetric(), tags, unit);
    }
}
