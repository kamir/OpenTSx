package org.opentsx.wayang;

import org.apache.kafka.common.compress.Compression;
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

    private static byte[] produced() {
        ProduceResponseData data = new ProduceResponseData();
        data.responses().add(new ProduceResponseData.TopicProduceResponse().setName("fixture").setPartitionResponses(List.of(
                new ProduceResponseData.PartitionProduceResponse().setIndex(0).setBaseOffset(0).setErrorCode((short) 0))));
        return IncrementLatencyTest.response(PRODUCE.toResponseHeader(), data, VERSION);
    }

    private static byte[] metadata(int correlation, int size) {
        RequestHeader header = new RequestHeader(ApiKeys.METADATA, ApiKeys.METADATA.latestVersion(), "fixture", correlation);
        return ByteBuffer.allocate(size).put(MessageUtil.toByteBuffer(header.data(), header.headerVersion())).array();
    }

    @Test
    void cleanClientCloseSeenBeforeTheResponseDropsAPendingProduce() throws Exception {
        byte[] request = produce();
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false)) {
            try (Socket client = connect(observer); Socket broker = backend.accept()) {
                DataInputStream in = new DataInputStream(broker.getInputStream());
                LatencyProtocolObserver.writeFrame(new DataOutputStream(client.getOutputStream()), request);
                assertArrayEquals(request, LatencyProtocolObserver.readFrame(in));
                client.close();
                assertEquals(-1, in.read());
                byte[] response = produced();
                broker.getOutputStream().write(ByteBuffer.allocate(Integer.BYTES + response.length).putInt(response.length).put(response).array());
            }
            observer.check();
        }
    }

    @Test
    void cleanClientCloseSeenAfterTheResponseWriteDropsAPendingProduce() throws Exception {
        byte[] request = produce();
        byte[] held = metadata(1, 16 << 20);
        try (ServerSocket backend = new ServerSocket()) {
            backend.setReceiveBufferSize(4096);
            backend.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
            try (LatencyProtocolObserver observer = new LatencyProtocolObserver(backend.getLocalPort(), false)) {
                try (Socket client = connect(observer); Socket broker = backend.accept()) {
                    DataInputStream in = new DataInputStream(broker.getInputStream());
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    LatencyProtocolObserver.writeFrame(out, request);
                    LatencyProtocolObserver.writeFrame(out, held);
                    assertArrayEquals(request, LatencyProtocolObserver.readFrame(in));
                    // The unread 16 MiB frame keeps the request thread writing, so it sees the close only after the response.
                    assertEquals(held.length, in.readInt());
                    client.close();
                    LatencyProtocolObserver.writeFrame(new DataOutputStream(broker.getOutputStream()), produced());
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
        byte[] request = metadata(0, 64);
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
        byte[] request = metadata(3, 64);
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
}
