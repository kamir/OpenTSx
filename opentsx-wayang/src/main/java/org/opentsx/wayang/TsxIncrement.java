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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * One increment of a continuous plan on Wayang (S5.1 of docs/datamodel-2026/20-WAYANG-TS-OPERATORS-SPEC.md): read the
 * end offsets of the input, run the plan over {@code [checkpoint, end)} with the operator state of the checkpoint, and
 * write the new checkpoint only after the plan, and with it every sink, has finished.
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

    /** Builds the rest of the plan on the source and returns its sink. Stateful operators keep their state in {@code state}. */
    @FunctionalInterface
    public interface Plan {
        Operator build(TsxTopicSource source, TsxState state);
    }

    private TsxIncrement() {
    }

    /**
     * @throws TsxIncrementException when the plan fails; the checkpoint is then left as it was
     */
    public static Result run(Config c, Plan plan) {
        if (!c.start().equals("earliest") && !c.start().equals("latest")) {
            throw new IllegalArgumentException("start must be earliest or latest, not " + c.start());
        }
        Optional<TsxCheckpoint> saved = TsxCheckpoint.load(c.checkpoint(), c.specHash());
        Map<Integer, Long> from = saved.map(TsxCheckpoint::next).orElse(Map.of());
        TsxState state = new TsxState(saved.map(TsxCheckpoint::state).orElse(Map.of()));
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
            new WayangContext(new Configuration()).with(new TsxJavaPlugin())
                    .execute(new WayangPlan(plan.build(source, state)));
        } catch (RuntimeException e) {
            throw new TsxIncrementException(rootMessage(e), e);
        }
        Map<Integer, Long> next = new HashMap<>();
        ranges.forEach((p, r) -> next.put(p, r[1]));
        new TsxCheckpoint(c.job(), c.planVersion(), c.specHash(), c.inputTopic(), next, state.snapshot())
                .write(c.checkpoint());
        return new Result(ranges, records);
    }

    /**
     * Runs increments until {@code stop} says so, one every {@code interval}, and reports each in {@code status}:
     * {@code running} with the records read so far, or {@code failed} with the error. A failed increment ends the loop.
     *
     * @return the records read by all increments
     */
    public static long loop(Config c, Plan plan, TsxStatus status, Duration interval, BooleanSupplier stop) {
        long records = 0;
        status.report(c, "running", records, null);
        while (!stop.getAsBoolean()) {
            long started = System.nanoTime();
            try {
                records += run(c, plan).records();
            } catch (TsxIncrementException e) {
                status.report(c, "failed", records, e.getMessage());
                throw e;
            }
            status.report(c, "running", records, null);
            long rest = interval.toNanos() - (System.nanoTime() - started);
            if (rest > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(rest).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        status.report(c, "stopped", records, null);
        return records;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
