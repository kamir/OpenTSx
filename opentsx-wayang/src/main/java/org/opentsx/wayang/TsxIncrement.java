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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
        return run(c, plan, () -> { }, state -> { }, new Object());
    }

    private static Result run(Config c, Plan plan, Runnable beforeCheckpoint,
                              Consumer<TsxState> liveState, Object checkpointLock) {
        if (!c.start().equals("earliest") && !c.start().equals("latest")) {
            throw new IllegalArgumentException("start must be earliest or latest, not " + c.start());
        }
        Optional<TsxCheckpoint> saved = TsxCheckpoint.load(c.checkpoint(), c.specHash());
        Map<Integer, Long> from = saved.map(TsxCheckpoint::next).orElse(Map.of());
        TsxState state = new TsxState(saved.map(TsxCheckpoint::state).orElse(Map.of()));
        liveState.accept(state);
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
        synchronized (checkpointLock) {
            beforeCheckpoint.run();
            new TsxCheckpoint(c.job(), c.planVersion(), c.specHash(), c.inputTopic(), next, state.snapshot())
                    .write(c.checkpoint());
        }
        return new Result(ranges, records);
    }

    /**
     * Runs increments until {@code stop} says so, one every {@code interval}, and reports each in {@code status}:
     * {@code running} with the records read so far, or {@code failed} with the error. A failed increment ends the loop.
     *
     * @return the records read by all increments
     */
    public static long loop(Config c, Plan plan, TsxStatus status, Duration interval, BooleanSupplier stop) {
        AtomicReference<TsxState> live = new AtomicReference<>();
        return loop(c, check -> run(c, plan, check, state -> {
            TsxState previous = live.get();
            if (previous != null) state.inheritProgress(previous);
            live.set(state);
        }, status), status, interval, stop, System::nanoTime,
                duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000),
                () -> live.get() == null ? new TsxState.Progress(Long.MIN_VALUE, List.of(), 0) : live.get().progress());
    }

    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    static long loop(Config c, Function<Runnable, Result> increment, TsxStatus status, Duration interval,
                     BooleanSupplier stop, LongSupplier nanoTime, Pause pause) {
        return loop(c, increment, status, interval, stop, nanoTime, pause,
                () -> new TsxState.Progress(Long.MIN_VALUE, List.of(), 0));
    }

    static long loop(Config c, Function<Runnable, Result> increment, TsxStatus status, Duration interval,
                     BooleanSupplier stop, LongSupplier nanoTime, Pause pause, Supplier<TsxState.Progress> progress) {
        AtomicLong records = new AtomicLong();
        AtomicReference<RuntimeException> reportingFailure = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = null;
        Runnable check = () -> {
            RuntimeException failure = reportingFailure.get();
            if (failure != null) throw failure;
        };
        try {
            status.report(c, "running", records.get(), null, progress.get());
            if (interval.isNegative()) throw new IllegalArgumentException("increment interval must not be negative");
            heartbeat = status.heartbeat(() -> {
                synchronized (status) {
                    if (finished.get() || reportingFailure.get() != null) return;
                    try {
                        status.report(c, "running", records.get(), null, progress.get());
                    } catch (RuntimeException e) {
                        reportingFailure.compareAndSet(null, e);
                    }
                }
            });
            while (!stop.getAsBoolean()) {
                check.run();
                long started = nanoTime.getAsLong();
                Result result = increment.apply(check);
                if (result.records() < 0) throw new IllegalStateException("an increment reported a negative record count");
                records.updateAndGet(previous -> Math.addExact(previous, result.records()));
                synchronized (status) {
                    check.run();
                    status.report(c, "running", records.get(), null, progress.get());
                }
                long rest = interval.toNanos() - (nanoTime.getAsLong() - started);
                if (rest > 0) {
                    try {
                        pause.sleep(Duration.ofNanos(rest));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            synchronized (status) {
                check.run();
                finished.set(true);
                status.report(c, "stopped", records.get(), null, progress.get());
            }
            return records.get();
        } catch (RuntimeException e) {
            RuntimeException previous = reportingFailure.get();
            if (previous != null && previous != e) e.addSuppressed(previous);
            synchronized (status) {
                finished.set(true);
                try {
                    status.report(c, "failed", records.get(), rootMessage(e), progress.get());
                } catch (RuntimeException reporting) {
                    if (reporting != e) e.addSuppressed(reporting);
                }
            }
            throw e;
        } finally {
            synchronized (status) {
                finished.set(true);
                if (heartbeat != null) heartbeat.cancel(false);
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getName() : root.getMessage();
    }
}
