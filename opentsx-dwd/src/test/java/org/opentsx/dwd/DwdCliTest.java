package org.opentsx.dwd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code bin/opentsx dwd ...} with {@code --offline} files (no network). */
class DwdCliTest {

    @TempDir
    Path dir;

    record Result(int code, String out, String err) {
    }

    private static Result cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DwdCli.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String run(String... args) {
        Result r = cli(Map.of(), args);
        assertEquals(0, r.code(), r.toString());
        assertEquals("", r.err());
        return r.out();
    }

    private Path fixture(String name) throws IOException {
        return Files.write(dir.resolve(name), Fixtures.bytes(name));
    }

    @Test
    void stationsNear() throws IOException {
        String out = run("stations", "--near", "50.6,13.0", "--n", "3", "--offline", fixture(Fixtures.MOSMIX_STATIONS).toString());
        assertTrue(out.contains("11 stations in catalog"), out);
        assertTrue(out.contains("N4377"), out);
        assertTrue(out.contains("10579"), out);
        String cdc = run("stations", "--near", "54.45,13.95", "--n", "1", "--offline", fixture(Fixtures.CDC_STATIONS).toString());
        assertTrue(cdc.contains("Greifswalder Oie"), cdc);
    }

    @Test
    void mosmixFromAnOfflineKmz() throws IOException {
        Path kmz = Files.write(dir.resolve("MOSMIX_L_LATEST_10578.kmz"),
                Fixtures.zip("MOSMIX_L_2026101209_10578.kml", Fixtures.bytes(Fixtures.KML), false));
        Path csv = dir.resolve("fc.csv");
        Path boat = dir.resolve("boat.csv");
        String out = run("mosmix", "--station", "10578", "--offline", kmz.toString(), "--out", csv.toString(),
                "--boat", boat.toString());
        assertTrue(out.contains("issued 2026-10-12T09:00:00Z"), out);
        assertTrue(out.contains("weather.irradiance_global"), out);
        assertTrue(out.contains("6 v2 episodes"), out);   // PPPP is all missing
        assertEquals(9, Files.readAllLines(csv).size());
        assertEquals(8, Files.readAllLines(boat).size());

        String house = run("house", "--offline", kmz.toString());
        assertTrue(house.contains("origin of weather.t_out_c: FORECAST"), house);
    }

    @Test
    void observationsFromOfflineFiles() throws IOException {
        Path daily = dir.resolve("daily.csv");
        String out = run("obs", "--offline", fixture(Fixtures.WIND_10MIN) + "," + fixture(Fixtures.TU_10MIN), "--out",
                dir.resolve("obs.csv").toString());
        assertTrue(out.contains("weather.wind_speed"), out);
        assertTrue(out.contains("MEASURED"), out);
        run("obs", "--offline", fixture(Fixtures.TU_HOURLY).toString(), "--daily", daily.toString());
        assertEquals(4, Files.readAllLines(daily).size());
    }

