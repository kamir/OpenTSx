package org.opentsx.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
import org.junit.jupiter.api.Test;
import org.opentsx.model.kafka.OpenTsxDeserializer;
import org.opentsx.model.kafka.OpenTsxSerializer;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodesAndWireFormatTest {

    private final OpenTsxAvro avro = new OpenTsxAvro();
    private final SeriesKey key = SeriesKeys.of("wind.turbine.power_active", Map.of("park", "ber-01", "turbine", "T07"), "kW");

    @Test
    void regularEpisodeDerivesTimeAxisAndSummary() {
        Episode e = Episodes.regular(key, 1_000_000L, 250_000L, new double[]{1, 2, 3, 4}).source("test").build();
        assertEquals(TimeEncoding.REGULAR, e.getTimeEncoding());
        assertEquals(4, e.getCount());
        assertEquals(2_000_000L, Micros.of(e.getTEnd()));
        assertArrayEquals(new long[]{1_000_000, 1_250_000, 1_500_000, 1_750_000}, Episodes.timestampsMicros(e));
        assertEquals(2.5, e.getSummary().getMean());
        assertEquals(26, e.getEpisodeId().length());
    }

    @Test
    void irregularEpisodeRoundTripsTimestamps() {
        long[] ts = {10, 11, 15, 1_000_000};
        Episode e = Episodes.irregular(key, ts, new double[]{1, 2, 3, 4}).build();
        assertArrayEquals(ts, Episodes.timestampsMicros(e));
        assertEquals(1_000_001L, Micros.of(e.getTEnd()));
    }

    @Test
    void ofPicksRegularForEqualStepsAndIrregularOtherwise() {
        Episode regular = Episodes.of(key, new long[]{10, 20, 30}, new double[]{1, 2, 3}).build();
        assertEquals(TimeEncoding.REGULAR, regular.getTimeEncoding());
        assertEquals(10L, regular.getIntervalMicros());
        assertEquals(40L, Micros.of(regular.getTEnd()));
        Episode uneven = Episodes.of(key, new long[]{10, 20, 31}, new double[]{1, 2, 3}).build();
        assertEquals(TimeEncoding.IRREGULAR_DELTA, uneven.getTimeEncoding());
        assertArrayEquals(new long[]{10, 20, 31}, Episodes.timestampsMicros(uneven));
        Episode single = Episodes.of(key, new long[]{10}, new double[]{1}).build();
        assertEquals(TimeEncoding.IRREGULAR_DELTA, single.getTimeEncoding());
        assertEquals("values: empty; expected at least one value",
                assertThrows(IllegalArgumentException.class, () -> Episodes.of(key, new long[0], new double[0])).getMessage());
        assertEquals("timestamps: length 2 differs from the length 3 of values; expected one timestamp per value",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.of(key, new long[]{10, 20}, new double[]{1, 2, 3})).getMessage());
        assertEquals("timestamps: timestamp 1 is not after timestamp 0; expected strictly increasing timestamps",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.of(key, new long[]{20, 10}, new double[]{1, 2}).build()).getMessage());
    }

    @Test
    void rejectsInconsistentEpisodes() {
        assertEquals("tEnd: \"20\" is not after the last timestamp 20; expected a time after the last timestamp",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.regular(key, 0, 10, new double[]{1, 2, 3}).tEnd(20).build()).getMessage());
        assertEquals("timestamps: length 2 differs from the length 1 of values; expected one timestamp per value",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.irregular(key, new long[]{1, 2}, new double[]{1}).build()).getMessage());
        assertEquals("quality: length 2 differs from the count 1; expected one byte per value",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.regular(key, 0, 10, new double[]{1}).quality(new byte[]{0, 0}).build()).getMessage());
        assertEquals("intervalMicros: \"0\" is not allowed; expected a positive number of microseconds",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.regular(key, 0, 0, new double[]{1})).getMessage());
        assertEquals("values: empty; expected at least one value",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.irregular(key, new long[0], new double[0])).getMessage());
        assertEquals("tEnd: \"5\" is not after the last timestamp 5; expected a time after the last timestamp",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.irregular(key, new long[]{1, 5}, new double[]{1, 2}).tEnd(5).build()).getMessage());
        assertEquals("timestamps: timestamp 1 is not after timestamp 0; expected strictly increasing timestamps",
                assertThrows(IllegalArgumentException.class,
                        () -> Episodes.irregular(key, new long[]{5, 5}, new double[]{1, 2}).build()).getMessage());
    }

    @Test
    void validateNamesTheFieldOfEveryRefusal() {
        Episode regular = Episodes.regular(key, 1_000_000L, 1_000_000L, new double[]{1, 2, 3}).episodeId("r").createdAt(0).build();
        Episode empty = Episodes.regular(key, 1_000_000L, 1_000_000L, new double[0]).episodeId("e").createdAt(0).build();
        assertEquals("count: \"4\" differs from the length 3 of values; expected the number of values",
                refusal(Episode.newBuilder(regular).setCount(4).build()));
        assertEquals("tEnd: \"3000000\" is not after the last timestamp 3000000; expected a time after the last timestamp",
                refusal(Episode.newBuilder(regular).setTEnd(Micros.toInstant(3_000_000L)).build()));
        assertEquals("tEnd: \"1000000\" is not after tStart 1000000; expected a time after tStart",
                refusal(Episode.newBuilder(empty).setTEnd(Micros.toInstant(1_000_000L)).build()));
        assertEquals("quality: length 1 differs from the count 3; expected one byte per value",
                refusal(Episode.newBuilder(regular).setQuality(ByteBuffer.wrap(new byte[]{0})).build()));
    }

    @Test
    void validateRefusesAMissingOrNonIncreasingTimeAxis() {
        Episode regular = Episodes.regular(key, 1_000_000L, 1_000_000L, new double[]{1, 2, 3}).episodeId("r").createdAt(0).build();
        Episode irregular = Episodes.irregular(key, new long[]{100, 110, 130}, new double[]{1, 2, 3}).episodeId("i").createdAt(0).build();
        assertEquals("intervalMicros: missing in a REGULAR episode; expected a positive number of microseconds",
                refusal(Episode.newBuilder(regular).setIntervalMicros(null).build()));
        assertEquals("intervalMicros: \"0\" is not allowed; expected a positive number of microseconds",
                refusal(Episode.newBuilder(regular).setIntervalMicros(0L).build()));
        assertEquals("intervalMicros: \"-1000000\" is not allowed; expected a positive number of microseconds",
                refusal(Episode.newBuilder(regular).setIntervalMicros(-1_000_000L).build()));
        assertEquals("timeDeltas: missing in an IRREGULAR_DELTA episode of 3 values; expected the encoded deltas of its timestamps",
                refusal(Episode.newBuilder(irregular).setTimeDeltas(null).build()));
        Episode backwards = Episode.newBuilder(irregular).setTimeDeltas(ByteBuffer.wrap(new byte[]{0x14, 0x4F})).build();
        assertArrayEquals(new long[]{100, 110, 80}, Episodes.timestampsMicros(backwards));
        assertEquals("timeDeltas: timestamp 2 is not after timestamp 1; expected strictly increasing timestamps",
                refusal(backwards));
    }

    private static String refusal(Episode e) {
        return assertThrows(IllegalArgumentException.class, () -> Episodes.validate(e)).getMessage();
    }

    @Test
    void noSourceFileCarriesALiteralProducerVersion() throws IOException {
        Pattern literal = Pattern.compile("opentsx-[a-z0-9-]+/[0-9]");
        List<Path> scanned;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            scanned = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(scanned.contains(Path.of("src/main/java/org/opentsx/model/Episodes.java")), scanned.toString());
        List<String> hits = new ArrayList<>();
        for (Path f : scanned) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (literal.matcher(lines.get(i)).find()) {
                    hits.add(f + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), hits);
    }

    @Test
    void producerCarriesTheVersionStampedByMaven() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Episodes.class.getResourceAsStream("version.properties")) {
            assertNotNull(in, "org/opentsx/model/version.properties is on the class path");
            p.load(in);
        }
        String version = p.getProperty("version");
        assertFalse(version.contains("${"), version);
        Matcher pom = Pattern.compile("<version>([^<]+)</version>").matcher(Files.readString(Path.of("pom.xml")));
        assertTrue(pom.find());
        assertEquals(pom.group(1), version);
        assertEquals("opentsx-model/" + version, Episodes.PRODUCER);
        assertEquals("opentsx-windpark/" + version, Episodes.producer("opentsx-windpark"));
        assertEquals("opentsx-model/" + version,
                Episodes.regular(key, 0, 1_000, new double[]{1}).build().getProvenance().getProducer());
        assertEquals("module: \"opentsx-market/3\" is not allowed; expected a Maven artifact id such as opentsx-market",
                assertThrows(IllegalArgumentException.class, () -> Episodes.producer("opentsx-market/3")).getMessage());
    }

    @Test
    void aSingleObjectPayloadCutInTheBodyNamesTheSchema() {
        Episode e = Episodes.regular(key, 0, 1_000, new double[]{1, 2}).episodeId("cut").createdAt(0).build();
        byte[] cut = java.util.Arrays.copyOf(avro.encode(e), 40);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> avro.decode(cut));
        assertEquals("org.opentsx.model.v2.Episode: cannot decode a single-object payload of 40 bytes;"
                + " expected a complete payload written with this schema", ex.getMessage());
        assertInstanceOf(EOFException.class, rootCause(ex));
    }

    @Test
    void aConfluentPayloadCutInTheBodyNamesTheSchema() {
        Observation o = Observation.newBuilder().setSeriesId("abc").setTs(Micros.toInstant(5)).setValue(1.5).build();
        byte[] body = java.util.Arrays.copyOfRange(avro.encode(o), 10, 14);
        byte[] framed = ByteBuffer.allocate(5 + body.length).put((byte) 0).putInt(42).put(body).array();
        OpenTsxAvro withRegistry = new OpenTsxAvro(id -> id == 42 ? Observation.getClassSchema() : null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> withRegistry.decode(framed));
        assertEquals("org.opentsx.model.v2.Observation: cannot decode a Confluent payload of 9 bytes with schema id 42;"
                + " expected a complete payload written with this schema", ex.getMessage());
        assertInstanceOf(EOFException.class, rootCause(ex));
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void fingerprintsMatchPython() {
        JsonNode fps = TestVectors.load("wire-format.json").get("fingerprints");
        for (Class<? extends org.apache.avro.specific.SpecificRecord> type : OpenTsxAvro.TOP_LEVEL_TYPES) {
            Schema s = org.apache.avro.specific.SpecificData.get().getSchema(type);
            assertEquals(fps.get(s.getName()).asText(), XxHash64.hex(OpenTsxAvro.fingerprint(s)), s.getName());
        }
    }

    @Test
    void episodesEncodeToTheSameBytesAsPython() {
        for (JsonNode v : TestVectors.load("wire-format.json").get("episodes")) {
            Episode built = fromSpec(v.get("spec"));
            byte[] encoded = avro.encode(built);
            assertEquals(v.get("hex").asText(), TestVectors.hex(encoded), v.get("spec").get("kind").asText());
            Episode decoded = avro.decode(TestVectors.bytes(v.get("hex").asText()), Episode.class);
            assertEquals(built, decoded);
        }
    }

    @Test
    void observationEncodesToTheSameBytesAsPython() {
        JsonNode v = TestVectors.load("wire-format.json").get("observation");
        JsonNode r = v.get("record");
        Observation o = Observation.newBuilder()
                .setSeriesId(r.get("seriesId").asText())
                .setTs(Micros.toInstant(r.get("ts").asLong()))
                .setValue(r.get("value").asDouble())
                .setQuality(null)
                .build();
        assertEquals(v.get("hex").asText(), TestVectors.hex(avro.encode(o)));
    }

    @Test
    void decodesConfluentFramingWithResolver() {
        Observation o = Observation.newBuilder().setSeriesId("abc").setTs(Micros.toInstant(5)).setValue(1.5).build();
        byte[] body = java.util.Arrays.copyOfRange(avro.encode(o), 10, avro.encode(o).length);
        byte[] framed = ByteBuffer.allocate(5 + body.length).put((byte) 0).putInt(42).put(body).array();
        OpenTsxAvro withRegistry = new OpenTsxAvro(id -> id == 42 ? Observation.getClassSchema() : null);
        assertEquals(o, withRegistry.decode(framed, Observation.class));
        assertThrows(NullPointerException.class, () -> avro.decode(framed));
    }

    @Test
    void readsPayloadsOfAnOlderSchemaVersionOnceRegistered() throws Exception {
        Schema v0 = SchemaBuilder.record("Observation").namespace("org.opentsx.model.v2").fields()
                .requiredString("seriesId")
                .name("ts").type(org.apache.avro.LogicalTypes.timestampMicros()
                        .addToSchema(Schema.create(Schema.Type.LONG))).noDefault()
                .requiredDouble("value")
                .endRecord();
        GenericRecord old = new GenericData.Record(v0);
        old.put("seriesId", "abc");
        old.put("ts", 7L);
        old.put("value", 2.0);
        byte[] payload = new BinaryMessageEncoder<GenericRecord>(GenericData.get(), v0).encode(old).array();

        assertThrows(IllegalArgumentException.class, () -> avro.decode(payload));
        avro.registerWriterSchema(v0);
        Observation o = avro.decode(payload, Observation.class);
        assertEquals("abc", o.getSeriesId());
        assertNull(o.getQuality());
    }

    @Test
    void doublesSurviveTheRoundTripAtFullPrecision() {
        // values that are not representable as float must come back bit-identical
        double[] v = {10.67, 9.82, Math.PI, 1e-300, -0.1, Double.MAX_VALUE};
        Episode e = Episodes.regular(key, 0, 1_000, v).build();
        assertArrayEquals(v, Episodes.values(avro.decode(avro.encode(e), Episode.class)));
    }

    @Test
    void kafkaSerdeRoundTrip() {
        Episode e = Episodes.regular(key, 0, 1_000, new double[]{1, 2}).build();
        try (OpenTsxSerializer ser = new OpenTsxSerializer(); OpenTsxDeserializer de = new OpenTsxDeserializer()) {
            assertEquals(e, de.deserialize("t", ser.serialize("t", e)));
            assertNull(ser.serialize("t", null));
        }
    }

    static Episode fromSpec(JsonNode s) {
        SeriesKey key = SeriesKeys.of(s.get("metric").asText(), TestVectors.map(s.get("tags")), s.get("unit").asText());
        double[] values = TestVectors.doubles(s.get("values"));
        Episodes.Draft d = "regular".equals(s.get("kind").asText())
                ? Episodes.regular(key, s.get("tStartUs").asLong(), s.get("intervalUs").asLong(), values)
                : Episodes.irregular(key, TestVectors.longs(s.get("timestampsUs")), values).tEnd(s.get("tEndUs").asLong());
        d.episodeId(s.get("episodeId").asText())
                .createdAt(s.get("createdAtUs").asLong())
                .source(s.get("source").asText())
                .producer(s.get("producer").asText());
        TestVectors.map(s.get("labels")).forEach(d::label);
        if (!s.get("bucketId").isNull()) {
            d.bucketId(s.get("bucketId").asText());
        }
        if (s.has("qualityHex")) {
            d.quality(TestVectors.bytes(s.get("qualityHex").asText()));
        }
        JsonNode seg = s.get("segmentation");
        d.segmentation(Segmentation.newBuilder()
                .setStrategy(SegmentationStrategy.valueOf(seg.get("strategy").asText()))
                .setParams(SeriesKeys.sortedByUtf8(TestVectors.map(seg.get("params"))))
                .setAnchorTs(seg.get("anchorTs").isNull() ? null : Micros.toInstant(seg.get("anchorTs").asLong()))
                .setSourceEpisodeId(seg.get("sourceEpisodeId").isNull() ? null : seg.get("sourceEpisodeId").asText())
                .build());
        return d.build();
    }
}
