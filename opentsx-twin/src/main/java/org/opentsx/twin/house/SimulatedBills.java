package org.opentsx.twin.house;

import org.opentsx.model.Micros;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Params;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.atoms.Weather;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * <b>Simulated</b> example bills for the house twin, so the calibration pipeline can be shown end to end before real
 * bills exist. The house model runs 2022–2025 with a hidden "true" parameter set that differs from the plan's
 * defaults ({@link #truth()}), on a synthetic Erzgebirge climate with year-to-year variation (a mild winter 2022/23,
 * a cold winter 2024/25). From the hourly result it writes what a homeowner would actually have, imperfections
 * included:
 * <ul>
 *   <li>annual electricity bills with non-calendar reading dates, one bill missing, one estimated reading (+4 %);</li>
 *   <li>oil deliveries on irregular dates in round litres (1500, 2000 or 2500 l, ordered when the tank falls below
 *       700–1200 l, delivered 4–14 days later); tank levels unknown until 2023, read by the driver at deliveries from 2024, and read monthly
 *       in 2025 (rounded to 10 l, ±25 l reading error, August reading missing);</li>
 *   <li>firewood per winter in Ster, rounded to 0.5;</li>
 *   <li>costs with price changes (2022 energy-price crisis);</li>
 *   <li>daily mean temperatures rounded to 0.1 °C, like station data.</li>
 * </ul>
 * Deterministic for a given seed. Run {@code main(outDir)} to regenerate the bundled files in
 * {@code twin/beispiel/}.
 */
public final class SimulatedBills {

    public static final long SEED = 2026;
    public static final LocalDate FROM = LocalDate.of(2022, 1, 1);
    public static final LocalDate TO = LocalDate.of(2025, 12, 31);
    public static final String BILLS = "twin/beispiel/haus-abrechnungen-simuliert.csv";
    public static final String TEMPERATURES = "twin/beispiel/haus-tagesmittel-simuliert.csv";

    /** Winter anomaly in K per heating year (July to June, keyed by the starting year), largest in mid-January. */
    private static final Map<Integer, Double> WINTER = Map.of(2021, 0.5, 2022, 1.8, 2023, 0.6, 2024, -2.0, 2025, -0.4);

    /** The generated files as CSV lines. */
    public record Data(List<String> bills, List<String> temperatures, Map<String, Double> truth) {
    }

    private SimulatedBills() {
    }

    /** The hidden parameters of the simulated house (the default plan has H 200, scales 1.0, eta 0.90). */
    public static Map<String, Double> truth() {
        Map<String, Double> t = new LinkedHashMap<>();
        t.put("building.lossWPerK", 230.0);   // +15 %
        t.put("dhw.scale", 0.8);
        t.put("appliances.scale", 1.1);
        t.put("boiler.eta", 0.86);            // not fitted: only H/eta is identifiable from bills
        return t;
    }

    public static Data generate() {
        return generate(HouseScenario.plan(), SEED);
    }

    public static Data generate(TwinPlan plan, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        int days = (int) ChronoUnit.DAYS.between(FROM, TO) + 1;

        // 1. weather: synthetic climate of the plan plus a winter anomaly per heating year, rounded like station data
        String weatherId = HouseScenario.id(plan, "weather");
        Weather climate = new Weather(weatherId, new Params(plan.atoms().stream()
                .filter(a -> a.id().equals(weatherId)).findFirst().orElseThrow().params()));
        List<String> temps = new ArrayList<>();
        temps.add("# SIMULIERT - keine Messwerte. Tagesmitteltemperatur eines fiktiven Standorts im Erzgebirge (~650 m),");
        temps.add("# erzeugt von org.opentsx.twin.house.SimulatedBills (Seed " + seed + "), Winter 2022/23 mild, 2024/25 kalt.");
        temps.add("datum,t_mittel_c");
        for (int d = 0; d < days; d++) {
            LocalDate day = FROM.plusDays(d);
            long t0 = Micros.of(day.atStartOfDay(HouseScenario.ZONE).toInstant());
            double sum = 0;
            for (int h = 0; h < 24; h++) {
                sum += climate.syntheticTemperature(t0 + h * 3_600_000_000L + 1_800_000_000L);
            }
            int heatingYear = day.getMonthValue() >= 7 ? day.getYear() : day.getYear() - 1;
            double w = Math.max(0, Math.cos(2 * Math.PI * (day.getDayOfYear() - 15) / 365.25));
            double mean = sum / 24 + WINTER.getOrDefault(heatingYear, 0.0) * w;
            temps.add(day + "," + String.format(Locale.ROOT, "%.1f", mean));
        }
        SeriesInputs weather = BillCalibration.dailyTemperatures(temps, weatherId, 5.0);

        // 2. the "real" house: hidden parameters, stove on its own schedule, tank bookkeeping done below
        Instant start = FROM.atStartOfDay(HouseScenario.ZONE).toInstant();
        Instant end = TO.plusDays(1).atStartOfDay(HouseScenario.ZONE).toInstant();
        int steps = (int) ((Micros.of(end) - Micros.of(start)) / plan.baseStepMicros());
        Map<String, Object> o = new LinkedHashMap<>(truth());
        o.put(HouseScenario.id(plan, "oil-tank") + ".level0L", 1e12);
        o.put(HouseScenario.id(plan, "oil-tank") + ".capacityL", 2e12);
        TwinPlan truePlan = HouseScenario.withParams(plan, o, start, steps);
        RunResult r = new TwinRunner(truePlan).run(new TwinRunner.RunSpec(Mode.SHADOW, weather, null, null, 0, null,
                null));
        double dtH = plan.baseStepMicros() / 3.6e9;
        double[] el = daily(r.series(HouseScenario.id(plan, "meter") + ".p_import_kw"), start, dtH, days, plan);
        double[] oil = daily(r.series(HouseScenario.id(plan, "oil-boiler") + ".fuel_lph"), start, dtH, days, plan);
        double[] wood = daily(r.series(HouseScenario.id(plan, "wood-stove") + ".wood_ster_ph"), start, dtH, days, plan);

        List<String[]> rows = new ArrayList<>();

        // 3. electricity: annual bills, reading dates drift; 2023/24 missing; 2024/25 estimated (+4 %)
        LocalDate[] readings = {LocalDate.of(2022, 3, 16), LocalDate.of(2023, 3, 15), LocalDate.of(2024, 3, 13),
                LocalDate.of(2025, 3, 18), TO.plusDays(1)};
        double[] price = {0.33, 0.43, 0.38, 0.36};
        for (int i = 0; i + 1 < readings.length; i++) {
            LocalDate a = readings[i];
            LocalDate b = readings[i + 1].minusDays(1);
            double kwh = sum(el, a, b);
            String note;
            if (i == 1) {
                rows.add(row(a, b, "", "", "", "", "", "", "SIMULIERT: Stromabrechnung 2023/24 nicht auffindbar"));
                continue;
            } else if (i == 2) {
                kwh *= 1.04;
                note = "SIMULIERT: Stromabrechnung, Zaehlerstand vom Versorger geschaetzt";
            } else if (i == 3) {
                note = "SIMULIERT: Zwischenablesung (Zaehlerfoto) zum Jahresende";
            } else {
                note = "SIMULIERT: Stromjahresabrechnung";
            }
            double days1 = ChronoUnit.DAYS.between(a, b) + 1;
            double cost = Math.round(kwh) * price[i] + 130 * days1 / 365;
            rows.add(row(a, b, fmt0(kwh), "", "", "", "", fmt2(cost), note));
        }

        // 4. oil: tank process with ordering threshold, irregular delivery dates, round litres
        double[] level = new double[days + 1];
        double l = 2200;
        int pending = -1;
        int order = 0;
        double threshold = 700 + 500 * rnd.nextDouble();
        List<int[]> deliveries = new ArrayList<>();   // {day, litres}
        for (int d = 0; d < days; d++) {
            level[d] = l;   // reading at the start of the day, before any filling
            if (d == pending) {
                double space = Math.floor((3900 - l) / 500) * 500;
                int litres = (int) Math.max(1000, Math.min(space, order));
                deliveries.add(new int[]{d, litres});
                l += litres;
                pending = -1;
                threshold = 700 + 500 * rnd.nextDouble();
            }
            l -= oil[d];
            if (l < threshold && pending < 0) {
                pending = d + 4 + rnd.nextInt(11);
                order = 1500 + 500 * rnd.nextInt(3);
            }
        }
        level[days] = l;
        LocalDate monthly = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < deliveries.size(); i++) {
            int d0 = deliveries.get(i)[0];
            LocalDate a = FROM.plusDays(d0);
            if (!a.isBefore(monthly)) {
                break;
            }
            int d1 = i + 1 < deliveries.size() ? deliveries.get(i + 1)[0] : days;
            LocalDate next = FROM.plusDays(d1);
            LocalDate b = (next.isAfter(monthly) ? monthly : next).minusDays(1);
            double oilPrice = oilPrice(a, rnd);
            String litres = Integer.toString(deliveries.get(i)[1]);
            String cost = fmt2(deliveries.get(i)[1] * oilPrice);
            if (a.getYear() < 2024) {
                rows.add(row(a, b, "", litres, "", "", "", cost,
                        "SIMULIERT: Heizoel-Lieferung, Tankstand nicht notiert"));
            } else {
                int endDay = (int) ChronoUnit.DAYS.between(FROM, b) + 1;
                rows.add(row(a, b, "", litres, reading(level[d0], rnd), reading(level[endDay], rnd), "", cost,
                        "SIMULIERT: Heizoel-Lieferung, Tankstand vor Befuellung laut Fahrer"
                                + (b.plusDays(1).equals(monthly) ? "; Ende = Peilstab 01.01.2025" : "")));
            }
        }
        // monthly gauge readings 2025, August reading missing (holiday)
        LocalDate m = monthly;
        String startReading = null;
        while (!m.isAfter(TO)) {
            LocalDate next = m.plusMonths(1);
            boolean skipped = next.getMonthValue() == 8;
            if (skipped) {
                next = next.plusMonths(1);
            }
            int da = (int) ChronoUnit.DAYS.between(FROM, m);
            int db = (int) ChronoUnit.DAYS.between(FROM, next);
            double delivered = 0;
            double cost = 0;
            for (int[] dv : deliveries) {
                if (dv[0] >= da && dv[0] < db) {
                    delivered += dv[1];
                    cost += dv[1] * oilPrice(FROM.plusDays(dv[0]), rnd);
                }
            }
            String s0 = startReading != null ? startReading : reading(level[da], rnd);
            String s1 = reading(level[Math.min(db, days)], rnd);
            rows.add(row(m, next.minusDays(1), "", fmt0(delivered), s0, s1, "", cost > 0 ? fmt2(cost) : "",
                    "SIMULIERT: Peilstab am Monatsersten" + (delivered > 0 ? " + Lieferung" : "")
                            + (skipped ? "; Ablesung 01.08. fehlt (Urlaub)" : "")));
            startReading = s1;
            m = next;
        }

        // 5. wood per winter, rounded to 0.5 Ster
        LocalDate[][] winters = {
                {FROM, LocalDate.of(2022, 5, 31)},
                {LocalDate.of(2022, 9, 1), LocalDate.of(2023, 5, 31)},
                {LocalDate.of(2023, 9, 1), LocalDate.of(2024, 5, 31)},
                {LocalDate.of(2024, 9, 1), LocalDate.of(2025, 5, 31)},
                {LocalDate.of(2025, 9, 1), TO}};
        for (LocalDate[] w : winters) {
            double ster = Math.round(sum(wood, w[0], w[1]) * 2) / 2.0;
            double woodPrice = 80 + 5 * (w[0].getYear() - 2022);
            rows.add(row(w[0], w[1], "", "", "", "", fmt1(ster), fmt2(ster * woodPrice),
                    "SIMULIERT: Holz verbrannt (Fichte; geschaetzt aus Stapel, auf 0,5 Ster gerundet)"));
        }

        rows.sort((x, y) -> x[0].compareTo(y[0]) != 0 ? x[0].compareTo(y[0]) : x[1].compareTo(y[1]));
        List<String> bills = new ArrayList<>();
        bills.add("# SIMULIERT - erfundene Abrechnungen eines fiktiven Hauses, KEINE echten Daten.");
        bills.add("# Erzeugt von org.opentsx.twin.house.SimulatedBills (Seed " + seed + ") mit verdeckten Parametern "
                + truth() + ".");
        bills.add("# Kalibrierung: bin/opentsx house --simulated");
        bills.add(String.join(",", BillCalibration.COLUMNS));
        for (String[] row : rows) {
            bills.add(String.join(",", row));
        }
        return new Data(bills, temps, truth());
    }

    /** The bundled simulated bills (classpath resource). */
    public static List<String> bundledBills() {
        return resource(BILLS);
    }

    /** The bundled simulated daily mean temperatures (classpath resource). */
    public static List<String> bundledTemperatures() {
        return resource(TEMPERATURES);
    }

    private static List<String> resource(String name) {
        try (InputStream in = SimulatedBills.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Hourly flow series to daily sums by local date. */
    private static double[] daily(double[] hourly, Instant start, double dtH, int days, TwinPlan plan) {
        double[] out = new double[days];
        for (int k = 0; k < hourly.length; k++) {
            LocalDate day = start.plusSeconds((long) (k * dtH * 3600)).atZone(HouseScenario.ZONE).toLocalDate();
            int d = (int) ChronoUnit.DAYS.between(FROM, day);
            if (d >= 0 && d < days) {
                out[d] += hourly[k] * dtH;
            }
        }
        return out;
    }

    private static double sum(double[] daily, LocalDate a, LocalDate b) {
        double s = 0;
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
            s += daily[(int) ChronoUnit.DAYS.between(FROM, d)];
        }
        return s;
    }

    /** Gauge reading: ±25 l error, rounded to 10 l. */
    private static String reading(double litres, SplittableRandom rnd) {
        return fmt0(Math.round((litres + (rnd.nextDouble() * 2 - 1) * 25) / 10) * 10.0);
    }

    /** Heating-oil price in EUR/l: 2022 crisis peak, then easing; small noise per delivery. */
    private static double oilPrice(LocalDate d, SplittableRandom rnd) {
        double base = switch (d.getYear()) {
            case 2022 -> d.getMonthValue() < 3 ? 0.95 : 1.45;
            case 2023 -> 1.08;
            case 2024 -> 1.00;
            default -> 0.96;
        };
        return base + (rnd.nextDouble() - 0.5) * 0.06;
    }

    private static String[] row(LocalDate a, LocalDate b, String el, String oil, String start, String end,
                                String wood, String cost, String note) {
        return new String[]{a.toString(), b.toString(), el, oil, start, end, wood, cost, "\"" + note + "\""};
    }

    private static String fmt0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Regenerates the bundled files: {@code SimulatedBills <resources dir>} (e.g. opentsx-twin/src/main/resources). */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "opentsx-twin/src/main/resources");
        Data d = generate();
        Files.createDirectories(dir.resolve(BILLS).getParent());
        Files.write(dir.resolve(BILLS), d.bills(), StandardCharsets.UTF_8);
        Files.write(dir.resolve(TEMPERATURES), d.temperatures(), StandardCharsets.UTF_8);
        System.out.println("wrote " + dir.resolve(BILLS) + " and " + dir.resolve(TEMPERATURES));
    }
}
