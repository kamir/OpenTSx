package org.opentsx.windpark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentsx.model.Micros;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.Episode;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.WriteResult;
import org.opentsx.store.iceberg.IcebergSeriesStore;
import org.opentsx.windpark.analysis.PowerCurveAnalysis;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.TurbineType;
import org.opentsx.windpark.sim.GroundTruth;
import org.opentsx.windpark.sim.Scenario;
import org.opentsx.windpark.sim.WindparkSimulator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class WindparkDemo {

    private WindparkDemo() {
    }

    public record Report(int schemaVersion, String scenario, long seed, long fromMicros, long toMicros,
                         int episodes, long values, Map<String, String> episodeSha256,
                         List<GroundTruth> groundTruth, Map<String, PowerCurveAnalysis.Result> analysis) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !(args[0].equals("run") || args[0].equals("replay"))
                || !args[1].equals("--output") || args[2].isBlank()) {
            throw new IllegalArgumentException("usage: WindparkDemo run|replay --output <directory>");
        }
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (args[0].equals("run")) {
            run(output);
        } else {
            replay(output);
        }
    }

    private static Scenario scenario() throws IOException {
        try (InputStream input = WindparkDemo.class.getResourceAsStream("/windpark/replay-demo.yaml")) {
            if (input == null) {
                throw new IOException("missing bundled windpark/replay-demo.yaml");
            }
            return Scenario.load(input);
        }
    }

    private static Map<String, String> storeProperties(Path output) {
        return Map.of("catalog-impl", "org.apache.iceberg.jdbc.JdbcCatalog",
                "uri", "jdbc:sqlite:" + output.resolve("catalog.db"),
                "jdbc.schema-version", "V1", "warehouse", output.resolve("warehouse").toUri().toString());
    }

    private static void run(Path output) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("output already exists: " + output);
        }
        Scenario scenario = scenario();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        List<Episode> episodes = new ArrayList<>();
        new WindparkSimulator(scenario, ChannelCatalog.standard()).run(episodes::add);
        byte[] original = bytes(report(scenario, episodes));
        try (IcebergSeriesStore store = IcebergSeriesStore.open(storeProperties(output), "windpark_demo")) {
            WriteResult first = store.write(episodes);
            WriteResult repeated = store.write(episodes);
            if (!first.equals(new WriteResult(episodes.size(), 0, 0))
                    || !repeated.equals(new WriteResult(0, episodes.size(), 0))) {
                throw new IOException("store did not preserve idempotent episode writes");
            }
        }
        byte[] stored = read(output, scenario);
        if (!Arrays.equals(original, stored)) {
            throw new IOException("persisted episodes do not match simulation");
        }
        Files.write(output.resolve("report.json"), original, StandardOpenOption.CREATE_NEW);
        System.out.println("stored and verified " + episodes.size() + " episodes: " + output.resolve("report.json"));
    }

    private static void replay(Path output) throws IOException {
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(output.resolve("report.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing demo report.json");
        }
        byte[] original = Files.readAllBytes(output.resolve("report.json"));
        byte[] replayed = read(output, scenario());
        if (!Arrays.equals(original, replayed)) {
            throw new IOException("replay does not match report.json");
        }
        Path receipt = output.resolve("replay.json");
        if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(replayed, Files.readAllBytes(receipt))) {
                throw new IOException("existing replay.json does not match verified replay");
            }
        } else {
            Files.write(receipt, replayed, StandardOpenOption.CREATE_NEW);
        }
        System.out.println("replay verified: " + receipt);
    }

    private static byte[] read(Path output, Scenario scenario) throws IOException {
        if (!Files.isRegularFile(output.resolve("catalog.db"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(output.resolve("warehouse"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing demo catalog.db or warehouse");
        }
        try (IcebergSeriesStore store = IcebergSeriesStore.openExisting(storeProperties(output), "windpark_demo")) {
            List<Episode> episodes = store.read(EpisodeQuery.of(SeriesSelector.all(), Long.MIN_VALUE, Long.MAX_VALUE));
            return bytes(report(scenario, episodes));
        } catch (RuntimeException failure) {
            throw new IOException("cannot read persisted demo episodes", failure);
        }
    }

    private static Report report(Scenario scenario, List<Episode> episodes) {
        OpenTsxAvro codec = new OpenTsxAvro();
        MessageDigest digest = sha256();
        Map<String, String> hashes = new TreeMap<>();
        long values = 0;
        for (Episode episode : episodes) {
            String hash = HexFormat.of().formatHex(digest.digest(codec.encode(episode)));
            if (hashes.put(episode.getEpisodeId(), hash) != null) {
                throw new IllegalStateException("duplicate episode id in demo");
            }
            values += episode.getCount();
        }
        WindparkSimulator simulator = new WindparkSimulator(scenario, ChannelCatalog.standard());
        Map<String, PowerCurveAnalysis.Result> analysis = new TreeMap<>();
        for (var turbine : simulator.park().turbines()) {
            List<Episode> wind = select(episodes, turbine.id(), "wind.met.wind_speed");
            List<Episode> power = select(episodes, turbine.id(), "wind.turbine.power_active");
            analysis.put(turbine.id(), PowerCurveAnalysis.analyze(TurbineType.byModel(scenario.park().turbineModel()),
                    wind, power, PowerCurveAnalysis.Settings.defaults()));
        }
        long start = Micros.of(scenario.start());
        return new Report(1, scenario.name(), scenario.seed(), start, start + scenario.duration().toNanos() / 1000,
                episodes.size(), values, hashes, simulator.groundTruth(), analysis);
    }

    private static List<Episode> select(List<Episode> episodes, String turbine, String metric) {
        return episodes.stream().filter(e -> e.getSeries().getMetric().equals(metric)
                && turbine.equals(e.getSeries().getTags().get("turbine"))).toList();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static byte[] bytes(Report report) throws IOException {
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
    }
}
