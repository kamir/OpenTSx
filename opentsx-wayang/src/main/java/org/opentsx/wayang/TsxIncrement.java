package org.opentsx.wayang;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.opentsx.kafka.KafScaleClients;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * One increment of a continuous plan on Wayang (S5.1 of docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md): read the
 * end offsets of the input, run the plan over {@code [checkpoint, end)}, and write the new checkpoint only after the
 * plan, and with it every sink, has finished.
 */
public final class TsxIncrement {

    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(30);

    /**
     * @param start where to begin without a checkpoint: {@code earliest} or {@code latest}
     */
    public record Config(String bootstrap, String job, int planVersion, String specHash, String inputTopic,
                         Set<String> fingerprints, String start, Path checkpoint) {
    }

    /** @param ranges partition to {@code {from, toExclusive}} of this increment */
    public record Result(Map<Integer, long[]> ranges, long records) {
    }

    private TsxIncrement() {
    }

    /**
     * @param plan builds the rest of the plan on the source and returns its sink
     * @throws TsxIncrementException when the plan fails; the checkpoint is then left as it was
     */
    public static Result run(Config c, Function<TsxTopicSource, Operator> plan) {
        if (!c.start().equals("earliest") && !c.start().equals("latest")) {
            throw new IllegalArgumentException("start must be earliest or latest, not " + c.start());
        }
        Map<Integer, Long> from = TsxCheckpoint.load(c.checkpoint(), c.specHash()).map(TsxCheckpoint::next).orElse(Map.of());
        Map<Integer, long[]> ranges = new TreeMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = KafScaleClients.consumer(c.bootstrap())) {
            List<PartitionInfo> infos = consumer.partitionsFor(c.inputTopic(), METADATA_TIMEOUT);
            if (infos == null || infos.isEmpty()) {
                throw new IllegalStateException("topic " + c.inputTopic() + " has no partitions");
            }
            List<TopicPartition> tps = infos.stream().map(i -> new TopicPartition(c.inputTopic(), i.partition())).toList();
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps, METADATA_TIMEOUT);
            Map<TopicPartition, Long> end = consumer.endOffsets(tps, METADATA_TIMEOUT);
            for (TopicPartition tp : tps) {
                long first = from.containsKey(tp.partition()) ? Math.max(from.get(tp.partition()), begin.get(tp))
                        : c.start().equals("earliest") ? begin.get(tp) : end.get(tp);
                ranges.put(tp.partition(), new long[]{first, end.get(tp)});
            }
        }
        long records = ranges.values().stream().mapToLong(r -> r[1] - r[0]).sum();
        TsxTopicSource source = new TsxTopicSource(
                new TsxRead(c.bootstrap(), c.inputTopic(), ranges, c.fingerprints(), c.job(), c.planVersion()));
        try {
            new WayangContext(new Configuration()).with(new TsxJavaPlugin()).execute(new WayangPlan(plan.apply(source)));
        } catch (RuntimeException e) {
            throw new TsxIncrementException(rootMessage(e), e);
        }
        Map<Integer, Long> next = new HashMap<>();
        ranges.forEach((p, r) -> next.put(p, r[1]));
        new TsxCheckpoint(c.job(), c.planVersion(), c.specHash(), c.inputTopic(), next).write(c.checkpoint());
        return new Result(ranges, records);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
