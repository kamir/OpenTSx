package org.opentsx.wayang;

import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.MessageUtil;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyProtocolObserverConnectionTest {

    private static final short VERSION = 9;
    private static final RequestHeader PRODUCE = new RequestHeader(ApiKeys.PRODUCE, VERSION, "fixture", 0);

    private static Socket connect(LatencyProtocolObserver observer) throws IOException {
        return new Socket(InetAddress.getLoopbackAddress(), observer.server.getLocalPort());
    }

    private static byte[] produce() {
        MemoryRecords records = MemoryRecords.withRecords(Compression.NONE, new SimpleRecord(1, new byte[]{1}));
        ProduceRequestData data = new ProduceRequestData().setAcks((short) -1).setTimeoutMs(30000);
        data.topicData().add(new ProduceRequestData.TopicProduceData().setName("fixture").setPartitionData(List.of(
                new ProduceRequestData.PartitionProduceData().setIndex(0).setRecords(records))));
        return MessageUtil.byteBufferToArray(new ProduceRequest(data, VERSION).serializeWithHeader(PRODUCE));
    }

    private static byte[] produced(short error) {
        ProduceResponseData data = new ProduceResponseData();
        data.responses().add(new ProduceResponseData.TopicProduceResponse().setName("fixture").setPartitionResponses(List.of(
                new ProduceResponseData.PartitionProduceResponse().setIndex(0).setBaseOffset(error == 0 ? 0 : -1).setErrorCode(error))));
        return IncrementLatencyTest.response(PRODUCE.toResponseHeader(), data, VERSION);
    }

    private static byte[] request(ApiKeys key, int correlation, int size) {
        RequestHeader header = new RequestHeader(key, key.latestVersion(), "fixture", correlation);
        return ByteBuffer.allocate(size).put(MessageUtil.toByteBuffer(header.data(), header.headerVersion())).array();
    }

    // With this receive buffer, an unread 16 MiB frame keeps the request thread writing until the test drains the broker side.
    private static ServerSocket gatedBackend() throws IOException {
        ServerSocket backend = new ServerSocket();
        backend.setReceiveBufferSize(4096);
        backend.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
        return backend;
    }

    @Test
    void lateProduceErrorAfterACleanClientCloseIsDroppedUnrecorded() throws Exception {
        byte[] request = produce();
        byte[] versions = request(ApiKeys.API_VERSIONS, 1, 64);
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false)) {
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                DataInputStream in = new DataInputStream(broker.getInputStream());
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                LatencyProtocolObserver.writeFrame(out, request);
                LatencyProtocolObserver.writeFrame(out, versions);
                assertArrayEquals(request, LatencyProtocolObserver.readFrame(in));
                assertArrayEquals(versions, LatencyProtocolObserver.readFrame(in));
                client.close();
                assertEquals(-1, in.read());
                DataOutputStream responses = new DataOutputStream(broker.getOutputStream());
                LatencyProtocolObserver.writeFrame(responses, produced((short) 6));
                // This write completes only after the observer has read the Produce response before it.
                LatencyProtocolObserver.writeFrame(responses, ByteBuffer.allocate(16 << 20).putInt(1).array());
            }
            assertEquals(1, observer.produceRecords.get());
            assertEquals(0, observer.produceErrors.get());
            assertTrue(observer.entries.isEmpty());
            observer.check();
        }
    }

    @Test
    void cleanClientCloseSeenAfterTheResponseWriteDropsAPendingProduce() throws Exception {
        byte[] request = produce();
        byte[] held = request(ApiKeys.METADATA, 1, 16 << 20);
        try (ServerSocket backend = gatedBackend()) {
            try (LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false)) {
                try (Socket client = connect(observer); Socket broker = backend.accept()) {
                    DataInputStream in = new DataInputStream(broker.getInputStream());
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    LatencyProtocolObserver.writeFrame(out, request);
                    LatencyProtocolObserver.writeFrame(out, held);
                    assertArrayEquals(request, LatencyProtocolObserver.readFrame(in));
                    assertEquals(held.length, in.readInt());
                    client.close();
                    LatencyProtocolObserver.writeFrame(new DataOutputStream(broker.getOutputStream()), produced((short) 0));
                    in.transferTo(OutputStream.nullOutputStream());
                }
                observer.check();
            }
        }
    }

    @Test
    void clientResetDropsAPendingProduce() throws Exception {
        byte[] request = produce();
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false)) {
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                DataInputStream in = new DataInputStream(broker.getInputStream());
                LatencyProtocolObserver.writeFrame(new DataOutputStream(client.getOutputStream()), request);
                assertArrayEquals(request, LatencyProtocolObserver.readFrame(in));
                client.setSoLinger(true, 0);
                client.close();
                assertEquals(-1, in.read());
            }
            observer.check();
        }
    }

    @Test
    void brokerResetWhileTheClientIsConnectedFailsTheObserver() throws Exception {
        byte[] request = request(ApiKeys.METADATA, 0, 64);
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false);
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                LatencyProtocolObserver.writeFrame(new DataOutputStream(client.getOutputStream()), request);
                assertArrayEquals(request, LatencyProtocolObserver.readFrame(new DataInputStream(broker.getInputStream())));
                broker.setSoLinger(true, 0);
                broker.close();
                assertEquals(-1, client.getInputStream().read());
            }
            IllegalStateException failure = assertThrows(IllegalStateException.class, observer::check);
            assertEquals("protocol observer failed", failure.getMessage());
            assertInstanceOf(SocketException.class, failure.getCause());
            assertThrows(IllegalStateException.class, observer::close);
        }
    }

    @Test
    void unknownResponseCorrelationFailsTheObserver() throws Exception {
        byte[] request = request(ApiKeys.METADATA, 3, 64);
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false);
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                LatencyProtocolObserver.writeFrame(new DataOutputStream(client.getOutputStream()), request);
                assertArrayEquals(request, LatencyProtocolObserver.readFrame(new DataInputStream(broker.getInputStream())));
                LatencyProtocolObserver.writeFrame(new DataOutputStream(broker.getOutputStream()), ByteBuffer.allocate(8).putInt(4).putInt(0).array());
                assertEquals(-1, client.getInputStream().read());
            }
            IllegalStateException failure = assertThrows(IllegalStateException.class, observer::check);
            assertEquals("protocol observer failed", failure.getMessage());
            assertEquals("unknown response correlation 4", failure.getCause().getMessage());
            assertThrows(IllegalStateException.class, observer::close);
        }
    }

    @Test
    void truncatedClientFrameSeenByTheRequestThreadIsAbandonment() throws Exception {
        byte[] request = produce();
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false)) {
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                DataInputStream in = new DataInputStream(broker.getInputStream());
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                LatencyProtocolObserver.writeFrame(out, request);
                out.write(new byte[]{0, 0, 0, 100, 1, 2});
                client.close();
                assertArrayEquals(request, LatencyProtocolObserver.readFrame(in));
                assertEquals(-1, in.read());
            }
            observer.check();
        }
    }

    // The first response cannot reach the closed client. The last completes only after the observer read the others.
    private static void respondToTheClosedClient(Socket broker, byte[]... between) throws IOException {
        DataOutputStream out = new DataOutputStream(broker.getOutputStream());
        byte[] response = new byte[16 << 20];
        LatencyProtocolObserver.writeFrame(out, ByteBuffer.wrap(response).putInt(0, 2).array());
        for (byte[] frame : between) LatencyProtocolObserver.writeFrame(out, frame);
        LatencyProtocolObserver.writeFrame(out, ByteBuffer.wrap(response).putInt(0, 1).array());
    }

    @Test
    void truncatedClientFrameAfterADroppedResponseIsAbandonment() throws Exception {
        byte[] request = produce();
        byte[] versions = request(ApiKeys.API_VERSIONS, 2, 64);
        byte[] held = request(ApiKeys.API_VERSIONS, 1, 16 << 20);
        try (ServerSocket backend = gatedBackend();
             LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false)) {
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                DataInputStream in = new DataInputStream(broker.getInputStream());
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                for (byte[] frame : List.of(request, versions, held)) LatencyProtocolObserver.writeFrame(out, frame);
                out.write(new byte[]{0, 0, 0, 100, 1, 2});
                assertArrayEquals(request, LatencyProtocolObserver.readFrame(in));
                assertArrayEquals(versions, LatencyProtocolObserver.readFrame(in));
                assertEquals(held.length, in.readInt());
                client.close();
                respondToTheClosedClient(broker, produced((short) 6));
                assertEquals(held.length, in.transferTo(OutputStream.nullOutputStream()));
            }
            assertEquals(1, observer.produceRecords.get());
            assertEquals(0, observer.produceErrors.get());
            observer.check();
        }
    }

    private static Throwable malformedClientFrameFailure(boolean responseFirst) throws Exception {
        byte[] malformed = ByteBuffer.allocate(8).putShort((short) 0x7fff).putShort((short) 0).putInt(5).array();
        byte[] versions = request(ApiKeys.API_VERSIONS, 2, 64);
        byte[] held = request(ApiKeys.API_VERSIONS, 1, 16 << 20);
        try (ServerSocket backend = gatedBackend()) {
            LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false);
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                DataInputStream in = new DataInputStream(broker.getInputStream());
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                if (responseFirst) {
                    for (byte[] frame : List.of(versions, held)) LatencyProtocolObserver.writeFrame(out, frame);
                    assertArrayEquals(versions, LatencyProtocolObserver.readFrame(in));
                    assertEquals(held.length, in.readInt());
                }
                LatencyProtocolObserver.writeFrame(out, malformed);
                client.close();
                if (responseFirst) {
                    respondToTheClosedClient(broker);
                    assertEquals(held.length, in.readNBytes(held.length).length);
                }
                assertEquals(-1, in.read());
            }
            Throwable failure = assertThrows(IllegalStateException.class, observer::check).getCause();
            assertThrows(IllegalStateException.class, observer::close);
            return failure;
        }
    }

    @Test
    void completeMalformedClientFrameFailsWhicheverThreadSeesTheCloseFirst() throws Exception {
        Throwable requestFirst = malformedClientFrameFailure(false);
        Throwable responseFirst = malformedClientFrameFailure(true);
        assertInstanceOf(InvalidRequestException.class, requestFirst);
        assertInstanceOf(InvalidRequestException.class, responseFirst);
        assertEquals(requestFirst.getMessage(), responseFirst.getMessage());
    }

    private static Throwable brokerCloseFailure(boolean pendingRequest) throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false);
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                if (pendingRequest) {
                    byte[] request = request(ApiKeys.METADATA, 0, 64);
                    LatencyProtocolObserver.writeFrame(new DataOutputStream(client.getOutputStream()), request);
                    assertArrayEquals(request, LatencyProtocolObserver.readFrame(new DataInputStream(broker.getInputStream())));
                }
                broker.close();
                assertEquals(-1, client.getInputStream().read());
            }
            Throwable failure = assertThrows(IllegalStateException.class, observer::check).getCause();
            assertThrows(IllegalStateException.class, observer::close);
            return failure;
        }
    }

    @Test
    void brokerCloseWhileTheClientIsConnectedFailsTheObserver() throws Exception {
        assertEquals("broker closed the connection; pending requests: 1", brokerCloseFailure(true).getMessage());
        assertEquals("broker closed the connection; pending requests: 0", brokerCloseFailure(false).getMessage());
    }
}
