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
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flink job for the streaming statistics of {@code opentsx-stats}: reads {@code tsx.<domain>.episodes.v2} (and
 * optionally the observations topic), runs a {@code StatsEngine} per series and writes {@code SeriesStats} to
 * {@code tsx.<domain>.series-stats.v2}. The KafScale processor {@code StatsProcessorApp} writes the same records.
 *
 * <p>The command {@code stats-flink} reads its keys through {@link OpenTsxConf#load}; {@code bin/opentsx stats-flink
 * --help} lists them. Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error.
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

    private static final Logger LOG = LoggerFactory.getLogger(StatsJob.class);
    private static final int RESTARTS = 20;
    private static final Duration RESTART_DELAY = Duration.ofSeconds(15);
    static final String COMMAND = "stats-flink";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    private StatsJob() {
    }

    public static void main(String[] args) {
        int code = run(List.of(args), System.getenv(), System.out, System.err);
        // In a Flink application cluster main runs in the JobManager; System.exit(0) would end it with the job.
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        Options o;
        try {
            OpenTsxConf.Loaded loaded = load(args, env);
            if (loaded.help() || loaded.version()) {
                out.print(loaded.help() ? Registry.shipped().help(COMMAND) : ConfCli.versionLine());
                out.flush();
                if (out.checkError()) {
                    err.print("opentsx: " + WRITE_FAILED + "\n");
                    err.flush();
                    return 1;
                }
                return 0;
            }
            o = start(loaded);
        } catch (IllegalArgumentException e) {
            // OpenTsxConfException, and a domain or engine setting that TopicLayout or StatsEngine refuses
            err.print("opentsx: " + e.getMessage() + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        }
        try {
            execute(o);
            return 0;
        } catch (Exception e) {
            LOG.debug("stats-flink failed", e);
            err.print("opentsx: " + causes(e) + "\n");
            err.flush();
            return 1;
        }
    }

    static Options start(OpenTsxConf.Loaded loaded) {
        Options o = options(loaded.conf());
        LOG.info(loaded.startLine());
        return o;
    }

    /** Builds and runs the job in the environment of the caller (a local mini cluster outside a Flink cluster). */
    public static void execute(Options o) throws Exception {
        Configuration conf = new Configuration();
        // KafScale refuses reads for about a minute at a time; a failed task restarts from the last checkpoint
        conf.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, RESTARTS);
        conf.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, RESTART_DELAY);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.enableCheckpointing(o.checkpointEvery().toMillis());
        DataStream<StatsInput> inputs = env.fromSource(source(o), WatermarkStrategy.noWatermarks(), "series inputs")
                .uid("series-inputs");
        statistics(inputs, o.spec(), o.globalEvery()).sinkTo(sink(o)).name("series-stats").uid("series-stats");
        LOG.info("submitting the statistics job domain={} topics={} sink={} bootstrap={} once={} checkpointEvery={} restarts={}"
                        + " restartDelay={}", o.layout().domain(), String.join(",", topics(o)), o.layout().seriesStats(),
                o.bootstrap(), o.once(), o.checkpointEvery(), RESTARTS, RESTART_DELAY);
        env.execute("opentsx statistics " + o.layout().domain());
    }

    /** The statistics part of the job, between the inputs and the encoded records; tests drive it with bounded lists. */
    public static DataStream<StatsRecord> statistics(DataStream<StatsInput> inputs, EngineSpec spec, Duration globalEvery) {
        return inputs.keyBy(StatsInput::getSeriesId, Types.STRING)
                .process(new StatsFunction(spec, globalEvery.toMillis()))
                .name("statistics").uid("statistics");
    }

    private static List<String> topics(Options o) {
        TopicLayout layout = o.layout();
        return o.observations() ? List.of(layout.episodes(), layout.observations()) : List.of(layout.episodes());
    }

    static KafkaSource<StatsInput> source(Options o) {
        TopicLayout layout = o.layout();
        List<String> topics = topics(o);
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

    /** {@code --checkpoint} belongs to {@code stats}; the Flink job refuses it with the reason. */
    static OpenTsxConf.Loaded load(List<String> args, Map<String, String> env) {
        List<String> words = new ArrayList<>(args.size() + 1);
        words.add(COMMAND);
        words.addAll(args);
        try {
            return OpenTsxConf.load(words, env, env.get("OPENTSX_CONF_DIR"));
        } catch (OpenTsxConfException e) {
            int dashes = args.indexOf("--");
            if ((dashes < 0 ? args : args.subList(0, dashes)).contains("--checkpoint")) {
                OpenTsxConfException checkpoint = new OpenTsxConfException("--checkpoint", "an option of stats, not of stats-flink",
                        "the Flink job keeps its positions and state in Flink checkpoints (--checkpoint-every)");
                checkpoint.initCause(e);
                throw checkpoint;
            }
            throw e;
        }
    }

    static Options options(OpenTsxConf conf) {
        Duration checkpointEvery = conf.getDuration("opentsx.stats.checkpointInterval");
        if (checkpointEvery.isZero()) {
            throw new OpenTsxConfException("opentsx.stats.checkpointInterval", "\"" + conf.get("opentsx.stats.checkpointInterval")
                    + "\" is not allowed", "expected a positive duration such as PT30S");
        }
        EngineSpec spec = new EngineSpec(conf.getDurationList("opentsx.stats.windows"),
                conf.getDuration("opentsx.stats.allowedLateness"), conf.getDouble("opentsx.stats.sketchAlpha"));
        return new Options(conf.get("opentsx.kafscale.bootstrap"), new TopicLayout(conf.get("opentsx.domain")), spec,
                conf.getBoolean("opentsx.stats.observations"), conf.getBoolean("opentsx.stats.once"),
                conf.getDuration("opentsx.stats.globalInterval"), checkpointEvery);
    }

    /** The message and its causes on one line. */
    static String causes(Throwable t) {
        StringBuilder s = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return s.toString();
    }
}
