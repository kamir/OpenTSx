package org.opentsx.twin.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.Registry;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.boat.BoatData;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        assertTrue(house.err().startsWith("opentsx: " + bills + ": missing column period_from; expected "), house.err());
        assertEquals("", house.out());

        Path good = Files.write(tmp.resolve("good.csv"), getClass().getClassLoader()
                .getResourceAsStream("twin/beispiel/haus-abrechnungen-simuliert.csv").readAllBytes());
        Path oneColumn = Files.writeString(tmp.resolve("t1.csv"), "datum,t_mittel_c\n2024-01-01\n");
        assertEquals(new Result(2, "", "opentsx: " + oneColumn + ": line 2: no temperature; expected date,t_mean_c\n"),
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
        assertEquals(new Result(2, "", "opentsx: " + negative + ": steps: -5 is not allowed; expected a whole number from "
                + "1 to 35136\n"), run(H2ChainDemo::run, "--plan", negative.toString()));
        Path none = Files.writeString(tmp.resolve("none.yaml"), plan.replaceFirst("(?m)^steps: .*\n", ""));
        assertEquals(new Result(2, "", "opentsx: " + none + ": steps: 0 is not allowed; expected a whole number from "
                + "1 to 35136\n"), run(H2ChainDemo::run, "--plan", none.toString()));
    }

    @Test
    void aPlanWithMoreStepsThanARunCanHoldIsAUsageErrorOfEveryCommand() throws IOException {
        record Limit(String plan, Main command, int steps) {
        }
        for (Limit c : List.of(new Limit("twin/h2-chain.yaml", H2ChainDemo::run, 35136),
                new Limit("twin/boot-etap22.yaml", BoatDemo::run, 35136),
                new Limit("twin/haus-erzgebirge.yaml", HouseDemo::run, 87673))) {
            for (int steps : new int[] {0, c.steps() + 1, 50000000}) {
                Path plan = Files.writeString(tmp.resolve(steps + "-" + c.plan().substring(5)), new String(
                        resource(c.plan()), StandardCharsets.UTF_8).replaceFirst("(?m)^steps: .*$", "steps: " + steps));
                assertEquals(new Result(2, "", "opentsx: " + plan + ": steps: " + steps + " is not allowed; expected a "
                        + "whole number from 1 to " + c.steps() + "\n"), run(c.command(), "--plan", plan.toString()));
            }
        }
        TwinPlan p = HouseDemo.plan();
        assertEquals(87673, new TwinPlan(p.twin(), p.start(), p.baseStep(), 87673, p.atoms(), p.wiring()).steps());
        assertEquals("steps: 87674 is not allowed; expected a whole number from 1 to 87673", assertThrows(
                IllegalArgumentException.class, () -> new TwinPlan(p.twin(), p.start(), p.baseStep(), 87674, p.atoms(),
                        p.wiring())).getMessage());
    }

    @Test
    void theHouseOutputSaysWhatIsInventedOrSimulatedWithoutDashes() throws IOException {
        Path days = Files.writeString(tmp.resolve("house-4-days.yaml"), new String(resource("twin/haus-erzgebirge.yaml"),
                StandardCharsets.UTF_8).replaceFirst("(?m)^baseStep: .*$", "baseStep: PT24H")
                .replaceFirst("(?m)^steps: .*$", "steps: 4"));
        Result reference = run(HouseDemo::run, "--plan", days.toString());
        assertEquals(0, reference.exit(), reference.err());
        assertTrue(reference.out().startsWith("House twin 'haus-erzgebirge': ALL NUMBERS ARE INVENTED EXAMPLE "
                + "ASSUMPTIONS (not calibrated)\n"), reference.out());
        assertTrue(reference.out().contains("\n2. What-if: MODELLED potential per lever = alternative minus reference "
                + "(same weather, same plan)\n"), reference.out());
        assertFalse(reference.out().contains(" - "), reference.out());

        Result simulated = run(HouseDemo::run, "--plan", days.toString(), "--simulated");
        assertEquals(0, simulated.exit(), simulated.err());
        assertTrue(simulated.out().startsWith("House twin 'haus-erzgebirge' calibrated on SIMULATED bills "
                + "(twin/beispiel/haus-abrechnungen-simuliert.csv, twin/beispiel/haus-tagesmittel-simuliert.csv); no real "
                + "data\n"), simulated.out());
        assertTrue(simulated.out().contains("\n"
                + "   ! oil 2022-04-20..2023-11-04: delivery used as consumption (tank levels missing), weighted 0.2; merge "
                + "several years or add gauge readings\n"
                + "   ! oil 2023-11-05..2024-04-20: delivery used as consumption (tank levels missing), weighted 0.2; merge "
                + "several years or add gauge readings\n"
                + "   ! poorly determined by these bills (relative standard error > 25 %): [dhw.scale]; the value is hardly "
                + "better than the assumption\n"
                + "   ! dhw.scale at its bound 3.0; check the data or the fixed assumptions\n"
                + "   ! fixed assumptions (not fitted): boiler efficiency, standby loss, wood energy per Ster, heating oil "
                + "10 kWh/l; from period totals only H/eta is identified\n"), simulated.out());
        assertFalse(simulated.out().contains(" - "), simulated.out());
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
                + "2026-07-04T10:05Z,cruise\n");
        assertEquals(new Result(2, "", "opentsx: " + badMode + ": line 4: mode \"cruise\" is not a mode; expected "
                + "harbour, sailing, anchor or away\n"), run(BoatDemo::run, "--data", badMode.toString()));
    }

    @Test
    void theBoatDataFileIsReadOnlyUpToTheFirstRowOverTheBound() throws IOException {
        Path data = tmp.resolve("long.csv");
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(data))) {
            o.write("time,soc_percent\n".getBytes(StandardCharsets.US_ASCII));
            byte[] row = "2026-07-04T10:00Z,80\n".getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 532_041; i++) {
                o.write(row);
            }
            // not UTF-8, 5000 rows after the bound: beyond what the reader buffers ahead
            o.write(new byte[]{'x', (byte) 0xFF, '\n'});
        }
        assertEquals(new Result(2, "", "opentsx: " + data + ": line 527042: more than 527040 data rows; expected at "
                + "most 527040 rows, such as one row per minute for 366 days\n"),
                run(BoatDemo::run, "--data", data.toString()));
    }

    @Test
    void boatRefusesADataFileOf64MiBAndOneByte() throws IOException {
        Path data = Files.writeString(tmp.resolve("big.csv"), "time,soc_percent\n2026-07-04T10:00Z,80\n#");
        try (RandomAccessFile f = new RandomAccessFile(data.toFile(), "rw")) {
            f.setLength(64L * 1024 * 1024 + 1);
        }
        assertEquals(new Result(2, "", "opentsx: " + data + ": more than 67108864 bytes; expected a file of at most "
                + "67108864 bytes\n"), run(BoatDemo::run, "--data", data.toString()));
    }

    @Test
    void boatKeepsOnlyTheColumnsItReadsSoAWideFileFitsASmallHeap() throws IOException, InterruptedException {
        Path data = tmp.resolve("wide.csv");
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(data))) {
            StringBuilder header = new StringBuilder("time");
            for (int c = 1; c <= 54; c++) {
                header.append(",c").append(c);
            }
            o.write((header + "\n").getBytes(StandardCharsets.US_ASCII));
            byte[] row = ("2026-07-04T10:00Z" + ",1".repeat(54) + "\n").getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 100_000; i++) {
                o.write(row);
            }
        }
        // kept in full, 100000 rows of 55 fields take about 290 MB, more than twice the heap
        Path out = tmp.resolve("out.txt");
        Path err = tmp.resolve("err.txt");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:+UseSerialGC", "-cp", System.getProperty("surefire.test.class.path"),
                BoatDemo.class.getName(), "--data", data.toString())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().clear();
        Process p = pb.start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            fail("still running after 120 s\nstderr: " + Files.readString(err));
        }
        String stderr = Files.readString(err);
        assertEquals(0, p.exitValue(), stderr.lines().limit(5).collect(Collectors.joining("\n")));
        assertTrue(Files.readString(out).startsWith("Boat twin boot-etap22 on operating data: 100000 rows, 1 steps "
                + "from 2026-07-04T10:00:00Z"), Files.readString(out));
    }

    @Test
    void aFileOverTheByteBoundIsRefusedWithExitTwoBeforeItIsReadInFull() throws IOException {
        Path blank = tmp.resolve("blank.csv");
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(blank))) {
            o.write("time,soc_percent\n2026-07-04T10:00Z,80\n".getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < 100_000; i++) {
                o.write('\n');
            }
            // not UTF-8, far beyond what the reader buffers ahead of the bound
            o.write(new byte[]{'x', (byte) 0xFF, '\n'});
        }
        Main boat = (args, env, out, err) -> TwinCli.run("boat", args, env, out, err, (conf, rest) -> {
            TwinCli.csv(blank, "operating data file", BoatData.MAX_ROWS, 4096, lines -> lines);
            return o -> { };
        });
        assertEquals(new Result(2, "", "opentsx: " + blank + ": more than 4096 bytes; expected a file of at most 4096 "
                + "bytes\n"), run(boat));

        Path atTheBound = Files.writeString(tmp.resolve("bound.csv"), "a\n" + "\n".repeat(4094));
        assertEquals(4096, Files.size(atTheBound));
        assertEquals(4095, TwinCli.csv(atTheBound, "rows file", 1, 4096, lines -> lines).size());
        Path overTheBound = Files.writeString(tmp.resolve("over.csv"), "a\n" + "\n".repeat(4095));
        assertEquals(overTheBound + ": more than 4096 bytes; expected a file of at most 4096 bytes",
                assertThrows(IllegalArgumentException.class,
                        () -> TwinCli.csv(overTheBound, "rows file", 1, 4096, lines -> lines)).getMessage());
    }

    @Test
    void theBoundedReadKeepsAFileAtTheBoundAndFailsWhenTheParserAcceptsMore() throws IOException {
        Path file = Files.writeString(tmp.resolve("rows.csv"), "a\n1\n# note\n  \n2\n3\n4\n");
        assertEquals(List.of("a", "1", "#", "", "2", "3", "4"), TwinCli.csv(file, "rows file", 4, 1024, lines -> lines));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TwinCli.csv(file, "rows file", 2, 1024, lines -> lines));
        assertEquals(file + ": read up to data row 3, but the parser did not refuse more than 2 data rows",
                e.getMessage());
    }

    @Test
    void aRefusedRowOfTheHouseFilesNamesItsLineInTheFile() throws IOException {
        Path bills = Files.writeString(tmp.resolve("bills.csv"), "# bills\nzeitraum_von,zeitraum_bis\n2024-01-01,\n");
        assertEquals(new Result(2, "", "opentsx: " + bills + ": line 3: date missing\n"),
                run(HouseDemo::run, "--calibrate", bills.toString()));
        Path good = Files.write(tmp.resolve("good.csv"), resource("twin/beispiel/haus-abrechnungen-simuliert.csv"));
        Path temps = Files.writeString(tmp.resolve("temps.csv"), "# temperatures\ndatum,t_mittel_c\n2024-01-01\n");
        assertEquals(new Result(2, "", "opentsx: " + temps + ": line 3: no temperature; expected date,t_mean_c\n"),
                run(HouseDemo::run, "--calibrate", good.toString(), "--weather", temps.toString()));
    }

    @Test
    void aPlanOfAnotherScenarioIsAUsageErrorOfTwinAndBoat() throws IOException {
        Path house = Files.write(tmp.resolve("haus.yaml"), resource("twin/haus-erzgebirge.yaml"));
        assertEquals(new Result(2, "", "opentsx: " + house + ": plan haus-erzgebirge has no atom market (market), "
                + "offtake (h2-consumer), park (wind-park), ely (electrolyzer), handover (h2-handover), "
                + "battery (battery), buffer (h2-buffer), marketer (direct-marketer); expected an H2 chain plan such "
                + "as opentsx-twin/src/main/resources/twin/h2-chain.yaml\n"),
                run(H2ChainDemo::run, "--plan", house.toString()));
        assertEquals(new Result(2, "", "opentsx: " + house + ": plan haus-erzgebirge has no atom boat (boat-mode), "
                + "solar (solar), shore (shore-power), laptop1 (device), laptop2 (device), starlink (device), "
                + "station (power-station); expected a boat plan such as opentsx-twin/src/main/resources/twin/"
                + "boot-etap22.yaml\n"), run(BoatDemo::run, "--plan", house.toString()));
    }

    @Test
    void dataThatSpansMoreThanTenYearsIsAUsageError() throws IOException {
        Path boat = Files.writeString(tmp.resolve("boat.csv"), "time,soc_percent\n0001-01-01T00:00Z,80\n"
                + "9999-12-31T00:00Z,81\n");
        assertEquals(new Result(2, "", "opentsx: " + boat + ": line 3: time \"9999-12-31T00:00Z\" lies more than "
                + "3653 days after the earliest row (line 2); expected rows that span at most 3653 days (10 years)\n"),
                run(BoatDemo::run, "--data", boat.toString()));

        Path bills = Files.writeString(tmp.resolve("bills.csv"), "period_from,period_to,electricity_kwh\n"
                + "0001-01-01,0001-12-31,3000\n9999-01-01,9999-12-31,3000\n");
        assertEquals(new Result(2, "", "opentsx: " + bills + ": line 3: period_to 9999-12-31 lies 3653 days or more "
                + "after the earliest period_from (line 2); expected bills that span at most 3653 days (10 years)\n"),
                run(HouseDemo::run, "--calibrate", bills.toString()));

        Path good = Files.write(tmp.resolve("good.csv"), resource("twin/beispiel/haus-abrechnungen-simuliert.csv"));
        Path temps = Files.writeString(tmp.resolve("temps.csv"), "date,t_mean_c\n0001-01-01,3.5\n9999-12-31,4.5\n");
        assertEquals(new Result(2, "", "opentsx: " + temps + ": line 3: date 9999-12-31 lies 3653 days or more "
                + "after the earliest date (line 2); expected temperatures that span at most 3653 days (10 years)\n"),
                run(HouseDemo::run, "--calibrate", good.toString(), "--weather", temps.toString()));
    }

    @Test
    void dataThatNeedTooManyStepsOfThePlanAreAUsageError() throws IOException {
        Path boatPlan = Files.writeString(tmp.resolve("boat-1s.yaml"), new String(resource("twin/boot-etap22.yaml"),
                StandardCharsets.UTF_8).replaceFirst("(?m)^baseStep: .*$", "baseStep: PT1S"));
        Path boat = Files.writeString(tmp.resolve("ten.csv"), "time,soc_percent\n2026-01-01T00:00Z,80\n"
                + "2035-12-30T00:00Z,81\n");
        assertEquals(new Result(2, "", "opentsx: " + boat + ": line 3: time \"2035-12-30T00:00Z\" needs 315360001 "
                + "plan steps of PT1S from the earliest row (line 2); expected at most 35136 plan steps, from a longer "
                + "baseStep or rows that span less time\n"),
                run(BoatDemo::run, "--plan", boatPlan.toString(), "--data", boat.toString()));
        assertEquals(new Result(2, "", "opentsx: twin/beispiel/boot-logs-simuliert.csv: line 4037: time \"2026-07-17 "
                + "23:55\" needs 1209301 plan steps of PT1S from the earliest row (line 6); expected at most 35136 plan "
                + "steps, from a longer baseStep or rows that span less time\n"),
                run(BoatDemo::run, "--plan", boatPlan.toString(), "--simulated"));

        Path housePlan = Files.writeString(tmp.resolve("house-1s.yaml"), new String(resource("twin/haus-erzgebirge.yaml"),
                StandardCharsets.UTF_8).replaceFirst("(?m)^baseStep: .*$", "baseStep: PT1S"));
        Path bills = Files.writeString(tmp.resolve("bills.csv"), "period_from,period_to,electricity_kwh\n"
                + "2016-01-01,2020-12-31,15000\n2021-01-01,2025-12-31,15000\n");
        assertEquals(new Result(2, "", "opentsx: " + bills + ": period_from 2016-01-01 to period_to 2025-12-31 needs "
                + "315619200 plan steps of PT1S; expected at most 87673 plan steps, from a longer baseStep or bills "
                + "that span less time\n"),
                run(HouseDemo::run, "--plan", housePlan.toString(), "--calibrate", bills.toString()));
        assertEquals(new Result(2, "", "opentsx: twin/beispiel/haus-abrechnungen-simuliert.csv: period_from "
                + "2022-01-01 to period_to 2025-12-31 needs 126230400 plan steps of PT1S; expected at most 87673 plan "
                + "steps, from a longer baseStep or bills that span less time\n"),
                run(HouseDemo::run, "--plan", housePlan.toString(), "--simulated"));
    }

    @Test
    void anOptionWithoutEffectIsAUsageError() {
        String weatherNeedsBills = "opentsx: opentsx.house.weather: needs opentsx.house.bills; give --calibrate "
                + "<bills.csv> with --weather\n";
        assertEquals(new Result(2, "", weatherNeedsBills), run(HouseDemo::run, "--simulated", "--weather", "t.csv"));
        assertEquals(new Result(2, "", weatherNeedsBills), run(HouseDemo::run, "--weather", "t.csv"));
        assertEquals(new Result(2, "", "opentsx: opentsx.twin.simulated: cannot be combined with opentsx.house.bills; "
                + "give --calibrate <bills.csv> or --simulated, not both\n"),
                run(HouseDemo::run, "--simulated", "--calibrate", "b.csv"));
        assertEquals(new Result(2, "", "opentsx: opentsx.boat.zone: needs opentsx.boat.data; give --data <file.csv> "
                + "with --zone\n"), run(BoatDemo::run, "--zone", "UTC"));
        assertEquals(new Result(2, "", "opentsx: opentsx.boat.zone: needs opentsx.boat.data; give --data <file.csv> "
                + "with --zone\n"), run(BoatDemo::run, "--simulated", "--zone", "UTC"));
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

    @Test
    void boatCalibrationWithoutStateOfChargeNamesTheEnglishColumn() throws IOException {
        Path data = Files.writeString(tmp.resolve("no-soc.csv"), "time,solar_w\n2026-07-04T10:00+02:00,50\n");
        Result r = run(BoatDemo::run, "--data", data.toString(), "--calibrate");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("SoC RMSE model vs. measured n/a (no soc_percent)"), r.out());
        assertTrue(r.out().contains("   --calibrate needs the column soc_percent\n"), r.out());
    }
}
