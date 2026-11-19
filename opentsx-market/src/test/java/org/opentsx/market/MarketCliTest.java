package org.opentsx.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
import org.opentsx.market.archive.Manifest;
import org.opentsx.market.source.Fetcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.opentsx.market.ParsersTest.t;

/** {@code bin/opentsx market ...} through {@link MarketCli#run}: keys, exit codes, messages (no network). */
class MarketCliTest {

    static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    static final String SMARD_PRICE = "smard-4169-hour-1759096800000.json";
    static final MarketCli.Collectors NO_DOWNLOAD = (delay, credentials, proxy) -> {
        throw new AssertionError("this command must not download");
    };

    @TempDir
    Path dir;

    record Result(int code, String out, String err) {
    }

    static Result cli(MarketCli.Collectors collectors, Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = MarketCli.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), NOW, collectors);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static Result cli(String... args) {
        return cli(NO_DOWNLOAD, Map.of(), args);
    }

    /** Collectors whose every source fails the way {@link org.opentsx.market.source.Http} does when the network is down. */
    static MarketCli.Collectors down() {
        Fetcher down = (uri, h) -> {
            throw new UncheckedIOException("GET " + uri + " failed after 5 attempts", new IOException("connection refused"));
        };
        return (delay, credentials, proxy) -> new Collector(down, down, down, down, credentials);
    }

    private Path smardPrice() throws IOException {
        return Files.writeString(dir.resolve(SMARD_PRICE), Fixtures.text(SMARD_PRICE));
    }

    static List<Path> filesUnder(Path d) throws IOException {
        if (!Files.exists(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(d)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void aMisspelledOptionExitsWith2AndNamesTheNearestOption() {
        assertEquals(new Result(2, "", "opentsx: --form-year: unknown option of market backfill; did you mean --from-year?\n"),
                cli("backfill", "--archive", dir.toString(), "--form-year", "2020", "--to-year", "2018"));
        assertEquals(new Result(2, "", "opentsx: market backfil: unknown command; did you mean market backfill?\n"),
                cli("backfil", "--from-year", "2020"));
    }

    @Test
    void theHelpOfACommandComesFromTheRegistryAndImportNamesThePartnerOption() {
        Result r = cli("import", "--help");
        assertEquals(new Result(0, Registry.shipped().help("market import"), ""), r);
        assertTrue(r.out().contains("opentsx.market.import.partner  string = default  --partner"), r.out());
    }

    @Test
    void theHelpOfLoadAndPublishSaysThatFromDayIsAUtcDayAndFromOfTheFetchesABerlinDay() {
        for (String command : List.of("load", "publish")) {
            Result r = cli(command, "--help");
            assertEquals(0, r.code(), r.toString());
            assertTrue(r.out().contains("opentsx.market.fromDay  string  --from-day  OPENTSX_MARKET_FROM_DAY\n"
                    + "      First UTC day (YYYY-MM-DD) to load or publish."), r.out());
        }
        assertTrue(cli("prices", "--help").out().contains("opentsx.market.from  string  --from  OPENTSX_MARKET_FROM\n"
                + "      Start: a local date (Europe/Berlin, start of day) or an ISO instant."));
    }

    @Test
    void aBackfillWithAFailedYearExitsWith1() {
        Result r = cli(down(), Map.of(), "backfill", "--archive", dir.resolve("history").toString(), "--series",
                "smard/load", "--from-year", "2024", "--to-year", "2024");
        assertEquals(1, r.code(), r.toString());
        assertTrue(r.out().contains("smard/load                               2024  FAILED"), r.out());
        assertTrue(r.out().contains("1 failed"), r.out());
        assertEquals("opentsx: market backfill: 1 of 1 series-years failed; the lines marked FAILED on stdout say why\n",
                r.err());
    }

    @Test
    void aFetchWhoseHttpClientFailsExitsWith1() {
        Result r = cli(down(), Map.of(), "prices", "--from", "2025-10-01", "--to", "2025-10-02");
        assertEquals(new Result(1, "", "opentsx: GET https://www.smard.de/app/chart_data/4169/DE-LU/index_quarterhour.json "
                + "failed after 5 attempts; caused by java.io.IOException: connection refused\n"), r);
    }

    @Test
    void aFetchThatNeedsACredentialThatIsNotSetExitsWith2() {
        assertEquals(new Result(2, "", "opentsx: opentsx.market.entsoeToken: not set; set OPENTSX_MARKET_ENTSOE_TOKEN or "
                        + "ENTSOE_TOKEN or put opentsx.market.entsoeToken in a properties file, or parse a download with --offline\n"),
                cli("prices", "--source", "entsoe", "--from", "2025-10-01", "--to", "2025-10-02"));
        assertEquals(new Result(2, "", "opentsx: opentsx.market.ntpClientSecret: not set; set OPENTSX_MARKET_NTP_CLIENT_SECRET"
                        + " or NTP_CLIENT_SECRET or put opentsx.market.ntpClientSecret in a properties file, or parse a download "
                        + "with --offline\n"),
                cli(NO_DOWNLOAD, Map.of("NTP_CLIENT_ID", "client"), "rebap", "--from", "2024-01-01", "--to", "2024-02-01"));
    }

    @Test
    void aCredentialIsRefusedOnTheCommandLineWithEveryVariableOfItsKey() {
        assertEquals(new Result(2, "", "opentsx: opentsx.market.entsoeToken: secret keys are not accepted on the command line;"
                        + " set OPENTSX_MARKET_ENTSOE_TOKEN or ENTSOE_TOKEN or put opentsx.market.entsoeToken in a properties file\n"),
                cli("prices", "--source", "entsoe", "--from", "2025-10-01", "--to", "2025-10-02", "--conf",
                        "opentsx.market.entsoeToken=SECRET-TOKEN-123"));
    }

    @Test
    void requalityFalseLeavesTheManifestsAloneAndRequalityRewritesThem() throws IOException {
        Archive a = new Archive(dir.resolve("history"));
        SeriesDef p = MarketCatalog.require("smard/day_ahead_price");
        List<Point> price = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            price.add(new Point(t("2024-03-30T00:00:00Z") + i * Micro.HOUR, Micro.HOUR, 50 + i));
        }
        new Backfill(a, null, Map.of(), new PrintStream(new ByteArrayOutputStream())).store(p, 2024,
                new MarketSeries(p, price), List.of("fixture"), NOW, t("2024-03-30T01:00:00Z"));
        Path manifest = a.dir(p).resolve(Manifest.FILE);
        String before = Files.readString(manifest);
        Result r = cli("report", "--archive", a.root().toString(), "--requality", "false");
        assertEquals(0, r.code(), r.toString());
        assertEquals(before, Files.readString(manifest));
        assertEquals(0, cli("report", "--archive", a.root().toString(), "--requality").code());
        assertNotEquals(before, Files.readString(manifest));
    }

    @Test
    void aFetchStoresIntoTheArchiveOnlyWhenTheStoreSwitchSaysSo() throws IOException {
        String offline = smardPrice().toString();
        Path archive = dir.resolve("history");
        Result never = cli("prices", "--offline", offline, "--from", "2025-10-01", "--to", "2025-10-02", "--archive",
                archive.toString(), "--store", "never");
        assertEquals(0, never.code(), never.toString());
        assertEquals(List.of(), filesUnder(archive));

        Result environment = cli(NO_DOWNLOAD, Map.of("OPENTSX_ARCHIVE_DIR", archive.toString()), "prices", "--offline",
                offline, "--from", "2025-10-01", "--to", "2025-10-02");
        assertEquals(0, environment.code(), environment.toString());
        assertEquals(List.of(), filesUnder(archive));

        Result always = cli(NO_DOWNLOAD, Map.of("OPENTSX_ARCHIVE_DIR", archive.toString()), "prices", "--offline",
                offline, "--from", "2025-10-01", "--to", "2025-10-02", "--store", "always");
        assertEquals(0, always.code(), always.toString());
        assertTrue(always.out().contains("  archived smard/day_ahead_price 2025: "), always.out());
        assertTrue(Files.isRegularFile(archive.resolve("smard/day_ahead_price/manifest.json")));

        Path second = dir.resolve("second");
        assertEquals(0, cli("prices", "--offline", offline, "--from", "2025-10-01", "--to", "2025-10-02", "--archive",
                second.toString()).code());
        assertTrue(Files.isRegularFile(second.resolve("smard/day_ahead_price/manifest.json")));
    }

    @Test
    void importTakesOnlyTheOriginsReportedAndForecast() throws IOException {
        Path csv = Files.writeString(dir.resolve("p.csv"), "start,value\n2024-01-01T00:00:00+01:00,1.5\n");
        Path archive = dir.resolve("history");
        assertEquals(new Result(2, "", "opentsx: --origin: \"measured\" is not allowed; expected one of REPORTED, FORECAST\n"),
                cli("import", "--file", csv.toString(), "--name", "s", "--metric", "m", "--unit", "kW", "--resolution",
                        "PT1H", "--origin", "measured", "--archive", archive.toString()));
        assertEquals(List.of(), filesUnder(archive));
    }

    @Test
    void aNameThatLeavesItsArchiveDirectoryIsRefusedWith2AndWritesNothing() throws IOException {
        Path csv = Files.writeString(dir.resolve("p.csv"), "start,value\n2024-01-01T00:00:00+01:00,1.5\n");
        Path archive = dir.resolve("history");
        List<String> partner = List.of("import", "--file", csv.toString(), "--metric", "m", "--unit", "kW", "--resolution",
                "PT1H", "--archive", archive.toString(), "--name");
        Result first = cli(Stream.concat(partner.stream(), Stream.of("schedule")).toArray(String[]::new));
        assertEquals(0, first.code(), first.toString());
        List<Path> before = filesUnder(dir);
        for (String name : List.of("../../x", "../x", "a/./b", "a b")) {
            assertEquals(new Result(2, "", "opentsx: --name: \"" + name + "\" is not allowed; expected names of letters, "
                            + "digits, '.', '_' and '-', separated by '/', and none of them '.' or '..'\n"),
                    cli(Stream.concat(partner.stream(), Stream.of(name)).toArray(String[]::new)));
        }
        assertEquals(before, filesUnder(dir));
    }

    @Test
    void aMosmixStationThatLeavesItsArchiveDirectoryIsRefusedWith2BeforeAnyDownload() throws IOException {
        String archive = dir.resolve("history").toString();
        OpenTsxConf conf = OpenTsxConf.load(List.of("market", "mosmix-archive", "--archive", archive, "--stations",
                "10579,../x"), Map.of(), null).conf();
        assertEquals("--stations: item \"../x\" is not a station id; expected letters and digits such as 10579 or E5411",
                assertThrows(IllegalArgumentException.class,
                        () -> MarketCli.check("market mosmix-archive", conf, NOW, NO_DOWNLOAD)).getMessage());
        assertEquals(new Result(2, "", "opentsx: opentsx.market.mosmix.stations: item \"a/b\" is not a station id; "
                        + "expected letters and digits such as 10579 or E5411\n"),
                cli(NO_DOWNLOAD, Map.of("OPENTSX_MARKET_MOSMIX_STATIONS", "a/b"), "mosmix-archive", "--archive", archive));
        assertEquals(List.of(), filesUnder(dir));
    }

    @Test
    void aFetchWhoseEndIsNotAfterItsStartExitsWith2AndNamesBothOptions() throws IOException {
        String offline = smardPrice().toString();
        String refused = "opentsx: --to: \"2025-01-01\" is not after --from \"2025-02-01\"; expected an end after the "
                + "start\n";
        assertEquals(new Result(2, "", refused),
                cli("prices", "--offline", offline, "--from", "2025-02-01", "--to", "2025-01-01"));
        assertEquals(new Result(2, "", refused), cli("prices", "--from", "2025-02-01", "--to", "2025-01-01"));
        assertEquals(new Result(2, "", "opentsx: opentsx.market.to: \"2025-02-01\" is not after opentsx.market.from "
                        + "\"2025-02-01\"; expected an end after the start\n"),
                cli(NO_DOWNLOAD, Map.of("OPENTSX_MARKET_FROM", "2025-02-01", "OPENTSX_MARKET_TO", "2025-02-01"), "series",
                        "--name", "smard/load"));
    }

    @Test
    void theDownloadCommandsReadTheProxyKeyAndRefuseAMalformedOne() throws IOException {
        Map<String, String> env = Map.of("OPENTSX_HTTPS_PROXY", "http://user:s3cret@");
        Result refused = new Result(2, "", "opentsx: opentsx.https.proxy: not a proxy address; expected <host>:<port> or "
                + "http://<host>:<port>\n");
        assertEquals(refused, cli(down(), env, "prices", "--from", "2025-10-01", "--to", "2025-10-02"));
        assertEquals(refused, cli(down(), env, "backfill", "--archive", dir.resolve("history").toString(), "--series",
                "smard/load", "--from-year", "2024", "--to-year", "2024"));
        assertEquals(refused, cli(down(), env, "weather-backfill", "--archive", dir.resolve("history").toString(),
                "--stations", "03166:air_temperature", "--from-year", "2024", "--to-year", "2024"));
        assertEquals(refused, cli(down(), env, "mosmix-archive", "--archive", dir.resolve("history").toString()));
        assertEquals(List.of(), filesUnder(dir));
    }

    @Test
    void theProxyKeyReachesTheCollectorAndTheJvmProxyWins() {
        List<ProxySelector> seen = new ArrayList<>();
        MarketCli.Collectors recording = (delay, credentials, proxy) -> {
            seen.add(proxy);
            throw new IllegalStateException("recorded");
        };
        Map<String, String> env = Map.of("OPENTSX_HTTPS_PROXY", "proxy.example:3128");
        String jvm = System.getProperty("https.proxyHost");
        try {
            System.clearProperty("https.proxyHost");
            assertEquals(1, cli(recording, env, "prices", "--from", "2025-10-01", "--to", "2025-10-02").code());
            System.setProperty("https.proxyHost", "jvm-proxy.example");
            assertEquals(1, cli(recording, env, "backfill", "--series", "smard/load").code());
        } finally {
            if (jvm == null) {
                System.clearProperty("https.proxyHost");
            } else {
                System.setProperty("https.proxyHost", jvm);
            }
        }
        assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 3128))),
                seen.get(0).select(URI.create("https://www.smard.de/")));
        assertSame(ProxySelector.getDefault(), seen.get(1));
    }

    @Test
    void loadOpensIcebergWhenTheStoreKeySaysSoFromAnySource() {
        Result environment = cli(NO_DOWNLOAD, Map.of("OPENTSX_MARKET_LOAD_STORE", "iceberg", "OPENTSX_ICEBERG_PROPERTIES",
                "catalog-impl=org.example.NoSuchCatalog,uri=x"), "load", "--archive", dir.toString());
        assertEquals(1, environment.code(), environment.toString());
        assertTrue(environment.err().contains("org.example.NoSuchCatalog"), environment.err());
        assertEquals(new Result(0, "loaded 0 episodes, 0 values into an in-memory store\n", ""),
                cli("load", "--archive", dir.toString(), "--store", "memory", "--iceberg",
                        "catalog-impl=org.example.NoSuchCatalog,uri=x"));
        assertEquals(new Result(2, "", "opentsx: opentsx.iceberg.properties: not set; set it with --iceberg, --conf "
                        + "opentsx.iceberg.properties=<value> or OPENTSX_ICEBERG_PROPERTIES\n"),
                cli("load", "--archive", dir.toString(), "--store", "iceberg"));
    }

    @Test
    void loadWritesToIcebergOnlyWhenIcebergIsOnTheCommandLine() {
        assertEquals(new Result(0, "loaded 0 episodes, 0 values into an in-memory store\n", ""),
                cli(NO_DOWNLOAD, Map.of("OPENTSX_ICEBERG_PROPERTIES", "catalog-impl=org.example.NoSuchCatalog,uri=x"),
                        "load", "--archive", dir.toString()));
        Result commandLine = cli("load", "--archive", dir.toString(), "--iceberg",
                "catalog-impl=org.example.NoSuchCatalog,uri=x");
        assertEquals(1, commandLine.code(), commandLine.toString());
        assertTrue(commandLine.err().contains("org.example.NoSuchCatalog"), commandLine.err());
    }

    @Test
    void aMalformedValueIsOneLineThatNamesItsOption() throws IOException {
        assertEquals(new Result(2, "", "opentsx: --from: \"2024-13-01\" is not a date; expected a local date YYYY-MM-DD "
                        + "(Europe/Berlin) or an ISO instant such as 2024-01-01T00:00:00Z\n"),
                cli("prices", "--from", "2024-13-01", "--to", "2024-12-02"));
        assertEquals(new Result(2, "", "opentsx: --delay-ms: \"abc\" is not a long; expected a whole number from "
                        + "-9223372036854775808 to 9223372036854775807\n"),
                cli("backfill", "--delay-ms", "abc"));
        assertEquals(new Result(2, "", "opentsx: --from-day: \"2024-02-30\" is not a day; expected a UTC day YYYY-MM-DD\n"),
                cli("load", "--archive", dir.toString(), "--from-day", "2024-02-30"));
        assertEquals(new Result(2, "", "opentsx: --source: \"epex\" is not allowed; expected one of smard, energy-charts, entsoe\n"),
                cli("prices", "--source", "epex", "--from", "2025-10-01", "--to", "2025-10-02"));
        Path missing = dir.resolve("none.json");
        assertEquals(new Result(2, "", "opentsx: " + missing + ": no such file; give an existing file\n"),
                cli("prices", "--offline", missing.toString(), "--from", "2025-10-01", "--to", "2025-10-02"));
        Path broken = Files.writeString(dir.resolve("rebap.csv"), "no header\n");
        assertEquals(new Result(2, "", "opentsx: " + broken + ": no header line starting with 'Datum'\n"),
                cli("rebap", "--offline", broken.toString(), "--from", "2024-01-01", "--to", "2024-02-01"));
        assertEquals(new Result(2, "", "opentsx: --zone: \"Europe/Berln\" is not a time zone; give a region id such as "
                        + "Europe/Berlin or an offset such as +02:00\n"),
                cli("import", "--file", smardPrice().toString(), "--name", "s", "--metric", "m", "--unit", "kW", "--zone",
                        "Europe/Berln"));
    }

    @Test
    void theStartLineIsLoggedAtInfoWithTheCredentialsMasked() throws IOException, InterruptedException {
        Path offline = Files.writeString(dir.resolve("entsoe-a44.xml"), Fixtures.text("entsoe-a44.xml"));
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path"), MarketCli.class.getName(), "prices", "--source", "entsoe",
                "--offline", offline.toString(), "--from", "2025-09-30", "--to", "2025-10-02")
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().clear();
        pb.environment().putAll(Map.of("ENTSOE_TOKEN", "SECRET-TOKEN-123", "OPENTSX_LOG_LEVEL", "info"));
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            fail("still running after 60 s\nstderr: " + Files.readString(err));
        }
        String stderr = Files.readString(err);
        assertEquals(0, p.exitValue(), stderr);
        List<String> info = stderr.lines().filter(l -> l.contains(" INFO ")).toList();
        assertEquals(1, info.size(), stderr);
        assertTrue(info.get(0).contains(" INFO org.opentsx.market.MarketCli - starting market prices: "), info.get(0));
        assertTrue(info.get(0).contains("opentsx.market.entsoeToken=****** (environment)"), info.get(0));
        assertFalse(stderr.contains("SECRET-TOKEN-123"), stderr);
    }

    @Test
    void publishNeedsAnExplicitDomainAndAnArchivedSeries() {
        assertEquals(new Result(2, "", "opentsx: opentsx.domain: not set; set it with --domain, --conf opentsx.domain=<value>"
                        + " or OPENTSX_DOMAIN\n"),
                cli("publish", "--archive", dir.toString(), "--series", "smard/load", "--dry-run"));
        assertEquals(new Result(2, "", "opentsx: --series: item \"smard/load\" is not in the archive " + dir
                        + "; expected an archived series; run opentsx market report for them\n"),
                cli("publish", "--archive", dir.toString(), "--series", "smard/load", "--domain", "wind", "--dry-run"));
        assertEquals(new Result(2, "", "opentsx: --batch: \"0\" is not allowed; expected 1 or more\n"),
                cli("publish", "--archive", dir.toString(), "--series", "smard/load", "--domain", "wind", "--batch", "0",
                        "--dry-run"));
    }
    @Test
    void theJobsOfTheHistoryArchiveLoadTheirConfigurationInTheNamespaceOfTheBase() throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path base = Path.of("../deploy/k3s/base");
        Map<String, String> links = new HashMap<>();
        for (JsonNode resource : yaml.readTree(base.resolve("kustomization.yaml").toFile()).path("resources")) {
            for (JsonNode doc : yaml.readValues(yaml.createParser(base.resolve(resource.asText()).toFile()), JsonNode.class)
                    .readAll()) {
                if (doc.path("kind").asText().equals("Service")) {
                    String name = doc.path("metadata").path("name").asText().toUpperCase(Locale.ROOT).replace('-', '_');
                    String port = doc.path("spec").path("ports").get(0).path("port").asText();
                    links.put(name + "_SERVICE_HOST", "10.43.0.10");
                    links.put(name + "_SERVICE_PORT", port);
                    links.put(name + "_PORT", "tcp://10.43.0.10:" + port);
                }
            }
        }
        assertTrue(links.containsKey("OPENTSX_RETRIEVAL_PORT"), links.toString());
        List<String> commands = new ArrayList<>();
        List<Path> manifests;
        try (Stream<Path> files = Files.list(Path.of("../deploy/k3s/history"))) {
            manifests = files.filter(f -> !f.getFileName().toString().equals("kustomization.yaml")).sorted().toList();
        }
        for (Path f : manifests) {
            JsonNode doc = yaml.readTree(f.toFile());
            JsonNode spec = doc.path("kind").asText().equals("CronJob") ? doc.path("spec").path("jobTemplate").path("spec")
                    : doc.path("spec");
            JsonNode pod = spec.path("template").path("spec");
            if (pod.isMissingNode()) {
                continue;
            }
            // the optional Secret opentsx-market of kustomization.yaml, as envFrom puts it into the pod
            Map<String, String> env = new HashMap<>(Map.of("ENTSOE_TOKEN", "token", "NTP_CLIENT_ID", "client",
                    "NTP_CLIENT_SECRET", "secret"));
            if (pod.path("enableServiceLinks").asBoolean(true)) {
                env.putAll(links);
            }
            List<String> args = new ArrayList<>();
            pod.path("containers").get(0).path("args").forEach(a -> args.add(a.asText()));
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(args, env, null);
            MarketCli.check(loaded.command(), loaded.conf(), NOW, (delay, credentials, proxy) -> new Collector(null, null,
                    null, null, credentials));
            assertEquals("/data/history", loaded.conf().get(MarketCli.ARCHIVE), f.toString());
            commands.add(loaded.command());
        }
        assertEquals(List.of("market backfill", "market forward", "market mosmix-archive", "market weather-backfill",
                "market weather-forward"), commands);
    }

    @Test
    void noSourceFileCarriesALiteralProducerVersion() throws IOException {
        Pattern literal = Pattern.compile("opentsx-[a-z0-9-]+/[0-9]+\\.[0-9]+");
        List<Path> scanned;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            scanned = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(scanned.containsAll(List.of(Path.of("src/main/java/org/opentsx/market/MarketCli.java"),
                Path.of("src/main/java/org/opentsx/market/archive/BrokerPublish.java"))), scanned.toString());
        List<String> hits = new ArrayList<>();
        for (Path f : scanned) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (literal.matcher(lines.get(i)).find()) {
                    hits.add(f + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), hits);
    }
}