    @Test
    void aMalformedValueNamesItsOptionAndAnUnknownCommandIsAUsageError() throws IOException {
        String catalog = fixture(Fixtures.MOSMIX_STATIONS).toString();
        assertEquals(new Result(2, "", "opentsx: --n: \"abc\" is not an int; expected a whole number from -2147483648 to "
                + "2147483647\n"), cli(Map.of(), "stations", "--near", "50.6,13.0", "--n", "abc", "--offline", catalog));
        assertEquals(new Result(2, "", "opentsx: dwd frob: unknown command; expected one of dwd house, dwd mosmix, dwd obs, "
                + "dwd stations\n"), cli(Map.of(), "frob"));
        assertEquals(new Result(2, "", "opentsx: dwd: missing subcommand; expected one of dwd house, dwd mosmix, dwd obs, "
                + "dwd stations\n"), cli(Map.of()));
        assertEquals(new Result(2, "", "opentsx: --nera: unknown option of dwd stations; did you mean --near?\n"),
                cli(Map.of(), "stations", "--nera", "50.6,13.0"));
        assertEquals(new Result(2, "", "opentsx: --near: missing value; expected --near <list>\n"),
                cli(Map.of(), "stations", "--offline", catalog, "--near"));
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.near: not set; set it with --near, --conf opentsx.dwd.near=<value> "
                + "or OPENTSX_DWD_NEAR\n"), cli(Map.of(), "stations", "--offline", catalog));
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.near: \"50.6,13.0,7\" has 3 items; expected LAT,LON such as "
                + "50.6,13.0\n"), cli(Map.of(), "stations", "--near", "50.6,13.0,7", "--offline", catalog));
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.nearestCount: 0 is not allowed; expected 1 or more\n"),
                cli(Map.of(), "stations", "--near", "50.6,13.0", "--n", "0", "--offline", catalog));
    }

    @Test
    void anUnknownCatalogIsRefusedWithTheAllowedValues() throws IOException {
        assertEquals(new Result(2, "", "opentsx: --catalog: \"CDC\" is not allowed; expected one of mosmix, cdc\n"),
                cli(Map.of(), "stations", "--near", "50.6,13.0", "--catalog", "CDC", "--offline",
                        fixture(Fixtures.MOSMIX_STATIONS).toString()));
    }

    @Test
    void aMissingOfflineFileIsAUsageErrorWithoutATrace() {
        assertEquals(new Result(2, "", "opentsx: /nonexistent: no such file; give an existing station list\n"),
                cli(Map.of(), "stations", "--near", "50.6,13.0", "--offline", "/nonexistent"));
        assertEquals(new Result(2, "", "opentsx: /nonexistent.kmz: no such file; give an existing MOSMIX file\n"),
                cli(Map.of(), "mosmix", "--offline", "/nonexistent.kmz"));
        assertEquals(new Result(2, "", "opentsx: /nonexistent.zip: no such file; give an existing CDC observation file\n"),
                cli(Map.of(), "obs", "--offline", "/nonexistent.zip"));
        assertEquals(new Result(2, "", "opentsx: " + dir + ": no such file; give an existing station list\n"),
                cli(Map.of(), "stations", "--near", "50.6,13.0", "--offline", dir.toString()));
    }

    @Test
    void anOfflineFileWithUnreadableContentNamesTheFile() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.kml"), "not a forecast");
        Result r = cli(Map.of(), "mosmix", "--offline", broken.toString());
        assertEquals(2, r.code(), r.toString());
        assertTrue(r.err().startsWith("opentsx: " + broken + ": "), r.err());
        assertEquals(1, r.err().lines().count(), r.err());
    }

    @Test
    void aStationIdThatCannotBeAPathSegmentIsRefused() {
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.station: \"../10578\" is not a station id; expected letters and "
                + "digits such as 10578 or 01358\n"), cli(Map.of(), "mosmix", "--station", "../10578"));
    }

    @Test
    void offlineObservationsRefuseTheDownloadOptions() throws IOException {
        String file = fixture(Fixtures.WIND_10MIN).toString();
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.station: cannot be combined with opentsx.dwd.obs.offline; give "
                + "--station or --offline, not both\n"), cli(Map.of(), "obs", "--offline", file, "--station", "x"));
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.params: cannot be combined with opentsx.dwd.obs.offline; give "
                + "--param or --offline, not both\n"), cli(Map.of(), "obs", "--offline", file, "--param", "wind"));
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.hourly: cannot be combined with opentsx.dwd.obs.offline; give "
                + "--hourly or --offline, not both\n"), cli(Map.of(), "obs", "--offline", file, "--hourly"));
    }

    @Test
    void hourlyObservationsTakeOnlyTheHourlyParameters() {
        assertEquals(new Result(2, "", "opentsx: opentsx.dwd.params: \"solar\" has no hourly recent product; expected wind or "
                + "air_temperature with hourly\n"), cli(Map.of(), "obs", "--station", "01358", "--hourly", "--param", "solar"));
    }

    @Test
    void helpAndVersionPrintOnStdout() {
        Result help = cli(Map.of(), "stations", "--help");
        assertEquals(new Result(0, Registry.shipped().help("dwd stations"), ""), help);
        assertTrue(help.out().contains("  opentsx.dwd.stationCatalog  enum = mosmix  one of mosmix|cdc  --catalog"), help.out());
        assertTrue(help.out().contains("  opentsx.https.proxy  string  OPENTSX_HTTPS_PROXY, HTTPS_PROXY, https_proxy  secret"),
                help.out());
        assertEquals(new Result(0, Registry.shipped().help(), ""), cli(Map.of(), "--help"));
        assertEquals(new Result(0, ConfCli.versionLine(), ""), cli(Map.of(), "mosmix", "--version"));
    }

    @Test
    void aMalformedProxyIsRefusedWithoutEchoingIt() {
        assertEquals(new Result(2, "", "opentsx: opentsx.https.proxy: not a proxy address; expected <host>:<port> or "
                + "http://<host>:<port>\n"), cli(Map.of("OPENTSX_HTTPS_PROXY", "http://user:s3cret@"), "mosmix", "--station", "10578"));
    }

    /** The pod of a manifest in the namespace of deploy/k3s/base: its env with the config map and the Service links. */
    static Map<String, String> podEnv(JsonNode pod) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path base = Path.of("../deploy/k3s/base");
        JsonNode kustomization = yaml.readTree(base.resolve("kustomization.yaml").toFile());
        Map<String, String> literals = new HashMap<>();
        for (JsonNode literal : kustomization.path("configMapGenerator").get(0).path("literals")) {
            String[] kv = literal.asText().split("=", 2);
            literals.put(kv[0], kv[1]);
        }
        Map<String, String> env = new HashMap<>();
        if (pod.path("enableServiceLinks").asBoolean(true)) {
            for (JsonNode resource : kustomization.path("resources")) {
                for (JsonNode doc : yaml.readValues(yaml.createParser(base.resolve(resource.asText()).toFile()), JsonNode.class)
                        .readAll()) {
                    if (doc.path("kind").asText().equals("Service")) {
                        String name = doc.path("metadata").path("name").asText().toUpperCase(Locale.ROOT).replace('-', '_');
                        String port = doc.path("spec").path("ports").get(0).path("port").asText();
                        env.put(name + "_SERVICE_HOST", "10.43.0.10");
                        env.put(name + "_SERVICE_PORT", port);
                        env.put(name + "_PORT", "tcp://10.43.0.10:" + port);
                    }
                }
            }
            assertTrue(env.containsKey("OPENTSX_RETRIEVAL_PORT"), env.toString());
        }
        for (JsonNode e : pod.path("containers").get(0).path("env")) {
            JsonNode ref = e.path("valueFrom").path("configMapKeyRef");
            env.put(e.path("name").asText(), ref.isMissingNode() ? e.path("value").asText() : literals.get(ref.path("key").asText()));
        }
        return env;
    }

    /** The argv of every {@code opentsx dwd} line of the shell script of the pod, with the variables replaced. */
    static List<List<String>> dwdCommands(JsonNode pod, Map<String, String> vars) {
        List<List<String>> out = new ArrayList<>();
        for (String line : pod.path("containers").get(0).path("args").get(0).asText().split("\n")) {
            int at = line.indexOf("opentsx dwd ");
            if (at < 0) {
                continue;
            }
            String cmd = line.substring(at + "opentsx ".length()).replace("\\", "").replace("\"", "");
            for (Map.Entry<String, String> v : vars.entrySet().stream()
                    .sorted((a, b) -> b.getKey().length() - a.getKey().length()).toList()) {
                cmd = cmd.replace("$" + v.getKey(), v.getValue());
            }
            out.add(List.of(cmd.strip().split("\\s+")));
        }
        return out;
    }

    @Test
    void theCronJobDwdForwardLoadsItsConfigurationInTheNamespaceOfTheBase() throws IOException {
        JsonNode pod = new ObjectMapper(new YAMLFactory()).readTree(Path.of("../deploy/k3s/components/dwd-forward/dwd-forward.yaml")
                .toFile()).path("spec").path("jobTemplate").path("spec").path("template").path("spec");
        Map<String, String> env = podEnv(pod);
        assertEquals("online", env.get("DWD_MODE"));
        List<List<String>> online = dwdCommands(pod, Map.of("src", "--station 10578", "s", "10578", "ts", "20261119T000000Z"));
        assertEquals(List.of("dwd", "mosmix", "--station", "10578", "--out", "/data/dwd/mosmix-10578-20261119T000000Z.csv",
                "--daily", "/data/dwd/daily-10578-20261119T000000Z.csv"), online.get(0));
        OpenTsxConf conf = OpenTsxConf.load(online.get(0), env, null).conf();
        assertEquals("10578", conf.get(DwdCli.STATION));
        DwdCli.check("dwd mosmix", conf);

        assertTrue(pod.path("containers").get(0).path("args").get(0).asText().contains("export OPENTSX_DWD_OFFLINE=\"$DWD_FIXTURE\""));
        Map<String, String> offline = new HashMap<>(env);
        offline.put("OPENTSX_DWD_OFFLINE", env.get("DWD_FIXTURE"));
        assertEquals(env.get("DWD_FIXTURE"), OpenTsxConf.load(online.get(0), offline, null).conf().get(DwdCli.OFFLINE));
    }

    @Test
    void theDemoJobDwdLoadsItsConfigurationInTheNamespaceOfTheBase() throws IOException {
        JsonNode pod = new ObjectMapper(new YAMLFactory()).readTree(Path.of("../deploy/k3s/jobs/dwd.yaml").toFile())
                .path("spec").path("template").path("spec");
        Map<String, String> env = podEnv(pod);
        List<List<String>> commands = dwdCommands(pod, Map.of());
        assertEquals(List.of(List.of("dwd", "mosmix"), List.of("dwd", "house")), commands);
        for (List<String> argv : commands) {
            assertEquals("/opt/opentsx/share/dwd/MOSMIX_L_2026101209_10578.kml",
                    OpenTsxConf.load(argv, env, null).conf().get(DwdCli.OFFLINE), argv.toString());
        }
        assertEquals("/data/dwd/demo-mosmix.csv", OpenTsxConf.load(commands.get(0), env, null).conf().get(DwdCli.OUT));
        Map<String, String> online = new HashMap<>(env);
        online.remove("OPENTSX_DWD_OFFLINE");
        online.put("OPENTSX_DWD_STATION", "10578");
        OpenTsxConf conf = OpenTsxConf.load(commands.get(0), online, null).conf();
        assertEquals("10578", conf.get(DwdCli.STATION));
        DwdCli.check("dwd mosmix", conf);
    }

    @Test
    void theProxyIsNotACommandLineOption() {
        Result r = cli(Map.of(), "mosmix", "--station", "10578", "--conf", "opentsx.https.proxy=proxy.example:3128");
        assertEquals(new Result(2, "", "opentsx: opentsx.https.proxy: secret keys are not accepted on the command line; set "
                + "OPENTSX_HTTPS_PROXY or HTTPS_PROXY or https_proxy or put opentsx.https.proxy in a properties file\n"), r);
    }
}
