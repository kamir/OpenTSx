package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class TsxSignalSinkIntegrationTest {

    private static void write(KafScaleFixture fixture, String topic, List<JsonNode> signals) {
        CollectionSource<JsonNode> source = new CollectionSource<>(signals, JsonNode.class);
        TsxTopicSink sink = new TsxTopicSink(fixture.bootstrap, topic);
        source.connectTo(0, sink, 0);
        new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(sink));
    }

    @Test
    void writesJsonSignalsWithTheirSeriesKeyAndSchemaHeader() throws Exception {
        KafScaleFixture fixture = new KafScaleFixture();
        String topic = "tsx." + fixture.layout.domain() + ".signals.v1";
        try (Admin admin = KafScaleClients.admin(fixture.bootstrap)) {
            fixture.layout.provisionTopic(admin, topic, (short) 1);
        }
        JsonNode c = TsxDqVectorsTest.cases().get(0);
        List<JsonNode> signals = TsxDqVectorsTest.plan(TsxDqVectorsTest.operator(c), List.of(TsxDqVectorsTest.statistics(c, 0, 0)));
        write(fixture, topic, signals);
        List<ConsumerRecord<byte[], byte[]>> written = fixture.readAll(topic);
        assertEquals(signals.size(), written.size());
        List<JsonNode> actual = new ArrayList<>();
        for (ConsumerRecord<byte[], byte[]> record : written) {
            JsonNode signal = TsxDqVectorsTest.JSON.readTree(record.value());
            assertEquals(signal.path("seriesId").textValue(), new String(record.key(), StandardCharsets.UTF_8));
            assertEquals("opentsx.signal.v1", new String(record.headers().lastHeader(TopicLayout.HEADER_SCHEMA).value(), StandardCharsets.UTF_8));
            actual.add(signal);
        }
        assertEquals(new java.util.HashSet<>(signals), new java.util.HashSet<>(actual));
    }

    @Test
    void validatesTheWholeBatchBeforeSendingAnySignal() throws Exception {
        KafScaleFixture fixture = new KafScaleFixture();
        String topic = "tsx." + fixture.layout.domain() + ".signals.v1";
        try (Admin admin = KafScaleClients.admin(fixture.bootstrap)) {
            fixture.layout.provisionTopic(admin, topic, (short) 1);
        }
        JsonNode c = TsxDqVectorsTest.cases().get(0);
        List<JsonNode> signals = new ArrayList<>(TsxDqVectorsTest.plan(TsxDqVectorsTest.operator(c), List.of(TsxDqVectorsTest.statistics(c, 0, 0))));
        ObjectNode invalid = signals.get(signals.size() - 1).deepCopy();
        invalid.put("schema", "unsupported");
        signals.set(signals.size() - 1, invalid);
        assertThrows(RuntimeException.class, () -> write(fixture, topic, signals));
        assertTrue(fixture.readAll(topic).isEmpty());
    }
}
