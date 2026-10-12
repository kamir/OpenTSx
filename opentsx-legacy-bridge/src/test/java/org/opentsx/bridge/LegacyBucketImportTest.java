package org.opentsx.bridge;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.VectorWritable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.TimeEncoding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyBucketImportTest {

    @Test
    void readsTsBucketSequenceFileIntoRegularEpisodes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("demo.tsb.vec.seq");
        double[] a = {1.0, 2.0, 3.0};
        double[] b = {10.5, Double.NaN, 12.5, 13.0};
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", "file:///");
        try (SequenceFile.Writer w = SequenceFile.createWriter(conf,
                SequenceFile.Writer.file(new org.apache.hadoop.fs.Path(file.toUri())),
                SequenceFile.Writer.keyClass(Text.class), SequenceFile.Writer.valueClass(VectorWritable.class))) {
            w.append(new Text("wind.met.wind_speed park=ber-01"), new VectorWritable(new NamedVector(new DenseVector(a), "x")));
            w.append(new Text("exp7###rotor.rpm turbine=T07"), new VectorWritable(new NamedVector(new DenseVector(b), "y")));
        }

        List<Episode> episodes = LegacyBucketImport.read(file, 1_000_000L, 600_000_000L, null, "legacy:tsbucket");

        assertEquals(2, episodes.size());
        Episode first = episodes.get(0);
        assertEquals("wind.met.wind_speed", first.getSeries().getMetric());
        assertEquals(TimeEncoding.REGULAR, first.getTimeEncoding());
        assertArrayEquals(a, Episodes.values(first));
        assertArrayEquals(new long[]{1_000_000L, 601_000_000L, 1_201_000_000L}, Episodes.timestampsMicros(first));
        assertEquals("demo.tsb.vec.seq", first.getLabels().get("legacy.file"));

        Episode second = episodes.get(1);
        assertEquals(Map.of("group", "exp7", "turbine", "T07"), second.getSeries().getTags());
        assertArrayEquals(b, Episodes.values(second));
        assertEquals(1, second.getSummary().getNanCount());
    }
}
