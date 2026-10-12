package org.opentsx.windpark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.opentsx.kafka.BucketWriter;
import org.opentsx.kafka.EpisodeIndex;
import org.opentsx.kafka.FileEpisodeIndex;
import org.opentsx.kafka.InMemoryEpisodeIndex;
import org.opentsx.kafka.KafScaleClients;
import org.opentsx.kafka.TopicLayout;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.iceberg.IcebergSeriesStore;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.SimulationReport;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs a wind-farm scenario and writes the episodes to KafScale (as one sealed bucket) and/or Iceberg.
 *
 * <pre>
 * WindparkSim --scenario scenarios/ostsee-demo.yaml
 *             [--kafscale localhost:9092 --domain wind --index wind-index.tsv]
 *             [--iceberg type=rest,uri=http://localhost:8181,warehouse=s3://lake/ --namespace tsx]
 *             [--catalog my-catalog.yaml] [--ground-truth ground-truth.json]
 * </pre>
 */
public final class WindparkSim {

    private WindparkSim() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        if (!a.containsKey("scenario")) {
            System.err.println("usage: WindparkSim --scenario <file.yaml> [--kafscale <bootstrap> --domain <d> --index <file>] "
                    + "[--iceberg k=v,... --namespace tsx] [--catalog <file.yaml>] [--ground-truth <file.json>]");
            System.exit(2);
        }
        SimulationReport report = run(a);
        System.out.println(summary(report));
    }

    static SimulationReport run(Map<String, String> a) throws IOException {
        Scenario scenario = Scenario.load(Path.of(a.get("scenario")));
        ChannelCatalog catalog = a.containsKey("catalog") ? ChannelCatalog.load(Path.of(a.get("catalog"))) : ChannelCatalog.standard();
        WindparkSimulator sim = new WindparkSimulator(scenario, catalog);

        List<AutoCloseable> closeables = new ArrayList<>();
        List<Consumer<Episode>> sinks = new ArrayList<>();
        BucketWriter[] bucket = new BucketWriter[1];
        try {
            if (a.containsKey("kafscale")) {
                String bootstrap = a.get("kafscale");
                TopicLayout layout = new TopicLayout(a.getOrDefault("domain", "wind"));
                try (Admin admin = KafScaleClients.admin(bootstrap)) {
                    layout.provision(admin, Integer.parseInt(a.getOrDefault("partitions", "6")), (short) 1);
                }
                KafkaProducer<String, byte[]> producer = KafScaleClients.producer(bootstrap);
                closeables.add(producer);
                EpisodeIndex index = a.containsKey("index") ? new FileEpisodeIndex(Path.of(a.get("index"))) : new InMemoryEpisodeIndex();
                if (index instanceof AutoCloseable c) {
                    closeables.add(c);
                }
                Map<String, String> descriptor = new LinkedHashMap<>();
                descriptor.put("scenario", scenario.name());
                descriptor.put("seed", Long.toString(scenario.seed()));
                descriptor.put("park", scenario.park().id());
                descriptor.put("catalog", catalog.name() + "@" + catalog.version());
                descriptor.put("start", scenario.start().toString());
                descriptor.put("duration", scenario.duration().toString());
                bucket[0] = BucketWriter.open(producer, layout, index, scenario.name() + "/" + scenario.start(), descriptor);
                sinks.add(bucket[0]::append);
            }
            if (a.containsKey("iceberg")) {
                SeriesStore store = IcebergSeriesStore.open(props(a.get("iceberg")), a.getOrDefault("namespace", "tsx"));
                closeables.add(store);
                List<Episode> batch = new ArrayList<>();
                sinks.add(e -> {
                    batch.add(e);
                    if (batch.size() >= 2_000) {
                        store.write(batch);
                        batch.clear();
                    }
                });
                closeables.add(0, () -> {
                    if (!batch.isEmpty()) {
                        store.write(batch);
                    }
                });
            }
            SimulationReport report = sim.run(e -> sinks.forEach(s -> s.accept(e)));
            if (bucket[0] != null) {
                BucketManifest m = bucket[0].seal();
                System.out.println("bucket " + m.getBucketId() + " sealed: " + m.getEpisodeCount() + " episodes, checksum " + m.getChecksum());
            }
            if (a.containsKey("ground-truth")) {
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                        .writeValue(Files.newOutputStream(Path.of(a.get("ground-truth"))), report.groundTruth());
            }
            return report;
        } finally {
            for (AutoCloseable c : closeables) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    static String summary(SimulationReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("scenario %s: %,d episodes, %,d values, %d ground-truth events%n",
                r.scenario(), r.episodes(), r.values(), r.groundTruth().size()));
        r.energyKwh().forEach((t, e) -> sb.append(String.format("  %s  %8.1f kWh  mean wind %5.2f m/s  states %s%n",
                t, e, r.meanWindMs().get(t), r.stateSeconds().get(t))));
        return sb.toString();
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --name value pairs, got " + args[i]);
            }
            out.put(args[i].substring(2), args[++i]);
        }
        return out;
    }

    static Map<String, String> props(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : csv.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("expected key=value in " + csv);
            }
            out.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        return out;
    }
}
