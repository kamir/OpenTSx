package org.opentsx.wayang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.channels.JavaChannelInstance;
import org.apache.wayang.java.channels.StreamChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Java execution of {@link TsxTopicSink}. The operator returns only after the broker acknowledged every record, so
 * a checkpoint written after the job never runs ahead of the output (S6.3 of the specification).
 */
public final class JavaTsxTopicSink extends TsxTopicSink implements JavaExecutionOperator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SIGNAL_FIELDS = Set.of("schema", "id", "job", "site", "seriesId", "kind", "at",
            "window", "check", "status", "value", "threshold", "message", "specHash", "revision");

    public JavaTsxTopicSink(TsxTopicSink that) {
        super(that);
    }

    static Properties producerProperties(String bootstrap) {
        return KafScaleClients.producerProps(bootstrap);
    }

    public static ProducerRecord<String, byte[]> toRecord(OpenTsxAvro avro, String topic, Object r) {
        ProducerRecord<String, byte[]> out;
        if (r instanceof JsonNode signal) return signalRecord(topic, signal);
        if (r instanceof Episode e) {
            out = new ProducerRecord<>(topic, null, Math.floorDiv(Micros.of(e.getTStart()), 1_000L),
                    e.getSeries().getSeriesId(), avro.encode(canonical(e)));
        } else if (r instanceof SeriesStats s) {
            out = new ProducerRecord<>(topic, s.getSeriesId(), avro.encode(s));
        } else {
            throw new IllegalArgumentException("no key rule for "
                    + (r instanceof SpecificRecord record ? record.getSchema().getFullName() : r == null ? "null" : r.getClass().getName()));
        }
        out.headers().add(TopicLayout.HEADER_SCHEMA, ((SpecificRecord) r).getSchema().getFullName().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    private static String text(JsonNode signal, String field) {
        JsonNode value = signal.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("signal " + field + " must be a string");
        return value.textValue();
    }

    private static Instant time(JsonNode signal, String field) {
        try {
            return Instant.parse(text(signal, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("signal " + field + " must be an ISO timestamp", e);
        }
    }

    private static ProducerRecord<String, byte[]> signalRecord(String topic, JsonNode signal) {
        if (!signal.isObject()) throw new IllegalArgumentException("signal must be an object");
        signal.fieldNames().forEachRemaining(field -> {
            if (!SIGNAL_FIELDS.contains(field)) throw new IllegalArgumentException("unknown signal field " + field);
        });
        if (!text(signal, "schema").equals("opentsx.signal.v1")) throw new IllegalArgumentException("unsupported JSON signal schema");
        String id = text(signal, "id");
        String series = text(signal, "seriesId");
        if (!id.matches("[0-9a-f]{16}") || !series.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("signal id and seriesId must be 16-character lower-case hex");
        }
        if (!text(signal, "specHash").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("signal specHash must be 64-character lower-case hex");
        }
        String kind = text(signal, "kind");
        if (!Set.of("dq", "segment.begin", "segment.end", "threshold.enter", "threshold.exit").contains(kind)) {
            throw new IllegalArgumentException("unsupported signal kind " + kind);
        }
        time(signal, "at");
        String identity = text(signal, "job") + "|" + text(signal, "site") + "|" + series + "|" + kind + "|" + text(signal, "at");
        if (signal.has("window")) {
            JsonNode window = signal.get("window");
            if (!window.isObject() || window.size() != 2 || !window.has("from") || !window.has("to")) {
                throw new IllegalArgumentException("signal window needs only from and to");
            }
            if (time(window, "to").isBefore(time(window, "from"))) throw new IllegalArgumentException("signal window ends before it starts");
        }
        if (kind.equals("dq")) {
            if (!signal.has("window")) throw new IllegalArgumentException("DQ signal needs a window");
            identity += "|" + text(signal, "check") + "|" + text(signal.get("window"), "from") + "|" + text(signal.get("window"), "to");
        }
        if (signal.has("check") && !Set.of("COMPLETENESS", "PLAUSIBLE_RANGE", "FLATLINE", "GAPS", "FRESHNESS").contains(text(signal, "check"))) {
            throw new IllegalArgumentException("unsupported signal check");
        }
        if (signal.has("status") && !Set.of("SUCCESS", "FAILED", "ABORTED").contains(text(signal, "status"))) {
            throw new IllegalArgumentException("unsupported signal status");
        }
        if (signal.has("value") && !signal.get("value").isNull()
                && (!signal.get("value").isNumber() || !Double.isFinite(signal.get("value").doubleValue()))) {
            throw new IllegalArgumentException("signal value must be finite or null");
        }
        for (String field : List.of("threshold", "message")) if (signal.has(field)) text(signal, field);
        if (signal.has("revision") && (!signal.get("revision").isIntegralNumber() || signal.get("revision").bigIntegerValue().signum() < 0)) {
            throw new IllegalArgumentException("signal revision must be a non-negative integer");
        }
        if (!id.equals(XxHash64.hex(XxHash64.hash(identity.getBytes(StandardCharsets.UTF_8))))) {
            throw new IllegalArgumentException("signal id does not match its logical identity");
        }
        try {
            ObjectNode canonical = JSON.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            signal.fields().forEachRemaining(field -> fields.put(field.getKey(), field.getValue()));
            fields.forEach((field, value) -> {
                if (field.equals("window")) {
                    canonical.putObject(field).put("from", text(value, "from")).put("to", text(value, "to"));
                } else canonical.set(field, value);
            });
            ProducerRecord<String, byte[]> out = new ProducerRecord<>(topic, series, JSON.writeValueAsBytes(canonical));
            out.headers().add(TopicLayout.HEADER_SCHEMA, "opentsx.signal.v1".getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new java.io.UncheckedIOException("cannot encode signal", e);
        }
    }

    /**
     * The episode with its maps in UTF-8 key order, as the model specification requires for encoding
     * (docs/datamodel-2026/04-MODELL-V2-SPEC.md, section 5). Avro decodes maps into hash maps, so an episode that was
     * read and is written again would otherwise change its bytes.
     */
    static Episode canonical(Episode e) {
        Episode c = Episode.newBuilder(e).build();
        c.getSeries().setTags(SeriesKeys.sortedByUtf8(c.getSeries().getTags()));
        c.setLabels(SeriesKeys.sortedByUtf8(c.getLabels()));
        if (c.getSegmentation() != null) {
            c.getSegmentation().setParams(SeriesKeys.sortedByUtf8(c.getSegmentation().getParams()));
        }
        return c;
    }

    static void write(Producer<String, byte[]> producer, String topic, Iterator<?> input)
            throws InterruptedException, ExecutionException {
        OpenTsxAvro avro = new OpenTsxAvro();
        List<Future<RecordMetadata>> sent = new ArrayList<>();
        if (input.hasNext()) {
            Object first = input.next();
            if (first instanceof JsonNode) {
                List<ProducerRecord<String, byte[]>> signals = new ArrayList<>();
                signals.add(toRecord(avro, topic, first));
                while (input.hasNext()) {
                    Object record = input.next();
                    if (!(record instanceof JsonNode)) throw new IllegalArgumentException("a JSON signal batch cannot contain Avro records");
                    signals.add(toRecord(avro, topic, record));
                }
                signals.forEach(signal -> sent.add(producer.send(signal)));
            } else {
                sent.add(producer.send(toRecord(avro, topic, first)));
                while (input.hasNext()) {
                    Object record = input.next();
                    if (record instanceof JsonNode) throw new IllegalArgumentException("an Avro batch cannot contain JSON signals");
                    sent.add(producer.send(toRecord(avro, topic, record)));
                }
            }
        }
        producer.flush();
        for (Future<RecordMetadata> future : sent) future.get();
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs, ChannelInstance[] outputs, JavaExecutor executor,
            OptimizationContext.OperatorContext operatorContext) {
        try (KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap())) {
            write(producer, topic(), ((JavaChannelInstance) inputs[0]).provideStream().iterator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing " + topic(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("the broker did not acknowledge a record of " + topic(), e.getCause());
        }
        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.tsx.topicsink.load";
    }

    @Override
    protected ExecutionOperator createCopy() {
        return new JavaTsxTopicSink(this);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return List.of(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        throw new UnsupportedOperationException(this + " has no outputs");
    }
}
