package org.opentsx.wayang;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.MessageUtil;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.requests.FetchRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.ResponseHeader;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class LatencyProtocolObserver implements AutoCloseable {

    record Entry(String topic, int partition, long offset, Episode episode, long requestNanos, long acknowledgedNanos, Instant at,
                 int increment, short error) { }
    record Pending(RequestHeader header, long nanos, Instant at, int increment, ProduceRequest produce) { }

    final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
    final Map<Uuid, String> topics = new ConcurrentHashMap<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "latency-observer");
        thread.setDaemon(true);
        return thread;
    });
    final ServerSocket server;
    final int backend;
    volatile boolean closing;
    volatile int increment = -1;
    volatile String inputTopic;
    volatile long firstFetch = -1;

    LatencyProtocolObserver(int backend) throws IOException {
        this.backend = backend;
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(() -> {
            while (!closing) {
                try {
                    Socket client = server.accept();
                    client.setTcpNoDelay(true);
                    Socket broker = new Socket(InetAddress.getLoopbackAddress(), backend);
                    broker.setTcpNoDelay(true);
                    sockets.add(client);
                    sockets.add(broker);
                    Map<Integer, Pending> pending = new ConcurrentHashMap<>();
                    threads.submit(() -> requests(client, broker, pending));
                    threads.submit(() -> responses(broker, client, pending));
                } catch (IOException e) {
                    if (!closing) failure.compareAndSet(null, e);
                }
            }
        });
    }

    String bootstrap() { return "127.0.0.1:" + server.getLocalPort(); }

    void begin(String input, int number) {
        inputTopic = input;
        firstFetch = -1;
        increment = number;
    }

    long end() {
        increment = -1;
        check();
        if (firstFetch < 0) throw new IllegalStateException("no Fetch request for input topic " + inputTopic);
        return firstFetch;
    }

    void check() {
        if (failure.get() != null) throw new IllegalStateException("protocol observer failed", failure.get());
    }

    private void requests(Socket client, Socket broker, Map<Integer, Pending> pending) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(broker.getOutputStream());
            byte[] frame;
            while ((frame = readFrame(in)) != null) {
                long nanos = System.nanoTime();
                Instant at = Instant.now();
                ByteBuffer buffer = ByteBuffer.wrap(frame);
                RequestHeader header = RequestHeader.parse(buffer);
                ProduceRequest produce = header.apiKey() == ApiKeys.PRODUCE ? ProduceRequest.parse(buffer, header.apiVersion()) : null;
                if (header.apiKey() == ApiKeys.FETCH && increment >= 0 && firstFetch < 0) {
                    FetchRequest fetch = FetchRequest.parse(buffer, header.apiVersion());
                    if (fetch.fetchData(topics).keySet().stream().anyMatch(p -> inputTopic.equals(p.topic()))) firstFetch = nanos;
                }
                pending.put(header.correlationId(), new Pending(header, nanos, at, increment, produce));
                writeFrame(out, frame);
            }
        } catch (Exception e) {
            if (!closing && !client.isClosed() && !broker.isClosed()) failure.compareAndSet(null, e);
        } finally {
            closeSocket(client);
            closeSocket(broker);
        }
    }

    private void responses(Socket broker, Socket client, Map<Integer, Pending> pending) {
        try {
            DataInputStream in = new DataInputStream(broker.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            byte[] frame;
            while ((frame = readFrame(in)) != null) {
                int correlation = ByteBuffer.wrap(frame).getInt();
                Pending request = pending.remove(correlation);
                if (request == null) throw new IllegalStateException("unknown response correlation " + correlation);
                if (request.header.apiKey() == ApiKeys.METADATA) {
                    frame = metadata(frame, request.header, server.getLocalPort(), topics);
                } else if (request.produce != null) {
                    acknowledge(frame, request, System.nanoTime(), entries);
                }
                writeFrame(out, frame);
            }
        } catch (Exception e) {
            if (!closing && !client.isClosed() && !broker.isClosed()) failure.compareAndSet(null, e);
        } finally {
            closeSocket(client);
            closeSocket(broker);
        }
    }

    static byte[] metadata(byte[] frame, RequestHeader request, int port, Map<Uuid, String> topics) {
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        ResponseHeader header = ResponseHeader.parse(buffer, request.apiKey().responseHeaderVersion(request.apiVersion()));
        MetadataResponse response = MetadataResponse.parse(buffer, request.apiVersion());
        if (response.data().brokers().size() != 1) throw new IllegalStateException("measurement requires one broker");
        response.data().brokers().forEach(b -> b.setHost("127.0.0.1").setPort(port));
        response.data().topics().forEach(t -> { if (t.name() != null) topics.put(t.topicId(), t.name()); });
        ByteBuffer h = MessageUtil.toByteBuffer(header.data(), header.headerVersion());
        ByteBuffer body = MessageUtil.toByteBuffer(response.data(), request.apiVersion());
        ByteBuffer rewritten = ByteBuffer.allocate(h.remaining() + body.remaining());
        rewritten.put(h).put(body);
        return rewritten.array();
    }

    static void acknowledge(byte[] frame, Pending request, long acknowledgedNanos, List<Entry> entries) {
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        ResponseHeader.parse(buffer, ApiKeys.PRODUCE.responseHeaderVersion(request.header.apiVersion()));
        ProduceResponse response = ProduceResponse.parse(buffer, request.header.apiVersion());
        OpenTsxAvro avro = new OpenTsxAvro();
        for (var topic : request.produce.data().topicData()) {
            var result = response.data().responses().stream().filter(t -> t.name().equals(topic.name())).findFirst().orElseThrow();
            for (var partition : topic.partitionData()) {
                var ack = result.partitionResponses().stream().filter(p -> p.index() == partition.index()).findFirst().orElseThrow();
                MemoryRecords records = (MemoryRecords) partition.records();
                long index = 0;
                for (var batch : records.batches()) {
                    for (var record : batch) {
                        Episode episode = avro.decode(MessageUtil.byteBufferToArray(record.value()), Episode.class);
                        long offset = ack.errorCode() == 0 ? ack.baseOffset() + index : -1;
                        index++;
                        entries.add(new Entry(topic.name(), partition.index(), offset, episode,
                                request.nanos, acknowledgedNanos, request.at, request.increment, ack.errorCode()));
                    }
                }
            }
        }
    }

    static byte[] readFrame(DataInputStream in) throws IOException {
        int first = in.read();
        if (first < 0) return null;
        byte[] rest = in.readNBytes(3);
        if (rest.length != 3) throw new EOFException("incomplete Kafka frame length");
        int length = (first << 24) | ((rest[0] & 255) << 16) | ((rest[1] & 255) << 8) | (rest[2] & 255);
        if (length < 4 || length > 64 * 1024 * 1024) throw new IOException("invalid Kafka frame length " + length);
        byte[] frame = in.readNBytes(length);
        if (frame.length != length) throw new EOFException("incomplete Kafka frame body");
        return frame;
    }

    static void writeFrame(DataOutputStream out, byte[] frame) throws IOException {
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            failure.compareAndSet(null, e);
        }
        sockets.remove(socket);
    }

    @Override
    public void close() throws IOException {
        closing = true;
        server.close();
        for (Socket socket : sockets) closeSocket(socket);
        threads.shutdownNow();
        check();
    }
}
