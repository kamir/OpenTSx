package org.opentsx.twin.demo;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwinCliTest {

    interface Main {
        int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err);
    }

    record Result(int exit, String out, String err) {
    }

    static final String H2_ALLOWED = "expected a twin plan in YAML such as opentsx-twin/src/main/resources/twin/h2-chain.yaml";

    @TempDir
    Path tmp;

    static Result run(Main main, String... args) {
        return run(main, Map.of(), args);
    }

    static Result run(Main main, Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = main.run(List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void houseRefusesAMalformedHddRefWithOneLineThatNamesTheFlag() {
        assertEquals(new Result(2, "", "opentsx: --hdd-ref: \"abc\" is not a double; expected a finite decimal number "
                + "such as 0.01 or 1e-3\n"), run(HouseDemo::run, "--hdd-ref", "abc"));
    }

    @Test
    void boatRefusesAnUnknownTimeZoneWithOneLine() {
        assertEquals(new Result(2, "", "opentsx: opentsx.boat.zone: \"Europe/Berln\" is not a time zone; give a region "
                + "id such as Europe/Berlin or an offset such as +02:00\n"), run(BoatDemo::run, "--zone", "Europe/Berln"));
    }

    @Test
    void anUnknownFlagExitsTwoInEveryCommand() {
        assertEquals(new Result(2, "", "opentsx: --plna: unknown option of twin; did you mean --plan?\n"),
                run(H2ChainDemo::run, "--plna", "x.yaml"));
        assertEquals(new Result(2, "", "opentsx: --calibrat: unknown option of house; did you mean --calibrate?\n"),
                run(HouseDemo::run, "--calibrat", "bills.csv"));
        assertEquals(new Result(2, "", "opentsx: --zon: unknown option of boat; did you mean --zone?\n"),
                run(BoatDemo::run, "--zon", "UTC"));
        assertEquals(new Result(2, "", "opentsx: --scenarios: unknown option of twin-coupling; did you mean --scenario?\n"),
                run(CouplingStudy::run, "--scenarios", "haus"));
    }

    @Test
    void anUnreadablePlanExitsOneInEveryCommandWithAPlan() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("plan.yaml"));
        String line = "opentsx: " + dir + ": cannot read the plan file; caused by java.io.IOException: Is a directory\n";
        for (Main main : List.<Main>of(H2ChainDemo::run, HouseDemo::run, BoatDemo::run)) {
            assertEquals(new Result(1, "", line), run(main, "--plan", dir.toString()));
        }
    }

    @Test
    void aMissingOrInvalidPlanIsAUsageError() throws IOException {
        Path missing = tmp.resolve("missing.yaml");
        assertEquals(new Result(2, "", "opentsx: " + missing + ": no such file; give an existing plan file\n"),
                run(H2ChainDemo::run, "--plan", missing.toString()));

        Path unknownField = Files.writeString(tmp.resolve("unknown.yaml"), "twin: t\nstart: 2025-10-01T00:00:00Z\n"
                + "baseStep: PT1M\nsteps: 10\natoms: [{id: m, type: market}]\nwiring: []\ncolour: red\n");
        Result r = run(H2ChainDemo::run, "--plan", unknownField.toString());
        assertEquals(2, r.exit(), r.err());
        assertTrue(r.err().startsWith("opentsx: " + unknownField + ": Unrecognized field \"colour\""), r.err());
        assertTrue(r.err().endsWith("; " + H2_ALLOWED + "\n") && r.err().indexOf('\n') == r.err().length() - 1,
                r.err());

        Path badWire = Files.writeString(tmp.resolve("wire.yaml"), "twin: t\nstart: 2025-10-01T00:00:00Z\n"
                + "baseStep: PT1M\nsteps: 10\natoms: [{id: m, type: market}]\nwiring: [\"m.price -> x.in\"]\n");
        assertEquals(new Result(2, "", "opentsx: " + badWire + ": unknown atom in wire m.price -> x.in; "
                + H2_ALLOWED + "\n"), run(H2ChainDemo::run, "--plan", badWire.toString()));
    }

    @Test
    void twinRunsTheScenarioFromAPlanFile() throws IOException {
        Path plan = Files.writeString(tmp.resolve("h2.yaml"), new String(getClass().getClassLoader()
                .getResourceAsStream("twin/h2-chain.yaml").readAllBytes(), StandardCharsets.UTF_8));
        Result r = run(H2ChainDemo::run, "--plan", plan.toString());
        assertEquals(0, r.exit(), r.err());
        assertEquals("", r.err());
        assertTrue(r.out().contains("\n1. Shadow: observed history (valve closed at 01:00)\n"), r.out());
        assertTrue(r.out().contains("\n3b. Forecast from 01:10, electrolyzer policy pressure-aware\n"), r.out());
    }

    @Test
    void aPositionalArgumentIsRefused() {
        assertEquals(new Result(2, "", "opentsx: plan.yaml: unexpected argument; expected opentsx twin\n"),
                run(H2ChainDemo::run, "plan.yaml"));
    }

    @Test
    void helpAndVersionPrintOnStdoutAndExitZero() {
        for (Map.Entry<String, Main> c : Map.<String, Main>of("twin", H2ChainDemo::run, "house", HouseDemo::run,
                "boat", BoatDemo::run, "twin-coupling", CouplingStudy::run).entrySet()) {
            assertEquals(new Result(0, Registry.shipped().help(c.getKey()), ""), run(c.getValue(), "--help"));
            assertEquals(new Result(0, ConfCli.versionLine(), ""), run(c.getValue(), "--version"));
        }
        String help = run(HouseDemo::run, "--help").out();
        assertTrue(help.contains("  opentsx.house.hddRef  double = 4300  unit Kd  --hdd-ref  OPENTSX_HOUSE_HDD_REF\n"), help);
        assertTrue(!help.contains("parses its own flags"), help);
    }

    @Test
    void couplingRefusesAScenarioThatMatchesNothing() {
        Result r = run(CouplingStudy::run, "--scenario", "nosuch");
        assertEquals(2, r.exit());
        assertEquals("", r.out());
        assertTrue(r.err().startsWith("opentsx: ") && r.err().indexOf('\n') == r.err().length() - 1, r.err());
        for (String text : List.of("nosuch", "h2-chain", "haus", "boot")) {
            assertTrue(r.err().contains(text), r.err());
        }
    }

    @Test
    void couplingExitsOneWhenTheCsvCannotBeWritten() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("grid.csv"));
        Result r = run(CouplingStudy::run, "--scenario", "h2-chain", "--csv", dir.toString());
        assertEquals(1, r.exit());
        assertTrue(r.out().startsWith("| scenario | staleness |"), r.out());
        assertTrue(r.out().contains("| h2-chain | "), r.out());
        assertEquals("opentsx: java.nio.file.FileSystemException: " + dir + ": Is a directory\n", r.err());
    }

    @Test
    void boatRefusesContradictoryOptions() {
        assertEquals(new Result(2, "", "opentsx: opentsx.boat.calibrate: needs opentsx.boat.data; give --data "
                + "<file.csv> with --calibrate\n"), run(BoatDemo::run, "--calibrate"));
        assertEquals(new Result(2, "", "opentsx: opentsx.twin.simulated: cannot be combined with opentsx.boat.data; "
                + "give --data <file.csv> or --simulated, not both\n"), run(BoatDemo::run, "--simulated", "--data", "d.csv"));
    }

    @Test
    void aBadDataFileIsNamedAndMissingOnesAreUsageErrors() throws IOException {
        Path bills = Files.writeString(tmp.resolve("bills.csv"), "von,bis\n2024-01-01,2024-01-31\n");
        Result house = run(HouseDemo::run, "--calibrate", bills.toString());
        assertEquals(2, house.exit());
        assertTrue(house.err().startsWith("opentsx: " + bills + ": missing column zeitraum_von; expected "), house.err());
        assertEquals("", house.out());

        Path good = Files.write(tmp.resolve("good.csv"), getClass().getClassLoader()
                .getResourceAsStream("twin/beispiel/haus-abrechnungen-simuliert.csv").readAllBytes());
        Path oneColumn = Files.writeString(tmp.resolve("t1.csv"), "datum,t_mittel_c\n2024-01-01\n");
        assertEquals(new Result(2, "", "opentsx: " + oneColumn + ": line 2: no temperature; expected datum,t_mittel_c\n"),
                run(HouseDemo::run, "--calibrate", good.toString(), "--weather", oneColumn.toString()));
        Path badDate = Files.writeString(tmp.resolve("t2.csv"), "datum,t_mittel_c\n2024-13-01,3.5\n");
        Result date = run(HouseDemo::run, "--calibrate", good.toString(), "--weather", badDate.toString());
        assertEquals(2, date.exit(), date.err());
        assertTrue(date.err().startsWith("opentsx: " + badDate + ": line 2: Text '2024-13-01' could not be parsed"), date.err());

        Path data = tmp.resolve("absent.csv");
        assertEquals(new Result(2, "", "opentsx: " + data + ": no such file; give an existing operating data file\n"),
                run(BoatDemo::run, "--data", data.toString()));
    }

    @Test
    void houseRefusesAPlanWithoutTheHouseAtomsBeforeItWritesOrReadsTheData() throws IOException {
        Path h2 = Files.write(tmp.resolve("h2.yaml"), resource("twin/h2-chain.yaml"));
        Path bills = Files.write(tmp.resolve("bills.csv"), resource("twin/beispiel/haus-abrechnungen-simuliert.csv"));
        Path temps = Files.write(tmp.resolve("temps.csv"), resource("twin/beispiel/haus-tagesmittel-simuliert.csv"));
        Result expected = new Result(2, "", "opentsx: " + h2 + ": plan h2-chain has no atom of type building, weather, "
                + "oil-boiler, oil-tank, wood-stove, wood-store, dhw, appliances, meter; expected a house plan such as "
                + "opentsx-twin/src/main/resources/twin/haus-erzgebirge.yaml\n");
        assertEquals(expected, run(HouseDemo::run, "--plan", h2.toString(), "--calibrate", bills.toString(),
                "--weather", temps.toString()));
        assertEquals(expected, run(HouseDemo::run, "--plan", h2.toString()));
    }

    @Test
    void aPlanWithoutStepsIsAUsageError() throws IOException {
        String plan = new String(resource("twin/h2-chain.yaml"), StandardCharsets.UTF_8);
        Path negative = Files.writeString(tmp.resolve("negative.yaml"), plan.replaceFirst("(?m)^steps: .*$", "steps: -5"));
        assertEquals(new Result(2, "", "opentsx: " + negative + ": steps: -5 is not allowed; expected a whole number of "
                + "at least 1\n"), run(H2ChainDemo::run, "--plan", negative.toString()));
        Path none = Files.writeString(tmp.resolve("none.yaml"), plan.replaceFirst("(?m)^steps: .*\n", ""));
        assertEquals(new Result(2, "", "opentsx: " + none + ": steps: 0 is not allowed; expected a whole number of "
                + "at least 1\n"), run(H2ChainDemo::run, "--plan", none.toString()));
    }

    @Test
    void aBrokenPlanNamesTheExamplePlanOfItsCommand() throws IOException {
        Path broken = Files.writeString(tmp.resolve("broken.yaml"), "twin: [t\n");
        String house = run(HouseDemo::run, "--plan", broken.toString()).err();
        assertTrue(house.startsWith("opentsx: " + broken + ": ") && house.endsWith("; expected a twin plan in YAML such as "
                + "opentsx-twin/src/main/resources/twin/haus-erzgebirge.yaml\n"), house);
        String boat = run(BoatDemo::run, "--plan", broken.toString()).err();
        assertTrue(boat.startsWith("opentsx: " + broken + ": ") && boat.endsWith("; expected a twin plan in YAML such as "
                + "opentsx-twin/src/main/resources/twin/boot-etap22.yaml\n"), boat);
        assertTrue(run(H2ChainDemo::run, "--plan", broken.toString()).err().endsWith("; " + H2_ALLOWED + "\n"));
    }

    @Test
    void aBillsFileWithoutRowsIsAUsageError() throws IOException {
        Path headerOnly = Files.writeString(tmp.resolve("b0.csv"), "zeitraum_von,zeitraum_bis\n");
        assertEquals(new Result(2, "", "opentsx: " + headerOnly + ": no bill rows; expected a header and at least one "
                + "row\n"), run(HouseDemo::run, "--calibrate", headerOnly.toString()));
    }

    @Test
    void aBadTimeOrModeInTheBoatDataNamesTheFileAndTheLine() throws IOException {
        Path badTime = Files.writeString(tmp.resolve("time.csv"), "# logbook\nzeit,modus\n2026-07-04T10:00Z,hafen\nx,hafen\n");
        assertEquals(new Result(2, "", "opentsx: " + badTime + ": line 4: zeit \"x\" is not a time; expected "
                + "2026-07-04T10:00+02:00, 2026-07-04T08:00:00Z or 2026-07-04 10:00\n"),
                run(BoatDemo::run, "--data", badTime.toString()));
        Path badMode = Files.writeString(tmp.resolve("mode.csv"), "# logbook\nzeit,modus\n2026-07-04T10:00Z,hafen\n"
                + "2026-07-04T10:05Z,kreuzfahrt\n");
        assertEquals(new Result(2, "", "opentsx: " + badMode + ": line 4: unknown mode 'kreuzfahrt', use hafen, segeln, "
                + "anker, unbemannt\n"), run(BoatDemo::run, "--data", badMode.toString()));
    }

    @Test
    void aRefusedRowOfTheHouseFilesNamesItsLineInTheFile() throws IOException {
        Path bills = Files.writeString(tmp.resolve("bills.csv"), "# bills\nzeitraum_von,zeitraum_bis\n2024-01-01,\n");
        assertEquals(new Result(2, "", "opentsx: " + bills + ": line 3: date missing\n"),
                run(HouseDemo::run, "--calibrate", bills.toString()));
        Path good = Files.write(tmp.resolve("good.csv"), resource("twin/beispiel/haus-abrechnungen-simuliert.csv"));
        Path temps = Files.writeString(tmp.resolve("temps.csv"), "# temperatures\ndatum,t_mittel_c\n2024-01-01\n");
        assertEquals(new Result(2, "", "opentsx: " + temps + ": line 3: no temperature; expected datum,t_mittel_c\n"),
                run(HouseDemo::run, "--calibrate", good.toString(), "--weather", temps.toString()));
    }

    private byte[] resource(String name) throws IOException {
        return getClass().getClassLoader().getResourceAsStream(name).readAllBytes();
    }

    @Test
    void aValueFromTheEnvironmentIsCheckedLikeAFlag() {
        assertEquals(new Result(2, "", "opentsx: OPENTSX_HOUSE_HDD_REF: \"4300 Kd\" is not a double; expected a finite "
                + "decimal number such as 0.01 or 1e-3\n"), run(HouseDemo::run, Map.of("OPENTSX_HOUSE_HDD_REF", "4300 Kd")));
    }

    /** The pods of the jobs twin, house, boat and studio-export load the configuration from the pod environment. */
    @Test
    void theJobsLoadTheirConfigurationInTheNamespaceOfTheBase() throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Path base = Path.of("../deploy/k3s/base");
        JsonNode kustomization = yaml.readTree(base.resolve("kustomization.yaml").toFile());
        Map<String, String> links = new HashMap<>();
        for (JsonNode resource : kustomization.path("resources")) {
            for (JsonNode doc : yaml.readValues(yaml.createParser(base.resolve(resource.asText()).toFile()), JsonNode.class)
                    .readAll()) {
                if (doc.path("kind").asText().equals("Service")) {
                    String name = doc.path("metadata").path("name").asText().toUpperCase().replace('-', '_');
                    String port = doc.path("spec").path("ports").get(0).path("port").asText();
                    links.put(name + "_SERVICE_HOST", "10.43.0.10");
                    links.put(name + "_SERVICE_PORT", port);
                    links.put(name + "_PORT", "tcp://10.43.0.10:" + port);
                }
            }
        }
        assertTrue(links.containsKey("OPENTSX_RETRIEVAL_PORT"), links.toString());
        for (String job : List.of("twin", "house", "boat", "studio-export")) {
            JsonNode pod = yaml.readTree(Path.of("../deploy/k3s/jobs/" + job + ".yaml").toFile())
                    .path("spec").path("template").path("spec");
            Map<String, String> env = new HashMap<>(pod.path("enableServiceLinks").asBoolean(true) ? links : Map.of());
            JsonNode container = pod.path("containers").get(0);
            for (JsonNode e : container.path("env")) {
                env.put(e.path("name").asText(), e.path("value").asText());
            }
            List<String> argv = new ArrayList<>();
            for (JsonNode a : container.path("args")) {
                argv.add(a.asText());
            }
            if (container.path("command").path(0).asText().equals("/bin/sh")) {
                String script = argv.get(1);
                argv = List.of(script.substring("opentsx ".length(), script.indexOf(" &&")).split(" "));
            }
            assertEquals(job, argv.get(0), job);
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(argv, env, null);
            assertEquals(job, loaded.command(), job);
        }
    }
}
