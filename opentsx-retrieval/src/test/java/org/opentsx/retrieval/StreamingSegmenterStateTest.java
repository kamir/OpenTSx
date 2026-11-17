package org.opentsx.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.Ulid;
import org.opentsx.model.v2.Episode;
import org.opentsx.retrieval.segment.Segmenter;
import org.opentsx.retrieval.segment.StreamingSegmenter;
import org.opentsx.store.EpisodeOps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamingSegmenterStateTest {

    static byte[] checkpoint(StreamingSegmenter stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        stream.writeState(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    static StreamingSegmenter restore(Segmenter config, byte[] state) throws IOException {
        StreamingSegmenter restored = new StreamingSegmenter(config);
        restored.readState(new DataInputStream(new ByteArrayInputStream(state)));
        return restored;
    }

    @TestFactory
    List<DynamicTest> everySharedCaseResumesAfterEveryChunk() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String name : List.of("segment-threshold", "segment-extremum")) {
            for (JsonNode c : OperatorVectors.read(name).path("cases")) {
                tests.add(DynamicTest.dynamicTest(name + ": " + c.path("name").asText(), () -> {
                    Segmenter config = SegmentVectors.segmenter(c);
                    for (int chunk : new int[]{1, 7, Math.max(1, c.path("values").size())}) {
                        StreamingSegmenter stream = new StreamingSegmenter(config);
                        List<Episode> output = new ArrayList<>();
                        for (Episode episode : SegmentVectors.episodes(c, chunk)) {
                            output.addAll(stream.onEpisode(episode));
                            byte[] saved = checkpoint(stream);
                            stream = restore(config, saved);
                            assertArrayEquals(saved, checkpoint(stream), "stable checkpoint bytes");
                        }
                        output.addAll(stream.flush());
                        output.sort(EpisodeOps.READ_ORDER);
                        assertEquals(c.path("expect").toString(), SegmentVectors.records(output).toString(), "chunk " + chunk);
                    }
                }));
            }
        }
        assertEquals(12, tests.size());
        return tests;
    }

    @Test
    void rejectsUnknownVersionsOtherParametersAndTruncatedState() throws IOException {
        Segmenter config = Segmenter.threshold(Segmenter.Direction.ABOVE, 5, 3, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO);
        byte[] saved = checkpoint(new StreamingSegmenter(config));
        byte[] unknown = saved.clone();
        ByteBuffer.wrap(unknown).putInt(2);
        assertEquals("unsupported segmentation state version 2", assertThrows(IOException.class,
                () -> restore(config, unknown)).getMessage());
        Segmenter other = Segmenter.threshold(Segmenter.Direction.ABOVE, 6, 3, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO);
        assertEquals("segmentation checkpoint has different parameters", assertThrows(IOException.class,
                () -> restore(other, saved)).getMessage());
        assertThrows(IOException.class, () -> restore(config, Arrays.copyOf(saved, saved.length - 1)));
        assertThrows(IOException.class, () -> checkpoint(new StreamingSegmenter(Segmenter.fixedCount(4, 0))));
    }

    @Test
    void preservesQualityAndSourceMetadataAcrossAnActiveThreshold() throws IOException {
        Segmenter config = Segmenter.threshold(Segmenter.Direction.ABOVE, 5, 3, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO);
        var key = SeriesKeys.of("synthetic.signal", Map.of("site", "test"), "1");
        String source = Ulid.deterministic(0, 42);
        Episode first = Episodes.irregular(key, new long[]{0, 1_000_000, 2_000_000}, new double[]{0, 6, 6})
                .episodeId(source).source("synthetic-state").createdAt(5_000_001).quality(new byte[]{1, 2, 4})
                .label("scenario", "synthetic").label("gt.phase", "discard").build();
        Episode last = Episodes.irregular(key, new long[]{3_000_000, 4_000_000, 5_000_000}, new double[]{0, 0, 0})
                .episodeId(source).source("synthetic-state").createdAt(5_000_001).quality(new byte[]{8, 16, 32})
                .label("scenario", "synthetic").label("gt.phase", "discard").build();
        StreamingSegmenter uninterrupted = new StreamingSegmenter(config);
        List<Episode> expected = new ArrayList<>(uninterrupted.onEpisode(first));
        expected.addAll(uninterrupted.onEpisode(last));
        expected.addAll(uninterrupted.flush());
        StreamingSegmenter interrupted = new StreamingSegmenter(config);
        List<Episode> actual = new ArrayList<>(interrupted.onEpisode(first));
        interrupted = restore(config, checkpoint(interrupted));
        actual.addAll(interrupted.onEpisode(last));
        actual.addAll(interrupted.flush());
        assertEquals(expected, actual);
        assertEquals(1, actual.size());
        assertEquals(source, actual.get(0).getProvenance().getParentEpisodeId());
        assertEquals(Map.of("scenario", "synthetic", "segment.strategy", "FEATURE_THRESHOLD"), actual.get(0).getLabels());
        byte[] quality = new byte[actual.get(0).getQuality().remaining()];
        actual.get(0).getQuality().duplicate().get(quality);
        assertArrayEquals(new byte[]{1, 2, 4, 8}, quality);
    }

    @Test
    void preservesRawNaNBitsAcrossCheckpoints() throws IOException {
        Segmenter config = Segmenter.threshold(Segmenter.Direction.ABOVE, 5, 3, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO);
        var key = SeriesKeys.of("synthetic.signal", Map.of("site", "test"), "1");
        long bits = 0x7ff8000000000042L;
        String source = Ulid.deterministic(0, 43);
        Episode first = Episodes.regular(key, 0, 1_000_000, new double[]{0, 6, Double.longBitsToDouble(bits)})
                .episodeId(source).source("synthetic-state").createdAt(4_000_001).build();
        Episode last = Episodes.regular(key, 3_000_000, 1_000_000, new double[]{6, 0})
                .episodeId(source).source("synthetic-state").createdAt(4_000_001).build();
        StreamingSegmenter stream = new StreamingSegmenter(config);
        assertEquals(List.of(), stream.onEpisode(first));
        stream = restore(config, checkpoint(stream));
        List<Episode> output = stream.onEpisode(last);
        assertEquals(1, output.size());
        assertEquals(bits, Double.doubleToRawLongBits(Episodes.values(output.get(0))[1]));
    }

    @TestFactory
    List<DynamicTest> rejectsHugeTruncatedClaimsWithoutAllocatingTheClaimedMemory() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String mode : List.of("blob", "buffer")) {
            tests.add(DynamicTest.dynamicTest(mode, () -> {
                Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx64m", "-cp", System.getProperty("java.class.path"), CorruptStateProbe.class.getName(), mode)
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.waitFor(), mode + ": " + output);
            }));
        }
        return tests;
    }

    public static final class CorruptStateProbe {
        public static void main(String[] args) throws Exception {
            try {
                Segmenter config = Segmenter.threshold(Segmenter.Direction.ABOVE, 5, 3, Duration.ZERO,
                        Duration.ZERO, Duration.ZERO, Duration.ZERO);
                var key = SeriesKeys.of("synthetic.signal", Map.of("site", "test"), "1");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(1);
                out.writeUTF(config.strategy() + new TreeMap<>(config.params()).toString());
                out.writeInt(1);
                out.writeUTF(key.getSeriesId());
                out.writeLong(0);
                out.writeInt(args[0].equals("buffer") ? Integer.MAX_VALUE : 1);
                out.writeBoolean(false);
                out.writeInt(1);
                if (args[0].equals("blob")) {
                    out.writeInt(Integer.MAX_VALUE);
                } else {
                    Episode source = Episodes.regular(key, 0, 1_000_000, new double[]{0})
                            .episodeId(Ulid.deterministic(0, 7)).source("synthetic-state").createdAt(1).build();
                    byte[] encoded = new OpenTsxAvro().encode(source);
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
                restore(config, bytes.toByteArray());
                System.err.println("invalid segmentation state was accepted");
                System.exit(2);
            } catch (IOException expected) {
                return;
            } catch (OutOfMemoryError failure) {
                System.err.println("allocated memory from an untrusted segmentation state count");
                System.exit(3);
            }
        }
    }
}
