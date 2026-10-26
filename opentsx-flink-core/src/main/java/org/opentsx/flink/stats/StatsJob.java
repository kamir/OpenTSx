package org.opentsx.flink.stats;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceBuilder;
import org.apache.flink.connector.kafka.source.KafkaSourceOptions;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink job for the streaming statistics of {@code opentsx-stats}: reads {@code tsx.<domain>.episodes.v2} (and
 * optionally the observations topic), runs a {@code StatsEngine} per series and writes {@code SeriesStats} to
 * {@code tsx.<domain>.series-stats.v2}. The KafScale processor {@code StatsProcessorApp} writes the same records.
 *
 * <pre>
 * StatsJob --kafscale localhost:9092 --domain wind
 *          [--windows PT10M,PT1H] [--lateness PT0S] [--alpha 0.01] [--observations true]
 *          [--global-every PT30S] [--checkpoint-every PT30S]
 *          [--once true]   (process the backlog, close all windows, end)
 * </pre>
 *
 * <p>Built for what KafScale supports: the source commits no offsets (its positions are in Flink checkpoints),
 * the sink writes at least once without idempotence. Readers keep the latest record per
 * (seriesId, windowType, windowStart), as for the processor. Stop a running job without {@code --drain}: draining
 * sends the final watermark, which closes all open windows.
 */
public final class StatsJob {

    /** Job options; {@code --checkpoint} of the processor has no counterpart, Flink checkpoints replace the file. */
    public record Options(String bootstrap, TopicLayout layout, EngineSpec spec, boolean observations, boolean once,
                          Duration globalEvery, Duration checkpointEvery) {
    }

    private StatsJob() {
    }

    public static void main(String[] args) throws Exception {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: StatsJob --kafscale <bootstrap> --domain <d> [--windows PT10M,PT1H] [--lateness PT0S]"
                    + " [--alpha 0.01] [--observations true] [--global-every PT30S] [--checkpoint-every PT30S] [--once true]");
            System.exit(2);
            return;
        }
        execute(o);
    }

    /** Builds and runs the job in the environment of the caller (a local mini cluster outside a Flink cluster). */
    public static void execute(Options o) throws Exception {
        Configuration conf = new Configuration();
        // KafScale refuses reads for about a minute at a time; a failed task restarts from the last checkpoint
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 20);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofSeconds(15));
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.enableCheckpointing(o.checkpointEvery().toMillis());
        DataStream<StatsInput> inputs = env.fromSource(source(o), WatermarkStrategy.noWatermarks(), "series inputs")
                .uid("series-inputs");
        statistics(inputs, o.spec(), o.globalEvery()).sinkTo(sink(o)).name("series-stats").uid("series-stats");
        env.execute("opentsx statistics " + o.layout().domain());
    }

    /** The statistics part of the job, between the inputs and the encoded records; tests drive it with bounded lists. */
    public static DataStream<StatsRecord> statistics(DataStream<StatsInput> inputs, EngineSpec spec, Duration globalEvery) {
        return inputs.keyBy(StatsInput::getSeriesId, Types.STRING)
                .process(new StatsFunction(spec, globalEvery.toMillis()))
                .name("statistics").uid("statistics");
    }

    static KafkaSource<StatsInput> source(Options o) {
        TopicLayout layout = o.layout();
        List<String> topics = o.observations() ? List.of(layout.episodes(), layout.observations()) : List.of(layout.episodes());
        KafkaSourceBuilder<StatsInput> b = KafkaSource.<StatsInput>builder()
                .setBootstrapServers(o.bootstrap())
                .setProperties(KafScaleClients.consumerProps(o.bootstrap()))
                // KafScale rejects commits without group membership; the positions live in Flink checkpoints
                .setProperty(KafkaSourceOptions.COMMIT_OFFSETS_ON_CHECKPOINT.key(), "false")
                .setTopics(topics)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setDeserializer(new StatsInputDeserializer(layout.episodes()));
        if (o.once()) {
            b.setBounded(OffsetsInitializer.latest());
        }
        return b.build();
    }

    static KafkaSink<StatsRecord> sink(Options o) {
        return KafkaSink.<StatsRecord>builder()
                .setBootstrapServers(o.bootstrap())
                .setKafkaProducerConfig(KafScaleClients.producerProps(o.bootstrap()))
                .setRecordSerializer(new StatsRecordSerializer(o.layout().seriesStats()))
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }

    static Options parse(String[] args) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --option value pairs, got " + args[i]);
            }
            if (a.put(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("option given twice: " + args[i]);
            }
        }
        if (a.containsKey("checkpoint")) {
            throw new IllegalArgumentException("--checkpoint is an option of StatsProcessorApp; the Flink job keeps its"
                    + " positions and state in Flink checkpoints (--checkpoint-every)");
        }
        List<String> known = List.of("kafscale", "domain", "windows", "lateness", "alpha", "observations", "once",
                "global-every", "checkpoint-every");
        for (String k : a.keySet()) {
            if (!known.contains(k)) {
                throw new IllegalArgumentException("unknown option --" + k);
            }
        }
        String bootstrap = a.get("kafscale");
        if (bootstrap == null || bootstrap.isBlank()) {
            throw new IllegalArgumentException("--kafscale <bootstrap> is required");
        }
        EngineSpec spec = new EngineSpec(
                Arrays.stream(a.getOrDefault("windows", "PT10M,PT1H").split(",")).map(Duration::parse).toList(),
                Duration.parse(a.getOrDefault("lateness", "PT0S")),
                Double.parseDouble(a.getOrDefault("alpha", "0.01")));
        Duration globalEvery = Duration.parse(a.getOrDefault("global-every", "PT30S"));
        Duration checkpointEvery = Duration.parse(a.getOrDefault("checkpoint-every", "PT30S"));
        if (globalEvery.isNegative() || checkpointEvery.isNegative() || checkpointEvery.isZero()) {
            throw new IllegalArgumentException("--global-every must not be negative and --checkpoint-every must be positive");
        }
        return new Options(bootstrap, new TopicLayout(a.getOrDefault("domain", "wind")), spec,
                Boolean.parseBoolean(a.getOrDefault("observations", "false")),
                Boolean.parseBoolean(a.getOrDefault("once", "false")), globalEvery, checkpointEvery);
    }
}
