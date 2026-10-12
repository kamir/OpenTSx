package org.opentsx.dwd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code bin/opentsx dwd ...} with {@code --offline} files (no network). */
class DwdCliTest {

    @TempDir
    Path dir;

    private String run(String... args) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int rc = new DwdCli(new PrintStream(bos, true, StandardCharsets.UTF_8)).run(args);
        assertEquals(0, rc, bos.toString(StandardCharsets.UTF_8));
        return bos.toString(StandardCharsets.UTF_8);
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
    void usageErrors() throws IOException {
        DwdCli cli = new DwdCli(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(1, cli.run(new String[0]));
        assertEquals(1, new DwdCli(System.out).run(new String[]{"nope"}));
        assertEquals(2, new DwdCli(System.out).run(new String[]{"stations"}));   // --near missing
    }
}
