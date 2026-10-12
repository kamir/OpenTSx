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

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void rejectsInconsistentEpisodes() {
        assertThrows(IllegalArgumentException.class,
                () -> Episodes.regular(key, 0, 10, new double[]{1, 2, 3}).tEnd(20).build());
        assertThrows(IllegalArgumentException.class,
                () -> Episodes.irregular(key, new long[]{1, 2}, new double[]{1}).build());
        assertThrows(IllegalArgumentException.class,
                () -> Episodes.regular(key, 0, 10, new double[]{1}).quality(new byte[]{0, 0}).build());
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
