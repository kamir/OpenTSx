package org.opentsx.wayang;

import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.junit.jupiter.api.Test;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.stats.StatsAccumulator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyProtocolObserverTest {

    private static final short VERSION = 9;

    @Test
    void idleResetBeforeAFrameDoesNotFailTheMeasurement() throws IOException {
        DataInputStream input = new DataInputStream(new InputStream() {
            @Override
            public int read() throws IOException { throw new SocketException("fixture reset"); }
        });
        assertNull(LatencyProtocolObserver.readFrame(input));
    }

    private static Map<Integer, LatencyProtocolObserver.Pending> pending(ApiKeys... keys) {
        Map<Integer, LatencyProtocolObserver.Pending> pending = new HashMap<>();
        for (int index = 0; index < keys.length; index++) {
            RequestHeader header = new RequestHeader(keys[index], (short) 0, "fixture", index);
            pending.put(index, new LatencyProtocolObserver.Pending(header, 1, Instant.EPOCH, -1, null));
        }
        return pending;
    }

    private static DataInputStream input(byte[] prefix, IOException failure) {
        return new DataInputStream(new InputStream() {
            int position;

            @Override
            public int read() throws IOException {
                if (position < prefix.length) return prefix[position++] & 255;
                if (failure != null) throw failure;
                return -1;
            }
        });
    }

    private interface FrameReader {
        byte[] read(DataInputStream in) throws IOException;
    }

    private static final FrameReader[] READERS = {LatencyProtocolObserver::readFrame, LatencyProtocolObserver::readBrokerFrame};

    @Test
    void brokerResetsBeforeAFrameRemainFailures() {
        SocketException failure = new SocketException("fixture reset");
        assertSame(failure, assertThrows(SocketException.class, () -> LatencyProtocolObserver.readBrokerFrame(input(new byte[0], failure))));
    }

    @Test
    void frameBoundaryEofEndsClientAndBrokerStreams() throws IOException {
        for (FrameReader reader : READERS) assertNull(reader.read(new DataInputStream(new ByteArrayInputStream(new byte[0]))));
    }

    @Test
    void partialFramesKeepResetAndTruncationFailures() throws IOException {
        byte[] payload = {0, 1, 2, 3, 4, 5, 6, 7};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LatencyProtocolObserver.writeFrame(new DataOutputStream(bytes), payload);
        byte[] wire = bytes.toByteArray();
        for (FrameReader reader : READERS) {
            for (int length = 1; length < wire.length; length++) {
                byte[] prefix = Arrays.copyOf(wire, length);
                SocketException failure = new SocketException("fixture reset");
                assertSame(failure, assertThrows(SocketException.class, () -> reader.read(input(prefix, failure))));
                assertThrows(EOFException.class, () -> reader.read(input(prefix, null)));
            }
        }
    }

    @Test
    void completeFramesSurviveBeforeAnIdleReset() throws IOException {
        byte[] payload = {0, 1, 2, 3, 4, 5, 6, 7};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LatencyProtocolObserver.writeFrame(new DataOutputStream(bytes), payload);
        DataInputStream in = input(bytes.toByteArray(), new SocketException("fixture reset"));
        assertArrayEquals(payload, LatencyProtocolObserver.readFrame(in));
        assertNull(LatencyProtocolObserver.readFrame(in));
    }

    @Test
    void otherFirstByteIoFailuresRemainFailures() {
        for (FrameReader reader : READERS) {
            for (IOException failure : new IOException[]{new IOException("fixture I/O failure"), new EOFException("fixture EOF failure")}) {
                assertSame(failure, assertThrows(IOException.class, () -> reader.read(input(new byte[0], failure))));
            }
        }
    }

    @Test
    void responsesRemainPendingUntilTheirCompleteFrameIsForwarded() throws IOException {
        Map<Integer, LatencyProtocolObserver.Pending> pending = pending(ApiKeys.PRODUCE);
        byte[] frame = ByteBuffer.allocate(8).putInt(0).putInt(42).array();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) {
                assertEquals(1, pending.size());
                bytes.write(value);
            }

            @Override
            public void flush() { assertEquals(1, pending.size()); }
        };
        LatencyProtocolObserver.forwardResponse(new DataOutputStream(output), frame, pending);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        LatencyProtocolObserver.writeFrame(new DataOutputStream(expected), frame);
        assertArrayEquals(expected.toByteArray(), bytes.toByteArray());
        assertTrue(pending.isEmpty());
    }

    @Test
    void responseWriteAndFlushFailuresKeepTheCorrelation() throws IOException {
        byte[] frame = ByteBuffer.allocate(8).putInt(0).putInt(42).array();
        for (int position = 0; position <= Integer.BYTES + frame.length; position++) {
            Map<Integer, LatencyProtocolObserver.Pending> pending = pending(ApiKeys.PRODUCE);
            int failedAt = position;
            IOException failure = new IOException("fixture forwarding failure");
            OutputStream output = new OutputStream() {
                int written;

                @Override
                public void write(int value) throws IOException {
                    if (written == failedAt) throw failure;
                    written++;
                }

                @Override
                public void flush() throws IOException {
                    if (written == failedAt) throw failure;
                }
            };
            assertSame(failure, assertThrows(IOException.class,
                    () -> LatencyProtocolObserver.forwardResponse(new DataOutputStream(output), frame, pending)));
            assertEquals(1, pending.size());
        }
    }

    private static void resetAfterRequest(Socket client, Socket observer) throws IOException {
        byte[] request = ByteBuffer.allocate(8).putInt(0).putInt(7).array();
        LatencyProtocolObserver.writeFrame(new DataOutputStream(client.getOutputStream()), request);
        assertArrayEquals(request, LatencyProtocolObserver.readFrame(new DataInputStream(observer.getInputStream())));
        client.setSoLinger(true, 0);
        client.close();
        assertThrows(SocketException.class, () -> observer.getInputStream().read());
    }

    @Test
    void responsesToAClosedClientEndForwardingForAnyRequest() throws IOException {
        byte[] frame = ByteBuffer.allocate(8).putInt(0).putInt(42).array();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(server.getInetAddress(), server.getLocalPort());
             Socket observer = server.accept()) {
            resetAfterRequest(client, observer);
            DataOutputStream out = new DataOutputStream(observer.getOutputStream());
            for (ApiKeys key : new ApiKeys[]{ApiKeys.METADATA, ApiKeys.PRODUCE, ApiKeys.FETCH, ApiKeys.API_VERSIONS}) {
                Map<Integer, LatencyProtocolObserver.Pending> pending = pending(key);
                LatencyProtocolObserver.forwardResponse(out, frame, pending);
                assertTrue(pending.containsKey(0));
            }
        }
    }

    private static ProduceRequest request(String topic, short acks) {
        StatsAccumulator stats = new StatsAccumulator(0.01);
        stats.add(0, 2);
        stats.add(1_000_000, 4);
        byte[] payload = new OpenTsxAvro().encode(stats.toSeriesStats("series", "PT10M", 0, 600_000_000));
        MemoryRecords records = MemoryRecords.withRecords(Compression.zstd().build(), new SimpleRecord(1, payload), new SimpleRecord(2, payload));
        ProduceRequestData data = new ProduceRequestData().setAcks(acks).setTimeoutMs(30000);
        data.topicData().add(new ProduceRequestData.TopicProduceData().setName(topic).setPartitionData(List.of(
                new ProduceRequestData.PartitionProduceData().setIndex(0).setRecords(records))));
        return new ProduceRequest(data, VERSION);
    }

    @Test
    void compressedStatisticsFramesCountRecordsWithoutDecodingEpisodes() {
        ProduceRequest original = request("aggregate", (short) -1);
        RequestHeader header = new RequestHeader(ApiKeys.PRODUCE, VERSION, "measurement", 23);
        ByteBuffer wire = original.serializeWithHeader(header);
        RequestHeader parsed = RequestHeader.parse(wire);
        ProduceRequest request = ProduceRequest.parse(wire, VERSION);
        assertEquals("aggregate", LatencyProtocolObserver.measurementTopic(request, null));
        assertEquals(2, LatencyProtocolObserver.recordCount(request));
        ProduceResponseData data = new ProduceResponseData();
        data.responses().add(new ProduceResponseData.TopicProduceResponse().setName("aggregate").setPartitionResponses(List.of(
                new ProduceResponseData.PartitionProduceResponse().setIndex(0).setBaseOffset(17).setErrorCode((short) 0))));
        List<LatencyProtocolObserver.Entry> entries = new ArrayList<>();
        LatencyProtocolObserver.Pending pending = new LatencyProtocolObserver.Pending(parsed, 1, Instant.EPOCH, -1, request);
        assertEquals(0, LatencyProtocolObserver.acknowledge(IncrementLatencyTest.response(header.toResponseHeader(), data, VERSION), pending, 2, entries, false));
        assertTrue(entries.isEmpty());
        data.responses().find("aggregate").partitionResponses().get(0).setErrorCode((short) 7);
        assertEquals(2, LatencyProtocolObserver.acknowledge(IncrementLatencyTest.response(header.toResponseHeader(), data, VERSION), pending, 3, entries, false));
        assertTrue(entries.isEmpty());
    }

    @Test
    void measurementRejectsMultipleTopicsChangingTopicsAndUnacknowledgedWrites() {
        ProduceRequest request = request("first", (short) -1);
        assertEquals("first", LatencyProtocolObserver.measurementTopic(request, "first"));
        assertEquals("measurement requires one Produce topic per observer", assertThrows(IllegalStateException.class,
                () -> LatencyProtocolObserver.measurementTopic(request, "other")).getMessage());
        request.data().topicData().add(request("second", (short) -1).data().topicData().iterator().next().duplicate());
        assertEquals(2, request.data().topicData().size());
        assertEquals("measurement requires single-topic Produce frames", assertThrows(IllegalStateException.class,
                () -> LatencyProtocolObserver.measurementTopic(request, null)).getMessage());
        assertEquals("measurement requires acknowledged Produce requests", assertThrows(IllegalStateException.class,
                () -> LatencyProtocolObserver.measurementTopic(request("first", (short) 0), null)).getMessage());
    }
}
