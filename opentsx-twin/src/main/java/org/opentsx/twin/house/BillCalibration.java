package org.opentsx.twin.house;

import org.opentsx.model.Micros;
import org.opentsx.twin.Inputs;
import org.opentsx.twin.Mode;
import org.opentsx.twin.Origin;
import org.opentsx.twin.Params;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.Value;
import org.opentsx.twin.atoms.Weather;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Calibrates the house twin against bills (concept 08 §7). Each bill row covers a period and carries whatever is
 * known: electricity kWh, heating oil delivered (and tank levels at start/end, if read), firewood in Ster. The
 * twin is run over the whole bill horizon; per period it yields modelled electricity and oil consumption. A small
 * parameter vector (default: building heat-loss coefficient H, hot-water scale, appliance scale) is fitted by
 * <b>deterministic least squares</b> (Levenberg–Marquardt; forward differences, then Broyden updates) on the
 * period totals.
 * <p>
 * Inputs to the runs: the weather (synthetic unless daily temperatures are bound, see
 * {@link #dailyTemperatures(Path, String, double)}) and the firewood from the bills (REPORTED), so the model has to
 * explain only the oil on top of the wood actually burned. Fixed (not fitted) parameters stay as in the plan, in
 * particular the boiler efficiency: from totals only {@code H / η} is identified, not both.
 * <p>
 * Honest limits are reported, not hidden: number of periods vs. parameters, condition number and parameter
 * correlation of the sensitivity matrix, oil rows where deliveries had to stand in for consumption, and the fit
 * quality per quantity as CV(RMSE) and NMBE (ASHRAE Guideline 14 uses 15 % and ±5 % for monthly data).
 */
public final class BillCalibration {

    /** Columns of the bill CSV, in this order (header required; separator ',' or ';'). */
    public static final List<String> COLUMNS = List.of("zeitraum_von", "zeitraum_bis", "strom_kwh", "heizoel_liter",
            "tank_start_liter", "tank_ende_liter", "holz_ster", "kosten_eur", "notiz");

    /**
     * One bill row. Dates are local calendar days, both inclusive. Unknown values are NaN.
     *
     * @param oilDeliveredL heating oil delivered in the period (litres)
     * @param tankStartL    tank level at the start of the period (litres), NaN if not read
     * @param tankEndL      tank level at the end of the period (litres), NaN if not read
     */
    public record Bill(LocalDate from, LocalDate to, double electricityKwh, double oilDeliveredL, double tankStartL,
                       double tankEndL, double woodSter, double costEur, String note) {

        public Bill {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException("bill needs zeitraum_von <= zeitraum_bis: " + from + " " + to);
            }
            note = note == null ? "" : note;
        }

        public long days() {
            return ChronoUnit.DAYS.between(from, to) + 1;
        }

        public boolean hasElectricity() {
            return !Double.isNaN(electricityKwh);
        }

        public boolean hasOil() {
            return !Double.isNaN(oilConsumedL());
        }

        /** Both tank levels are known, so consumption = delivered + start − end is exact. */
        public boolean oilFromLevels() {
            return !Double.isNaN(tankStartL) && !Double.isNaN(tankEndL);
        }

        /** Oil consumed: from tank levels if known, else the delivery as a proxy (NaN if nothing is known). */
        public double oilConsumedL() {
            double delivered = Double.isNaN(oilDeliveredL) ? 0 : oilDeliveredL;
            if (oilFromLevels()) {
                return delivered + tankStartL - tankEndL;
            }
            return oilDeliveredL;
        }
    }

    /** A parameter to fit, {@code "atom.param"} in the plan, with start value and bounds. */
    public record Parameter(String key, double initial, double min, double max) {
    }

    /** Observed and modelled value of one quantity in one period. {@code proxy}: oil delivery used as consumption. */
    public record Period(Bill bill, String quantity, double observed, double modelled, boolean proxy) {
        public double residual() {
            return observed - modelled;
        }
    }

    /**
     * Result of a fit.
     *
     * @param relStdErr       relative standard error per parameter (NaN when not estimable)
     * @param conditionNumber of the scaled sensitivity matrix JᵀJ (large = parameters not separable)
     * @param maxCorrelation  largest absolute correlation between two fitted parameters
     */
    public record Result(Map<String, Double> parameters, Map<String, Double> relStdErr, List<Period> periods,
                         Map<String, Double> cvRmse, Map<String, Double> nmbe, double conditionNumber,
                         double maxCorrelation, boolean identifiable, int runs, List<String> warnings,
                         TwinPlan calibratedPlan) {
    }

    private final TwinPlan base;
    private final SeriesInputs weather;
    private final String meter;
    private final String boiler;
    private final String stove;
    private final String tank;
    private final double kwhPerSter;
    private final String weatherAtom;
    private final Weather climate;
    private final AtomicInteger runs = new AtomicInteger();
    private final Map<String, double[][]> cache = new ConcurrentHashMap<>();

    /** Calibration on the given plan with the synthetic climate of its weather atom. */
    public BillCalibration(TwinPlan base) {
        this(base, new SeriesInputs());
    }

    /** Calibration with bound weather, e.g. {@link #dailyTemperatures(Path, String, double)}. */
    public BillCalibration(TwinPlan base, SeriesInputs weather) {
        this.base = base;
        this.weather = weather == null ? new SeriesInputs() : weather;
        meter = HouseScenario.id(base, "meter");
        boiler = HouseScenario.id(base, "oil-boiler");
        stove = HouseScenario.id(base, "wood-stove");
        tank = HouseScenario.id(base, "oil-tank");
        kwhPerSter = HouseScenario.param(base, stove, "kwhPerSter", 1500);
        weatherAtom = HouseScenario.id(base, "weather");
        TwinPlan.AtomSpec w = base.atoms().stream().filter(a -> a.id().equals(weatherAtom)).findFirst().orElseThrow();
        climate = new Weather(weatherAtom, new Params(w.params()));
    }

    /** The default free parameters: H of the building, hot-water scale, appliance scale. */
    public static List<Parameter> defaultParameters(TwinPlan plan) {
        String b = HouseScenario.id(plan, "building");
        String d = HouseScenario.id(plan, "dhw");
        String a = HouseScenario.id(plan, "appliances");
        return List.of(
                new Parameter(b + ".lossWPerK", HouseScenario.param(plan, b, "lossWPerK", 250), 40, 1500),
                new Parameter(d + ".scale", HouseScenario.param(plan, d, "scale", 1), 0.1, 5),
                new Parameter(a + ".scale", HouseScenario.param(plan, a, "scale", 1), 0.1, 5));
    }

    // ------------------------------------------------------------------------------------------------ CSV

    public static List<Bill> read(Path csv) {
        try {
            return parse(Files.readAllLines(csv, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses bill rows. Header with the {@link #COLUMNS} names (any order, missing optional columns allowed).
     * Separator ',' (decimal point) or ';' (decimal comma allowed, as exported by German spreadsheets). Dates as
     * {@code 2024-01-31} or {@code 31.01.2024}. Empty cells are unknown. Lines starting with '#' are skipped.
     */
    public static List<Bill> parse(List<String> lines) {
        List<String> rows = lines.stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no header");
        }
        char sep = rows.get(0).contains(";") ? ';' : ',';
        List<String> header = split(rows.get(0).replace("﻿", ""), sep).stream()
                .map(s -> s.strip().toLowerCase(Locale.ROOT)).toList();
        for (String required : List.of("zeitraum_von", "zeitraum_bis")) {
            if (!header.contains(required)) {
                throw new IllegalArgumentException("missing column " + required + "; expected " + COLUMNS);
            }
        }
        for (String h : header) {
            if (!COLUMNS.contains(h)) {
                throw new IllegalArgumentException("unknown column '" + h + "'; expected " + COLUMNS);
            }
        }
        List<Bill> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = split(rows.get(i), sep);
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                m.put(header.get(c), c < cells.size() ? cells.get(c).strip() : "");
            }
            try {
                out.add(new Bill(date(m.get("zeitraum_von")), date(m.get("zeitraum_bis")),
                        num(m.get("strom_kwh"), sep), num(m.get("heizoel_liter"), sep),
                        num(m.get("tank_start_liter"), sep), num(m.get("tank_ende_liter"), sep),
                        num(m.get("holz_ster"), sep), num(m.get("kosten_eur"), sep), m.getOrDefault("notiz", "")));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static List<String> split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static LocalDate date(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("date missing");
        }
        return s.contains(".") ? LocalDate.parse(s, DateTimeFormatter.ofPattern("d.M.yyyy")) : LocalDate.parse(s);
    }

    private static double num(String s, char sep) {
        if (s == null || s.isEmpty()) {
            return Double.NaN;
        }
        String t = s.replace(" ", "");
        if (sep == ';') {
            t = t.replace(".", "").replace(',', '.');
        }
        return Double.parseDouble(t);
    }

    // ------------------------------------------------------------------------------------------------ weather

    /**
     * Daily mean temperatures from a CSV {@code datum,t_mittel_c} (e.g. DWD climate data, column TMK) as an hourly
     * binding of {@code <weatherAtom>.t_out_meas_c} (MEASURED), with a synthetic daily cycle of {@code dailyAmpC}
     * around each daily mean (minimum about 04:00). Days without a value fall back to the synthetic climate.
     */
    public static SeriesInputs dailyTemperatures(Path csv, String weatherAtom, double dailyAmpC) {
        Map<LocalDate, Double> days = new TreeMap<>();
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            char sep = lines.isEmpty() || !lines.get(0).contains(";") ? ',' : ';';
            for (String l : lines.subList(1, lines.size())) {
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                List<String> c = split(l.strip(), sep);
                double v = num(c.get(1).strip(), sep);
                if (!Double.isNaN(v) && v > -90) {   // DWD marks missing values with -999
                    days.put(date(c.get(0).strip()), v);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (days.isEmpty()) {
            throw new IllegalArgumentException("no temperatures in " + csv);
        }
        LocalDate first = days.keySet().iterator().next();
        LocalDate last = ((TreeMap<LocalDate, Double>) days).lastKey();
        Instant t0 = first.atStartOfDay(HouseScenario.ZONE).toInstant();
        int hours = (int) Duration.between(t0, last.plusDays(1).atStartOfDay(HouseScenario.ZONE).toInstant()).toHours();
        double[] v = new double[hours];
        for (int h = 0; h < hours; h++) {
            var local = t0.plusSeconds(3600L * h).atZone(HouseScenario.ZONE);
            Double mean = days.get(local.toLocalDate());
            v[h] = mean == null ? Double.NaN
                    : mean - dailyAmpC / 2 * Math.cos(2 * Math.PI * (local.getHour() + 0.5 - 4) / 24);
        }
        return new SeriesInputs().bind(weatherAtom + ".t_out_meas_c", Micros.of(t0), 3_600_000_000L, v, Origin.MEASURED);
    }

    // ------------------------------------------------------------------------------------------------ model

    /** Modelled values per bill row: {@code [electricity kWh, oil litres consumed]} with the given parameters. */
    public double[][] model(List<Bill> bills, Map<String, Double> params) {
        Instant start = bills.stream().map(Bill::from).min(LocalDate::compareTo).orElseThrow()
                .atStartOfDay(HouseScenario.ZONE).toInstant();
        Instant end = bills.stream().map(Bill::to).max(LocalDate::compareTo).orElseThrow().plusDays(1)
                .atStartOfDay(HouseScenario.ZONE).toInstant();
        long stepMicros = base.baseStepMicros();
        int steps = (int) Math.ceil((Micros.of(end) - Micros.of(start)) / (double) stepMicros);

        Map<String, Object> overrides = new LinkedHashMap<>(params);
        // the tank never runs empty in a calibration run: deliveries are not simulated, only consumption
        overrides.put(tank + ".level0L", 1e12);
        overrides.put(tank + ".capacityL", 2e12);
        TwinPlan plan = HouseScenario.withParams(base, overrides, start, steps);

        Inputs inputs = weather.with(woodBinding(bills, start, end));
        runs.incrementAndGet();
        RunResult r = new TwinRunner(plan).run(new TwinRunner.RunSpec(Mode.SHADOW, inputs, null, null, 0, null, null));
        double[] el = r.series(meter + ".p_import_kw");
        double[] oil = r.series(boiler + ".fuel_lph");
        double dtH = stepMicros / 3.6e9;
        long t0 = Micros.of(start);

        double[][] out = new double[bills.size()][2];
        for (int i = 0; i < bills.size(); i++) {
            Bill b = bills.get(i);
            int from = (int) ((Micros.of(b.from().atStartOfDay(HouseScenario.ZONE).toInstant()) - t0) / stepMicros);
            int to = (int) ((Micros.of(b.to().plusDays(1).atStartOfDay(HouseScenario.ZONE).toInstant()) - t0) / stepMicros);
            double e = 0;
            double o = 0;
            for (int k = from; k < Math.min(to, steps); k++) {
                e += el[k] * dtH;
                o += oil[k] * dtH;
            }
            out[i][0] = e;
            out[i][1] = o;
        }
        return out;
    }

    /**
     * Firewood from the bills as kWh per day for the stove (REPORTED); NaN where no bill gives wood. Within a period
     * the wood is spread over the days in proportion to their heating degrees {@code max(0, 15 °C − daily mean)}
     * (uniformly if the period has none), since nobody fires the stove in July.
     */
    private SeriesInputs woodBinding(List<Bill> bills, Instant start, Instant end) {
        LocalDate first = start.atZone(HouseScenario.ZONE).toLocalDate();
        int days = (int) ChronoUnit.DAYS.between(first, end.atZone(HouseScenario.ZONE).toLocalDate());
        double[] v = new double[days];
        Arrays.fill(v, Double.NaN);
        boolean any = false;
        for (Bill b : bills) {
            if (Double.isNaN(b.woodSter())) {
                continue;
            }
            any = true;
            double[] w = new double[(int) b.days()];
            double sum = 0;
            for (int i = 0; i < w.length; i++) {
                w[i] = Math.max(0, 15 - dailyMeanTemperature(b.from().plusDays(i)));
                sum += w[i];
            }
            for (int i = 0; i < w.length; i++) {
                double share = sum > 0 ? w[i] / sum : 1.0 / w.length;
                int d = (int) ChronoUnit.DAYS.between(first, b.from().plusDays(i));
                v[d] = (Double.isNaN(v[d]) ? 0 : v[d]) + b.woodSter() * kwhPerSter * share;
            }
        }
        if (!any) {
            return new SeriesInputs();
        }
        // bound in daily steps from the start of the horizon (DST shifts the day boundary by an hour in summer)
        return new SeriesInputs().bind(stove + ".wood_kwh_per_day", Micros.of(start), 86_400_000_000L, v,
                Origin.REPORTED);
    }

    /** Daily mean outdoor temperature: from the bound weather if present, else the synthetic climate. */
    private double dailyMeanTemperature(LocalDate day) {
        long t0 = Micros.of(day.atStartOfDay(HouseScenario.ZONE).toInstant());
        double sum = 0;
        for (int h = 0; h < 24; h++) {
            long t = t0 + h * 3_600_000_000L;
            sum += weather.value(weatherAtom + ".t_out_meas_c", t).map(Value::v)
                    .orElseGet(() -> climate.syntheticTemperature(t + 1_800_000_000L));
        }
        return sum / 24;
    }

    /**
     * Bills as the model would produce them: same periods, electricity where the template has electricity, oil
     * (as consumption from tank levels) where the template has oil, wood as in the template. For identifiability
     * tests and as a sanity check of a template.
     */
    public List<Bill> synthesize(List<Bill> template, Map<String, Double> params) {
        double[][] m = model(template, params);
        List<Bill> out = new ArrayList<>();
        for (int i = 0; i < template.size(); i++) {
            Bill b = template.get(i);
            out.add(new Bill(b.from(), b.to(), b.hasElectricity() ? m[i][0] : Double.NaN,
                    b.hasOil() ? 0 : Double.NaN, b.hasOil() ? 1000 + m[i][1] : Double.NaN,
                    b.hasOil() ? 1000 : Double.NaN, b.woodSter(), Double.NaN, "synthetic"));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ fit

    /** Fits the default parameters. */
    public Result fit(List<Bill> bills) {
        return fit(bills, defaultParameters(base));
    }

    /** Fits {@code free} to the bills by Levenberg–Marquardt on normalized period residuals. */
    public Result fit(List<Bill> bills, List<Parameter> free) {
        if (bills.isEmpty()) {
            throw new IllegalArgumentException("no bills");
        }
        runs.set(0);
        cache.clear();
        List<String> warnings = new ArrayList<>();
        double elNorm = mean(bills.stream().filter(Bill::hasElectricity).mapToDouble(Bill::electricityKwh).toArray());
        double oilNorm = mean(bills.stream().filter(Bill::hasOil).mapToDouble(Bill::oilConsumedL).toArray());
        int n = (int) (bills.stream().filter(Bill::hasElectricity).count() + bills.stream().filter(Bill::hasOil).count());
        int p = free.size();

        double[] theta = free.stream().mapToDouble(Parameter::initial).toArray();
        double[] r = residuals(bills, free, theta, elNorm, oilNorm);
        double cost = dot(r, r);
        double lambda = 1e-3;
        // Jacobian by forward differences once, then Broyden rank-1 updates after each accepted step (the period
        // totals are nearly linear in the parameters, so this saves most of the twin runs); a fresh difference
        // Jacobian is taken when a step fails.
        double[][] jac = jacobian(bills, free, theta, r, elNorm, oilNorm);
        int maxIter = n > p ? 20 : 2;   // underdetermined: any exact solution will do, it is reported as such
        for (int iter = 0; iter < maxIter && cost > 1e-20; iter++) {
            double[][] jtj = new double[p][p];
            double[] jtr = new double[p];
            for (int a = 0; a < p; a++) {
                for (int c = 0; c < p; c++) {
                    for (int i = 0; i < r.length; i++) {
                        jtj[a][c] += jac[i][a] * jac[i][c];
                    }
                }
                for (int i = 0; i < r.length; i++) {
                    jtr[a] += jac[i][a] * r[i];
                }
            }
            double[][] m = new double[p][p];
            for (int a = 0; a < p; a++) {
                m[a] = jtj[a].clone();
                m[a][a] += lambda * Math.max(jtj[a][a], 1e-12);
            }
            double[] step = solve(m, negate(jtr));
            double[] cand = theta.clone();
            double maxRel = 0;
            for (int a = 0; a < p; a++) {
                Parameter fp = free.get(a);
                cand[a] = Math.max(fp.min(), Math.min(fp.max(), theta[a] + (Double.isNaN(step[a]) ? 0 : step[a])));
                maxRel = Math.max(maxRel, Math.abs(cand[a] - theta[a]) / Math.abs(theta[a]));
            }
            if (maxRel < 1e-4) {
                break;
            }
            double[] rc = residuals(bills, free, cand, elNorm, oilNorm);
            double cc = dot(rc, rc);
            if (cc < cost) {
                double[] d = new double[p];
                for (int a = 0; a < p; a++) {
                    d[a] = cand[a] - theta[a];
                }
                double dd = dot(d, d);
                for (int i = 0; i < r.length; i++) {
                    double jd = 0;
                    for (int a = 0; a < p; a++) {
                        jd += jac[i][a] * d[a];
                    }
                    double u = (rc[i] - r[i] - jd) / dd;
                    for (int a = 0; a < p; a++) {
                        jac[i][a] += u * d[a];
                    }
                }
                theta = cand;
                r = rc;
                cost = cc;
                lambda = Math.max(1e-9, lambda / 3);
            } else {
                lambda *= 4;
                jac = jacobian(bills, free, theta, r, elNorm, oilNorm);
            }
        }

        // identifiability: sensitivity to relative parameter changes
        double[][] mrel = new double[p][p];
        for (int a = 0; a < p; a++) {
            for (int c = 0; c < p; c++) {
                for (int i = 0; i < r.length; i++) {
                    mrel[a][c] += jac[i][a] * theta[a] * jac[i][c] * theta[c];
                }
            }
        }
        double[] eig = eigenvalues(mrel);
        double eMax = Arrays.stream(eig).max().orElse(0);
        double eMin = Arrays.stream(eig).min().orElse(0);
        double cond = eMin <= eMax * 1e-15 ? Double.POSITIVE_INFINITY : eMax / eMin;
        double[][] inv = Double.isInfinite(cond) ? null : invert(mrel);
        double s2 = n > p ? cost / (n - p) : Double.NaN;
        Map<String, Double> params = new LinkedHashMap<>();
        Map<String, Double> relErr = new LinkedHashMap<>();
        double maxCorr = inv == null ? 1 : 0;
        for (int a = 0; a < p; a++) {
            params.put(free.get(a).key(), theta[a]);
            relErr.put(free.get(a).key(), inv == null ? Double.NaN : Math.sqrt(Math.max(0, s2 * inv[a][a])));
            for (int c = 0; inv != null && c < a; c++) {
                maxCorr = Math.max(maxCorr, Math.abs(inv[a][c] / Math.sqrt(inv[a][a] * inv[c][c])));
            }
        }
        boolean identifiable = n >= p && cond < 1e6 && maxCorr < 0.98;

        // per-period comparison and fit quality (the model at theta is cached from the last accepted step)
        double[][] m = cached(bills, params);
        List<Period> periods = new ArrayList<>();
        for (int i = 0; i < bills.size(); i++) {
            Bill b = bills.get(i);
            if (b.hasElectricity()) {
                periods.add(new Period(b, "strom_kwh", b.electricityKwh(), m[i][0], false));
            }
            if (b.hasOil()) {
                periods.add(new Period(b, "heizoel_liter", b.oilConsumedL(), m[i][1], !b.oilFromLevels()));
                if (!b.oilFromLevels()) {
                    warnings.add("oil " + b.from() + ".." + b.to() + ": delivery used as consumption (tank levels "
                            + "missing); deliveries are not consumption - merge several years or add gauge readings");
                }
            }
        }
        Map<String, Double> cv = new LinkedHashMap<>();
        Map<String, Double> nmbe = new LinkedHashMap<>();
        for (String q : List.of("strom_kwh", "heizoel_liter")) {
            List<Period> ps = periods.stream().filter(x -> x.quantity().equals(q)).toList();
            if (ps.isEmpty()) {
                continue;
            }
            double mo = ps.stream().mapToDouble(Period::observed).average().orElse(Double.NaN);
            double sse = ps.stream().mapToDouble(x -> x.residual() * x.residual()).sum();
            double sum = ps.stream().mapToDouble(Period::residual).sum();
            cv.put(q, Math.sqrt(sse / ps.size()) / mo);
            nmbe.put(q, sum / (ps.size() * mo));
        }

        if (n <= p) {
            warnings.add(n + " observed period totals for " + p + " parameters: not identifiable, the fit is one of "
                    + "many equally good solutions - add monthly meter or tank readings");
        } else if (!identifiable) {
            warnings.add(String.format(Locale.ROOT, "parameters hardly separable (condition %.2g, max correlation "
                    + "%.3f) - periods with different weather (winter vs. summer readings) help", cond, maxCorr));
        }
        if (weather.ports().isEmpty()) {
            warnings.add("weather is synthetic - for real bills bind daily mean temperatures of a nearby station "
                    + "(--weather), otherwise H absorbs the weather difference of the years");
        }
        for (int a = 0; a < p; a++) {
            Parameter fp = free.get(a);
            if (theta[a] <= fp.min() * 1.0001 || theta[a] >= fp.max() * 0.9999) {
                warnings.add(fp.key() + " at its bound " + theta[a] + " - check the data or the fixed assumptions");
            }
        }
        warnings.add("fixed assumptions (not fitted): boiler efficiency, standby loss, wood energy per Ster, heating "
                + "oil 10 kWh/l - from period totals only H/eta is identified");

        TwinPlan calibrated = HouseScenario.withParams(base, params);
        return new Result(params, relErr, periods, cv, nmbe, cond, maxCorr, identifiable, runs.get(), warnings, calibrated);
    }

    /** Model values, cached per parameter vector (computed outside the map so parallel runs do not block). */
    private double[][] cached(List<Bill> bills, Map<String, Double> params) {
        String key = params.toString();
        double[][] m = cache.get(key);
        if (m == null) {
            m = model(bills, params);
            cache.put(key, m);
        }
        return m;
    }

    private double[] residuals(List<Bill> bills, List<Parameter> free, double[] theta, double elNorm, double oilNorm) {
        Map<String, Double> params = new LinkedHashMap<>();
        for (int a = 0; a < free.size(); a++) {
            params.put(free.get(a).key(), theta[a]);
        }
        double[][] m = cached(bills, params);
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < bills.size(); i++) {
            Bill b = bills.get(i);
            if (b.hasElectricity()) {
                r.add((m[i][0] - b.electricityKwh()) / elNorm);
            }
            if (b.hasOil()) {
                r.add((m[i][1] - b.oilConsumedL()) / oilNorm);
            }
        }
        return r.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private double[][] jacobian(List<Bill> bills, List<Parameter> free, double[] theta, double[] r0, double elNorm,
                                double oilNorm) {
        double[][] j = new double[r0.length][free.size()];
        // the columns are independent runs: evaluate them in parallel (each run is deterministic on its own)
        IntStream.range(0, free.size()).parallel().forEach(a -> {
            double h = 0.01 * Math.abs(theta[a]);
            if (theta[a] + h > free.get(a).max()) {
                h = -h;
            }
            double[] t = theta.clone();
            t[a] += h;
            double[] r = residuals(bills, free, t, elNorm, oilNorm);
            for (int i = 0; i < r0.length; i++) {
                j[i][a] = (r[i] - r0[i]) / h;
            }
        });
        return j;
    }

    // ------------------------------------------------------------------------------------------------ small linear algebra

    private static double mean(double[] v) {
        return v.length == 0 ? 1 : Math.max(1e-9, Arrays.stream(v).map(Math::abs).average().orElse(1));
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double[] negate(double[] v) {
        return Arrays.stream(v).map(x -> -x).toArray();
    }

    /** Gaussian elimination with partial pivoting; NaN for a singular system. */
    static double[] solve(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = a0[i].clone();
        }
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int i = c + 1; i < n; i++) {
                if (Math.abs(a[i][c]) > Math.abs(a[piv][c])) {
                    piv = i;
                }
            }
            if (Math.abs(a[piv][c]) < 1e-300) {
                double[] nan = new double[n];
                Arrays.fill(nan, Double.NaN);
                return nan;
            }
            double[] tr = a[c];
            a[c] = a[piv];
            a[piv] = tr;
            double tb = b[c];
            b[c] = b[piv];
            b[piv] = tb;
            for (int i = c + 1; i < n; i++) {
                double f = a[i][c] / a[c][c];
                for (int k = c; k < n; k++) {
                    a[i][k] -= f * a[c][k];
                }
                b[i] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int k = i + 1; k < n; k++) {
                s -= a[i][k] * x[k];
            }
            x[i] = s / a[i][i];
        }
        return x;
    }

    static double[][] invert(double[][] m) {
        int n = m.length;
        double[][] inv = new double[n][n];
        for (int c = 0; c < n; c++) {
            double[] e = new double[n];
            e[c] = 1;
            double[] col = solve(m, e);
            for (int i = 0; i < n; i++) {
                inv[i][c] = col[i];
            }
        }
        return inv;
    }

    /** Eigenvalues of a small symmetric matrix (cyclic Jacobi rotations). */
    static double[] eigenvalues(double[][] s) {
        int n = s.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = s[i].clone();
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    off += a[i][j] * a[i][j];
                }
            }
            if (off < 1e-30) {
                break;
            }
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1);
                    double sn = t * c;
                    for (int k = 0; k < n; k++) {
                        double akp = a[k][p];
                        double akq = a[k][q];
                        a[k][p] = c * akp - sn * akq;
                        a[k][q] = sn * akp + c * akq;
                    }
                    for (int k = 0; k < n; k++) {
                        double apk = a[p][k];
                        double aqk = a[q][k];
                        a[p][k] = c * apk - sn * aqk;
                        a[q][k] = sn * apk + c * aqk;
                    }
                }
            }
        }
        double[] ev = new double[n];
        for (int i = 0; i < n; i++) {
            ev[i] = a[i][i];
        }
        return ev;
    }
}
