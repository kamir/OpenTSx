package org.opentsx.twin.boat;

import org.opentsx.model.Micros;
import org.opentsx.twin.Intervention;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.Twin;
import org.opentsx.twin.TwinPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * SIMULATED operating data of the boat, for demonstrating import and calibration until real exports exist.
 * <p>
 * The boat model runs with hidden "true" parameters that differ from the plan defaults ({@link #TRUE_DERATE},
 * {@link #TRUE_STARLINK_SCALE}, {@link #TRUE_LAPTOPS_SCALE}), over the 14-day trip {@link #TRIP}, at 5-minute steps,
 * with a cloudy spell ({@link #CLEARNESS}) and one day with Starlink switched off ({@link #STARLINK_OFF_DAY}). Its
 * output is written like a power-station app export joined with a logbook and a nearby irradiance station:
 * local time without offset (Europe/Berlin), SoC as whole percent, sensor noise on power and irradiance, irradiance
 * as hourly means, no app data while nobody is on board, a few nights and short spells without Bluetooth connection.
 * Everything is deterministic for a given seed ({@link java.util.Random} is specified to be reproducible).
 * <p>
 * Usage: {@code java -cp ... org.opentsx.twin.boat.SimulatedLogs <out.csv> [seed]}
 */
public final class SimulatedLogs {

    public static final long SEED = 20260704L;
    /** U away, H harbour, S sail to a harbour, A sail to an anchorage, X stay at anchor (from Sat 2026-07-04). */
    public static final String TRIP = "UHAXSHSAXXSHSU";
    public static final double TRUE_DERATE = 0.62;
    public static final double TRUE_STARLINK_SCALE = 1.25;
    public static final double TRUE_LAPTOPS_SCALE = 1.3;
    /** Day index (0 = first day) on which Starlink stayed off; recorded in the logbook as {@code starlink_an = 0}. */
    public static final int STARLINK_OFF_DAY = 4;
    /** True daily clearness index; days 8-10 are a cloudy spell (at anchor). */
    public static final List<Double> CLEARNESS = List.of(0.9, 0.85, 0.75, 0.9, 0.6, 0.8, 0.95, 0.7, 0.3, 0.25, 0.35,
            0.7, 0.85, 0.9);
    public static final String RESOURCE = "twin/beispiel/boot-logs-simuliert.csv";
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    public static final String HEADER = "zeit,modus,landstrom,starlink_an,soc_prozent,solar_w,last_w,einstrahlung_wm2,notiz";

    private static final int STEPS_PER_DAY = 288;
    private static final String[] MODE_NAMES = {"hafen", "segeln", "anker", "unbemannt"};

    private SimulatedLogs() {
    }

    /** The template plan with the true weather and itinerary, at 5-minute steps. */
    public static TwinPlan truthPlan(TwinPlan template) {
        List<TwinPlan.AtomSpec> atoms = new ArrayList<>();
        for (TwinPlan.AtomSpec a : template.atoms()) {
            Map<String, Object> p = new LinkedHashMap<>(a.params());
            if (a.type().equals("solar")) {
                p.put("clearness", CLEARNESS);
            } else if (a.type().equals("boat-mode")) {
                p.put("itinerary", TRIP);
            }
            atoms.add(new TwinPlan.AtomSpec(a.id(), a.type(), p, a.policy()));
        }
        return new TwinPlan(template.twin(), template.start(), Duration.ofMinutes(5), CLEARNESS.size() * STEPS_PER_DAY,
                atoms, template.wiring());
    }

    /** The hidden truth as interventions: parameters from the start, Starlink off for one day. */
    public static List<Intervention> truthInterventions(TwinPlan plan) {
        long t0 = plan.startMicros();
        long day = BoatClock.DAY;
        String why = "simulated truth";
        return List.of(
                new Intervention(t0, "solar", "derate", TRUE_DERATE, "simulation", why),
                new Intervention(t0, "starlink", "scale", TRUE_STARLINK_SCALE, "simulation", why),
                new Intervention(t0, "laptop1", "scale", TRUE_LAPTOPS_SCALE, "simulation", why),
                new Intervention(t0, "laptop2", "scale", TRUE_LAPTOPS_SCALE, "simulation", why),
                new Intervention(t0 + STARLINK_OFF_DAY * day, "starlink", "on", 0.0, "simulation", "Starlink off"),
                new Intervention(t0 + (STARLINK_OFF_DAY + 1) * day, "starlink", "on", null, "simulation", "Starlink on"));
    }

    /** The true run (5-minute steps) the logs are cut from. */
    public static RunResult truthRun(TwinPlan template) {
        TwinPlan p = truthPlan(template);
        return new Twin(p).shadow(new SeriesInputs(), truthInterventions(p));
    }

    /** The CSV lines (comments, header, rows). */
    public static List<String> csv(TwinPlan template, long seed) {
        RunResult r = truthRun(template);
        Random rnd = new Random(seed);
        int n = r.size();
        int days = n / STEPS_PER_DAY;

        // where the app had no connection: nobody on board, three nights, four short spells
        boolean[] gap = new boolean[n];
        String[] note = new String[n];
        List<Integer> manned = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            if (TRIP.charAt(d) == 'U') {
                java.util.Arrays.fill(gap, d * STEPS_PER_DAY, (d + 1) * STEPS_PER_DAY, true);
                note[d * STEPS_PER_DAY] = "niemand an Bord, keine App-Daten";
            } else {
                manned.add(d);
            }
        }
        for (int k = 0; k < 3; k++) {
            int d = manned.get(rnd.nextInt(manned.size()));
            mark(gap, note, d * STEPS_PER_DAY, d * STEPS_PER_DAY + 78, "App nachts nicht verbunden");
        }
        for (int k = 0; k < 4; k++) {
            int d = manned.get(rnd.nextInt(manned.size()));
            int from = d * STEPS_PER_DAY + (8 + rnd.nextInt(12)) * 12 + rnd.nextInt(12);
            mark(gap, note, from, from + 6 + rnd.nextInt(19), "Bluetooth ausser Reichweite");
        }
        note[STARLINK_OFF_DAY * STEPS_PER_DAY] = append(note[STARLINK_OFF_DAY * STEPS_PER_DAY], "Starlink heute aus");
        for (int d = 1; d < days; d++) {
            if (CLEARNESS.get(d) < 0.4 && CLEARNESS.get(d - 1) >= 0.4) {
                note[d * STEPS_PER_DAY] = append(note[d * STEPS_PER_DAY], "bedeckt, Regen");
            }
        }

        // irradiance: hourly means of the true value with 5 % noise (a nearby weather station)
        double[] ghi = r.series("solar.ghi_wm2");
        double[] hourly = new double[n];
        for (int h = 0; h * 12 < n; h++) {
            double sum = 0;
            int cnt = 0;
            for (int i = h * 12; i < Math.min(n, h * 12 + 12); i++) {
                sum += ghi[i];
                cnt++;
            }
            double v = sum / cnt * (1 + 0.05 * rnd.nextGaussian());
            for (int i = h * 12; i < Math.min(n, h * 12 + 12); i++) {
                hourly[i] = Math.max(0, v);
            }
        }

        double[] mode = r.series("boat.mode");
        double[] shore = r.series("shore.connected");
        double[] soc = r.series("station.soc");
        double[] pv = r.series("station.solar_used_w");
        double[] load = r.series("station.p_load_w");
        double[] unmet = r.series("station.p_unmet_w");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        List<String> out = new ArrayList<>();
        out.add("# SIMULIERT - KEINE MESSUNG. Erzeugt von org.opentsx.twin.boat.SimulatedLogs (Seed " + seed + ").");
        out.add("# Verdeckte wahre Parameter: solar.derate " + TRUE_DERATE + ", Starlink-Faktor " + TRUE_STARLINK_SCALE
                + ", Laptop-Faktor " + TRUE_LAPTOPS_SCALE + "; Reiseplan " + TRIP + "; Starlink aus an Tag "
                + STARLINK_OFF_DAY + "; bedeckt an den Tagen 8-10.");
        out.add("# Form wie ein App-Export der Powerstation (5 min) mit Logbuch und Einstrahlung einer Wetterstation;");
        out.add("# Ortszeit Europe/Berlin ohne Offset, SoC ganzzahlig, Rauschen, Luecken. Siehe README-boot.md.");
        out.add(HEADER);
        for (int i = 0; i < n; i++) {
            int d = i / STEPS_PER_DAY;
            double pvNoisy = noisy(pv[i], 0.03, 1.0, rnd);
            double served = load[i] - unmet[i];
            double loadNoisy = noisy(served, 0.02, 1.0, rnd);
            String time = Micros.toInstant(r.timeMicros(i)).atZone(ZONE).format(fmt);
            String nt = "SIMULIERT" + (note[i] == null ? "" : ": " + note[i]);
            out.add(String.join(",",
                    time,
                    MODE_NAMES[(int) mode[i]],
                    String.valueOf((int) shore[i]),
                    d == STARLINK_OFF_DAY ? "0" : "1",
                    gap[i] ? "" : String.valueOf(Math.round(100 * soc[i])),
                    gap[i] ? "" : String.valueOf(Math.round(pvNoisy)),
                    gap[i] ? "" : String.valueOf(Math.round(loadNoisy)),
                    String.valueOf(Math.round(hourly[i])),
                    i == 0 || note[i] != null ? nt : ""));
        }
        return out;
    }

    private static double noisy(double v, double rel, double abs, Random rnd) {
        double a = rnd.nextGaussian();
        double b = rnd.nextGaussian();
        return v <= 0 ? 0 : Math.max(0, v * (1 + rel * a) + abs * b);
    }

    private static void mark(boolean[] gap, String[] note, int from, int to, String why) {
        int end = Math.min(gap.length, to);
        if (from >= end) {
            return;
        }
        java.util.Arrays.fill(gap, from, end, true);
        note[from] = append(note[from], why);
    }

    private static String append(String a, String b) {
        return a == null ? b : a + "; " + b;
    }

    /** Hidden true parameters, named like the calibration knobs of {@code BoatDemo.defaultKnobs()}. */
    public static Map<String, Double> truth() {
        Map<String, Double> m = new HashMap<>();
        m.put("solar.derate", TRUE_DERATE);
        m.put("laptops.scale", TRUE_LAPTOPS_SCALE);
        m.put("starlink.scale", TRUE_STARLINK_SCALE);
        return m;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: SimulatedLogs <out.csv> [seed]");
            System.exit(1);
        }
        long seed = args.length > 1 ? Long.parseLong(args[1]) : SEED;
        try {
            Files.write(Path.of(args[0]), csv(TwinPlan.resource("twin/boot-etap22.yaml"), seed),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(String.format(Locale.ROOT, "wrote %s (seed %d)", args[0], seed));
    }
}
