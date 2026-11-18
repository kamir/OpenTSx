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

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyProtocolObserverTest {

    private static final short VERSION = 9;

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
