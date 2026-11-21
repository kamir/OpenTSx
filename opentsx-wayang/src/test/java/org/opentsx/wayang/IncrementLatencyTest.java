package org.opentsx.wayang;

import org.apache.kafka.common.Node;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.ProduceResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Message;
import org.apache.kafka.common.protocol.MessageUtil;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.ResponseHeader;
import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncrementLatencyTest {

    @Test
    void framingPreservesPayloadAndHandlesFragmentationAndEndOfStream() throws Exception {
        byte[] payload = {0, 1, 2, 3, 4, 5, 6, 7};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LatencyProtocolObserver.writeFrame(new DataOutputStream(bytes), payload);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()) {
            @Override public synchronized int read(byte[] b, int offset, int length) { return super.read(b, offset, Math.min(1, length)); }
        });
        assertArrayEquals(payload, LatencyProtocolObserver.readFrame(in));
        assertNull(LatencyProtocolObserver.readFrame(in));
        assertNull(LatencyProtocolObserver.readFrame(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0}))));
        assertNull(LatencyProtocolObserver.readFrame(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 0, 4, 1}))));
        assertThrows(IOException.class, () -> LatencyProtocolObserver.readFrame(new DataInputStream(new ByteArrayInputStream(new byte[]{-1, -1, -1, -1}))));
    }

    @Test
    void metadataRewritesOnlyBrokerEndpointForLegacyAndFlexibleVersions() {
        for (short version : new short[]{1, 9, 12}) {
            Uuid topicId = new Uuid(1, 2);
            var topic = new MetadataResponseData.MetadataResponseTopic().setName("input").setTopicId(topicId);
            MetadataResponse source = MetadataResponse.prepareResponse(version, 0, List.of(new Node(0, "backend", 9092)),
                    "cluster", 0, List.of(topic), MetadataResponse.AUTHORIZED_OPERATIONS_OMITTED);
            RequestHeader request = new RequestHeader(ApiKeys.METADATA, version, "client", 17);
            byte[] frame = response(request.toResponseHeader(), source.data(), version);
            Map<Uuid, String> names = new HashMap<>();
            ByteBuffer rewritten = ByteBuffer.wrap(LatencyProtocolObserver.metadata(frame, request, 39092, names));
            assertEquals(17, ResponseHeader.parse(rewritten, request.toResponseHeader().headerVersion()).correlationId());
            MetadataResponse actual = MetadataResponse.parse(rewritten, version);
            assertEquals("127.0.0.1", actual.brokers().iterator().next().host());
            assertEquals(39092, actual.brokers().iterator().next().port());
            ByteBuffer original = ByteBuffer.wrap(frame);
            ResponseHeader.parse(original, request.toResponseHeader().headerVersion());
            MetadataResponse decoded = MetadataResponse.parse(original, version);
            assertEquals(decoded.data().topics(), actual.data().topics());
            assertEquals("backend", source.brokers().iterator().next().host());
            if (version >= 10) assertEquals("input", names.get(topicId));
        }
    }

    @Test
    void acknowledgedZstdBatchesKeepOriginalPayloadAndEventTimestamp() {
        Episode episode = episode("input", 0, new double[]{1, 2});
        byte[] payload = new OpenTsxAvro().encode(episode);
        var data = new ProduceRequestData().setAcks((short) -1).setTimeoutMs(30000);
        MemoryRecords records = MemoryRecords.withRecords(Compression.zstd().build(), new SimpleRecord(123, payload));
        data.topicData().add(new ProduceRequestData.TopicProduceData().setName("input").setPartitionData(List.of(
                new ProduceRequestData.PartitionProduceData().setIndex(0).setRecords(records))));
        short version = 9;
        RequestHeader header = new RequestHeader(ApiKeys.PRODUCE, version, "producer", 19);
        ProduceRequest request = new ProduceRequest(data, version);
        ByteBuffer wire = request.serializeWithHeader(header);
        RequestHeader parsedHeader = RequestHeader.parse(wire);
        ProduceRequest parsed = ProduceRequest.parse(wire, version);
        var result = new ProduceResponseData();
        result.responses().add(new ProduceResponseData.TopicProduceResponse().setName("input").setPartitionResponses(List.of(
                new ProduceResponseData.PartitionProduceResponse().setIndex(0).setBaseOffset(40).setErrorCode((short) 0))));
        List<LatencyProtocolObserver.Entry> entries = new ArrayList<>();
        LatencyProtocolObserver.acknowledge(response(header.toResponseHeader(), result, version),
                new LatencyProtocolObserver.Pending(parsedHeader, 100, Instant.EPOCH, 3, parsed), 200, entries);
        assertEquals(1, entries.size());
        assertEquals(40, entries.get(0).offset());
        assertEquals(100, entries.get(0).requestNanos());
        assertEquals(200, entries.get(0).acknowledgedNanos());
        assertArrayEquals(payload, new OpenTsxAvro().encode(entries.get(0).episode()));
        assertEquals(123, records.records().iterator().next().timestamp());
        result.responses().find("input").partitionResponses().get(0).setErrorCode((short) 7);
        LatencyProtocolObserver.acknowledge(response(header.toResponseHeader(), result, version),
                new LatencyProtocolObserver.Pending(header, 300, Instant.EPOCH, 3, parsed), 400, entries);
        assertEquals(7, entries.get(1).error());
    }

    @Test
    void provenanceUsesRawSeriesIdentityAndEveryOverlappingEpisode() {
        Episode first = episode("one", 0, new double[]{1, 2});
        Episode second = episode("two", 2_000_000, new double[]{3, 4});
        Episode output = episode("out", 1_000_000, new double[]{1, 2});
        output.getSeries().getTags().put("flow", "latency");
        assertEquals(List.of(first, second), IncrementLatency.contributors(output, List.of(first, second)));
        assertThrows(IllegalStateException.class, () -> IncrementLatency.contributors(output, List.of()));
    }

    @Test
    void percentilesUseNearestRankAndNeverInventAnEmptySample() {
        assertEquals(3, IncrementLatency.percentile(List.of(4.0, 1.0, 5.0, 3.0, 2.0), 0.5));
        assertEquals(5, IncrementLatency.percentile(List.of(4.0, 1.0, 5.0, 3.0, 2.0), 0.95));
        assertThrows(IllegalArgumentException.class, () -> IncrementLatency.percentile(List.of(), 0.5));
    }

    static Episode episode(String id, long from, double[] values) {
        return Episodes.regular(SeriesKeys.of("wind.speed", Map.of("turbine", "T01"), "m/s"), from, 1_000_000, values)
                .episodeId(id).createdAt(0).build();
    }

    static byte[] response(ResponseHeader header, Message body, short version) {
        ByteBuffer h = MessageUtil.toByteBuffer(header.data(), header.headerVersion());
        ByteBuffer b = MessageUtil.toByteBuffer(body, version);
        return ByteBuffer.allocate(h.remaining() + b.remaining()).put(h).put(b).array();
    }
}
