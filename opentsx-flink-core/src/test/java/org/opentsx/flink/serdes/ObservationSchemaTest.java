package org.opentsx.flink.serdes;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.InvalidNumberEncodingException;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentsx.data.model.Observation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ObservationSchemaTest {

    private static final String TOPIC = "decoder-test-topic";
    private static final int PARTITION = 3;
    private static final long OFFSET = 42;
    private static final String URI = "urn:series:unicode-\u2603-\uD83D\uDE80";
    private static final String FAILURE = "Failed to deserialize observation from topic " + TOPIC
            + " partition " + PARTITION + " offset " + OFFSET;

    @ParameterizedTest
    @MethodSource("rawObservations")
    void rawBinaryPreservesUnicodeTimestampAndValue(Observation expected) throws Exception {
        byte[] payload = encode(expected);
        byte[] original = payload.clone();
        List<Observation> output = new ArrayList<>();

        new ObservationSchema().deserialize(record(payload), collecting(output));

        assertEquals(1, output.size());
        assertObservation(expected, output.get(0));
        assertArrayEquals(original, payload);
    }

    private static Stream<Observation> rawObservations() {
        return Stream.of(new Observation(1234L, URI, -12.5), new Observation(Long.MIN_VALUE, URI, -0.0),
                new Observation(0L, "", Double.MIN_VALUE), new Observation(Long.MAX_VALUE, URI, Double.MAX_VALUE));
    }

    @Test
    void reusedDecoderEmitsIndependentObservations() throws Exception {
        Observation first = new Observation(10L, URI, 2.0);
        Observation second = new Observation(-20L, "urn:series:second", -4.0);
        ObservationSchema schema = new ObservationSchema();
        List<Observation> output = new ArrayList<>();

        schema.deserialize(record(encode(first)), collecting(output));
        schema.deserialize(record(encode(second)), collecting(output));

        assertEquals(2, output.size());
        assertNotSame(output.get(0), output.get(1));
        assertObservation(first, output.get(0));
        assertObservation(second, output.get(1));
    }

    @Test
    void tombstoneBeforeAValidRecordEmitsNothing() throws Exception {
        ObservationSchema schema = new ObservationSchema();
        List<Observation> output = new ArrayList<>();

        schema.deserialize(record(null), collecting(output));
        assertTrue(output.isEmpty());

        Observation expected = new Observation(100L, URI, 3.0);
        schema.deserialize(record(encode(expected)), collecting(output));
        assertEquals(1, output.size());
        assertObservation(expected, output.get(0));
    }

    @Test
    void tombstoneAfterAValidRecordDoesNotRepeatThePreviousObservation() throws Exception {
        ObservationSchema schema = new ObservationSchema();
        List<Observation> output = new ArrayList<>();
        Observation expected = new Observation(100L, URI, 3.0);

        schema.deserialize(record(encode(expected)), collecting(output));
        schema.deserialize(record(null), collecting(output));

        assertEquals(1, output.size());
        assertObservation(expected, output.get(0));
    }

    @Test
    void everyProperRawRecordPrefixFailsWithoutEmitting() throws Exception {
        byte[] payload = encode(new Observation(1234L, URI, -12.5));
        ObservationSchema schema = new ObservationSchema();
        for (int length = 0; length < payload.length; length++) {
            assertDecodeFailure(schema, Arrays.copyOf(payload, length), IOException.class);
        }
    }

    @Test
    void overlongTimestampVarintRetainsItsDecodeCause() {
        byte[] payload = new byte[11];
        Arrays.fill(payload, (byte) 0x80);
        assertDecodeFailure(new ObservationSchema(), payload, InvalidNumberEncodingException.class);
    }

    @Test
    void negativeStringLengthRetainsItsUncheckedDecodeCause() {
        assertDecodeFailure(new ObservationSchema(), new byte[]{0, 1}, AvroRuntimeException.class);
    }

    @Test
    void validRecordAfterDecodeFailuresUsesFreshInputAndStillSkipsTombstones() throws Exception {
        ObservationSchema schema = new ObservationSchema();
        List<Observation> output = new ArrayList<>();
        List<byte[]> malformed = List.of(new byte[0], new byte[]{0, 1}, new byte[]{0});
        for (int index = 0; index < malformed.size(); index++) {
            assertDecodeFailure(schema, malformed.get(index), Exception.class);
            schema.deserialize(record(null), collecting(output));
            assertEquals(index, output.size());
            Observation expected = new Observation((long) index, URI, index + 0.5);
            schema.deserialize(record(encode(expected)), collecting(output));
            assertEquals(index + 1, output.size());
            assertObservation(expected, output.get(index));
        }
    }

    @ParameterizedTest
    @MethodSource("decodeCauses")
    void decodeFailurePreservesTheOriginalCauseAndOnlyAddsRecordCoordinates(Exception expected) throws Exception {
        ObservationSchema schema = new ObservationSchema();
        var field = ObservationSchema.class.getDeclaredField("datumReader");
        field.setAccessible(true);
        field.set(schema, new SpecificDatumReader<Observation>(Observation.getClassSchema()) {
            @Override
            public Observation read(Observation reuse, Decoder decoder) throws IOException {
                if (expected instanceof IOException failure) throw failure;
                throw (AvroRuntimeException) expected;
            }
        });

        IOException failure = assertDecodeFailure(schema, encode(new Observation(1L, URI, 2.0)), expected.getClass());

        assertSame(expected, failure.getCause());
    }

    private static Stream<Exception> decodeCauses() {
        return Stream.of(new IOException("Synthetic I/O decode failure"),
                new AvroRuntimeException("Synthetic unchecked decode failure"));
    }

    @Test
    void collectorRuntimeFailurePropagatesUnchangedAfterOneEmissionAttempt() throws Exception {
        IllegalStateException expected = new IllegalStateException("Synthetic collector failure");
        int[] calls = {0};
        Collector<Observation> output = new Collector<>() {
            @Override
            public void collect(Observation observation) {
                calls[0]++;
                throw expected;
            }

            @Override
            public void close() {}
        };
        byte[] payload = encode(new Observation(1L, URI, 2.0));

        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> new ObservationSchema().deserialize(record(payload), output)));
        assertEquals(1, calls[0]);
    }

    @Test
    void collectorErrorPropagatesUnchangedAfterOneEmissionAttempt() throws Exception {
        AssertionError expected = new AssertionError("Synthetic collector error");
        int[] calls = {0};
        Collector<Observation> output = new Collector<>() {
            @Override
            public void collect(Observation observation) {
                calls[0]++;
                throw expected;
            }

            @Override
            public void close() {}
        };
        byte[] payload = encode(new Observation(1L, URI, 2.0));

        assertSame(expected, assertThrows(AssertionError.class,
                () -> new ObservationSchema().deserialize(record(payload), output)));
        assertEquals(1, calls[0]);
    }

    @Test
    void producedTypeIsTheLegacyObservationClass() {
        assertEquals(Observation.class, new ObservationSchema().getProducedType().getTypeClass());
    }

    private static IOException assertDecodeFailure(ObservationSchema schema, byte[] payload,
                                                    Class<? extends Throwable> causeType) {
        List<Observation> output = new ArrayList<>();
        IOException failure = assertThrows(IOException.class, () -> schema.deserialize(record(payload), collecting(output)));
        assertEquals(FAILURE, failure.getMessage());
        assertInstanceOf(causeType, failure.getCause());
        assertTrue(output.isEmpty());
        return failure;
    }

    private static void assertObservation(Observation expected, Observation actual) {
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getUri().toString(), actual.getUri().toString());
        assertEquals(Double.doubleToRawLongBits(expected.getValue()), Double.doubleToRawLongBits(actual.getValue()));
    }

    private static ConsumerRecord<byte[], byte[]> record(byte[] payload) {
        return new ConsumerRecord<>(TOPIC, PARTITION, OFFSET, null, payload);
    }

    private static Collector<Observation> collecting(List<Observation> output) {
        return new Collector<>() {
            @Override
            public void collect(Observation observation) {
                output.add(observation);
            }

            @Override
            public void close() {}
        };
    }

    private static byte[] encode(Observation observation) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(bytes, null);
        new SpecificDatumWriter<Observation>(Observation.getClassSchema()).write(observation, encoder);
        encoder.flush();
        return bytes.toByteArray();
    }
}
