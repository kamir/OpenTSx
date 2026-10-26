package org.opentsx.bridge;

import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.Patience;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.WireFormat;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.TimeEncoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Success criterion 2 of TASK-011: {@code TimeSeriesObject -> Episode -> Kafka (both wire formats) -> Episode ->
 * TimeSeriesObject} without loss, time axis included. OpenTSx writes single-object encoding; Confluent framing is
 * what a registry-based producer writes, and OpenTSx reads it with a schema resolver (D10).
 */
class WireRoundTripTest {

    private static final int SCHEMA_ID = 7;
    private static final OpenTsxAvro AVRO = new OpenTsxAvro(id -> id == SCHEMA_ID ? Episode.getClassSchema() : null);

    /** A regular series (10-minute values) and an irregular one (seconds with fractions). */
    private static List<TimeSeriesObject> series() {
        TimeSeriesObject regular = new TimeSeriesObject("wind.turbine.power_active park=ber-01,turbine=T07");
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 144; i++) {
            regular.addValuePair(t0 + i * 600_000L, Math.sin(i / 10.0) * 1000 + i * 1e-9);
        }
        TimeSeriesObject irregular = new TimeSeriesObject("rotor.rpm park=ber-01,turbine=T07");
        double[] xs = {1_700_000_000.0, 1_700_000_000.5, 1_700_000_002.25, 1_700_000_010.0, 1_700_000_010.125};
        for (int i = 0; i < xs.length; i++) {
            irregular.addValuePair(xs[i], 12.5 + i * 0.1);
        }
        return List.of(regular, irregular);
    }

    private static List<Episode> episodes() {
        List<TimeSeriesObject> tsos = series();
        List<Episode> out = new ArrayList<>();
        out.add(LegacyBridge.fromTimeSeriesObject(tsos.get(0), TimeUnitOfX.EPOCH_MILLIS, "legacy:wire-test"));
        out.add(LegacyBridge.fromTimeSeriesObject(tsos.get(1), TimeUnitOfX.EPOCH_SECONDS, "legacy:wire-test"));
        assertEquals(TimeEncoding.REGULAR, out.get(0).getTimeEncoding());
        assertEquals(TimeEncoding.IRREGULAR_DELTA, out.get(1).getTimeEncoding());
        return out;
    }

    private static byte[] encode(Episode e, WireFormat format) {
        if (format == WireFormat.SINGLE_OBJECT) {
            return AVRO.encode(e);
        }
        // Confluent framing: 0x00, 4-byte big-endian schema id, Avro binary body
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.writeBytes(ByteBuffer.allocate(4).putInt(SCHEMA_ID).array());
        try {
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new SpecificDatumWriter<>(Episode.class).write(e, encoder);
            encoder.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** Decodes what came back from Kafka and checks it against the start of the chain. */
    private static void assertLossless(List<Episode> sent, List<byte[]> received, WireFormat format) {
        List<TimeSeriesObject> original = series();
        assertEquals(sent.size(), received.size());
        for (int i = 0; i < sent.size(); i++) {
            assertEquals(format, WireFormat.detect(received.get(i)));
            Episode back = AVRO.decode(received.get(i), Episode.class);
            assertEquals(sent.get(i), back, "the episode survives the broker unchanged");
            TimeUnitOfX unit = i == 0 ? TimeUnitOfX.EPOCH_MILLIS : TimeUnitOfX.EPOCH_SECONDS;
            TimeSeriesObject tso = LegacyBridge.toTimeSeriesObject(back, unit);
            assertEquals(original.get(i).getLabel(), tso.getLabel());
            assertArrayEquals(original.get(i).getYData(), tso.getYData());
            assertEquals(original.get(i).getXValues(), tso.getXValues(), "time axis");
        }
    }

    @ParameterizedTest
    @EnumSource(value = WireFormat.class, names = {"SINGLE_OBJECT", "CONFLUENT"})
    void throughTheKafkaClientRecords(WireFormat format) {
        String topic = "tsx.test.episodes.v2";
        List<Episode> sent = episodes();
        MockProducer<String, byte[]> producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        for (Episode e : sent) {
            producer.send(new ProducerRecord<>(topic, e.getSeries().getSeriesId(), encode(e, format)));
        }
        MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        TopicPartition tp = new TopicPartition(topic, 0);
        consumer.assign(List.of(tp));
        consumer.updateBeginningOffsets(java.util.Map.of(tp, 0L));
        long offset = 0;
        for (ProducerRecord<String, byte[]> r : producer.history()) {
            consumer.addRecord(new ConsumerRecord<>(topic, 0, offset++, r.key().getBytes(), r.value()));
        }
        List<byte[]> received = new ArrayList<>();
        consumer.poll(Duration.ofMillis(100)).forEach(r -> received.add(r.value()));
        assertLossless(sent, received, format);
    }

    @ParameterizedTest
    @EnumSource(value = WireFormat.class, names = {"SINGLE_OBJECT", "CONFLUENT"})
    @EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
    void throughKafScale(WireFormat format) throws Exception {
        String bootstrap = System.getenv("KAFSCALE_BOOTSTRAP");
        String topic = "tsx-wire-" + format.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        try (Admin admin = KafScaleClients.admin(bootstrap)) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 1, (short) 1))).all().get();
        }
        List<Episode> sent = episodes();
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap)) {
            for (Episode e : sent) {
                producer.send(new ProducerRecord<>(topic, e.getSeries().getSeriesId(), encode(e, format))).get();
            }
        }
        TopicPartition tp = new TopicPartition(topic, 0);
        List<byte[]> received = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(bootstrap)) {
            consumer.assign(List.of(tp));
            consumer.seekToBeginning(List.of(tp));
            Patience patience = Patience.standard();
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (received.size() < 2 && System.nanoTime() < deadline) {
                patience.poll(consumer, Duration.ofMillis(500)).forEach(r -> received.add(r.value()));
            }
        }
        assertLossless(sent, received, format);
    }
}
