package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Origin;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SplittableRandom;

/**
 * A4: weather at the site. Outputs the outdoor temperature and the global horizontal irradiance. A bound measurement
 * ({@code t_out_meas_c}, {@code ghi_meas_w_m2}, e.g. a DWD station) wins; otherwise a <b>deterministic synthetic
 * climate</b> is used: seasonal cycle ({@code meanC}, {@code seasonAmpC}, coldest around 18 January), a daily cycle
 * (minimum around 04:00) and day-to-day anomalies from fixed sines plus seeded per-day noise. The defaults are an
 * invented mid-altitude Erzgebirge site (about 650 m); they are assumptions, not station data.
 * <p>
 * State {@code hdd_kd} counts heating degree days G20/15 (VDI 3807: for each complete day with mean below 15 °C,
 * add 20 °C minus the mean). Days run on standard time (UTC+1).
 */
public final class Weather extends Atom {

    private static final double YEAR_DAYS = 365.2425;
    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long CET_MICROS = 3_600_000_000L;

    private final double meanC;
    private final double seasonAmpC;
    private final double dailyAmpC;
    private final double anomalyScale;
    private final long seed;
    private final double latRad;
    private final double lonDeg;
    private final double cloudMin;
    private final double cloudMax;
    private final double hddBaseC;
    private final double hddLimitC;

    private double tOut;
    private double hddKd;
    private double heatingDays;
    private double daySum;
    private double dayN;
    private double tSum;
    private double hours;
    private double ghiKwhM2;

    public Weather(String id, Params params) {
        super(id, params);
        meanC = params.d("meanC", 6.3);
        seasonAmpC = params.d("seasonAmpC", 9.0);
        dailyAmpC = params.d("dailyAmpC", 5.0);
        anomalyScale = params.d("anomalyScale", 1.0);
        seed = params.i("seed", 7);
        latRad = Math.toRadians(params.d("latitude", 50.6));
        lonDeg = params.d("longitude", 13.0);
        cloudMin = params.d("cloudMin", 0.25);
        cloudMax = params.d("cloudMax", 0.85);
        hddBaseC = params.d("hddBaseC", 20);
        hddLimitC = params.d("hddLimitC", 15);
    }

    @Override
    public String type() {
        return "weather";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("t_out_meas_c", "°C"), PortSpec.signal("ghi_meas_w_m2", "W/m2"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.signal("t_out_c", "°C"), PortSpec.signal("ghi_w_m2", "W/m2"));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("t_out_c", tOut);
        s.put("hdd_kd", hddKd);
        s.put("heating_days", heatingDays);
        s.put("day_sum", daySum);
        s.put("day_n", dayN);
        s.put("t_sum", tSum);
        s.put("hours", hours);
        s.put("ghi_kwh_m2", ghiKwhM2);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        tOut = s.get("t_out_c");
        hddKd = s.get("hdd_kd");
        heatingDays = s.get("heating_days");
        daySum = s.get("day_sum");
        dayN = s.get("day_n");
        tSum = s.get("t_sum");
        hours = s.get("hours");
        ghiKwhM2 = s.get("ghi_kwh_m2");
    }

    @Override
    public void step(StepContext ctx) {
        // value at the middle of the interval
        long mid = ctx.tMicros() + (long) (ctx.dtSeconds() * 5e5);
        OptionalDouble tm = ctx.in("t_out_meas_c");
        if (tm.isPresent()) {
            tOut = tm.getAsDouble();
            ctx.out("t_out_c", tOut);
        } else {
            tOut = syntheticTemperature(mid);
            ctx.out("t_out_c", tOut, Origin.SYNTHETIC);
        }
        OptionalDouble gm = ctx.in("ghi_meas_w_m2");
        double ghi = gm.isPresent() ? gm.getAsDouble() : syntheticGhi(mid);
        if (gm.isPresent()) {
            ctx.out("ghi_w_m2", ghi);
        } else {
            ctx.out("ghi_w_m2", ghi, Origin.SYNTHETIC);
        }

        double h = ctx.dtHours();
        tSum += tOut * h;
        hours += h;
        ghiKwhM2 += ghi / 1000 * h;
        daySum += tOut * h;
        dayN += h;
        if (dayN >= 24 - 1e-9) {
            double mean = daySum / dayN;
            if (mean < hddLimitC) {
                hddKd += hddBaseC - mean;
                heatingDays++;
            }
            daySum = 0;
            dayN = 0;
        }
    }

    /** Synthetic outdoor temperature at {@code tMicros}, a pure function of time and parameters. */
    public double syntheticTemperature(long tMicros) {
        double d = (tMicros + CET_MICROS) / (double) DAY_MICROS;   // days since 1970-01-01, standard time
        double season = -Math.cos(2 * Math.PI * (d - 17.5) / YEAR_DAYS); // -1 mid-January, +1 mid-July
        double hourOfDay = (d - Math.floor(d)) * 24;
        double dailyAmp = dailyAmpC * (1 + 0.25 * season);
        double daily = -dailyAmp / 2 * Math.cos(2 * Math.PI * (hourOfDay - 4) / 24);
        double anomaly = anomalyScale * (1.15 - 0.25 * season) * (
                2.2 * Math.sin(2 * Math.PI * d / 5.3 + 1.0)
                        + 1.8 * Math.sin(2 * Math.PI * d / 11.7 + 2.0)
                        + 1.4 * Math.sin(2 * Math.PI * d / 27.1 + 3.0)
                        + 1.5 * interpolatedNoise(d, 0));
        return meanC + seasonAmpC * season + daily + anomaly;
    }

    /** Synthetic global horizontal irradiance at {@code tMicros}: clear-sky shape times a per-day cloud factor. */
    public double syntheticGhi(long tMicros) {
        double dUtc = tMicros / (double) DAY_MICROS;
        double decl = Math.toRadians(23.44) * Math.sin(2 * Math.PI * (dUtc - 79.5) / YEAR_DAYS);
        double solarHour = (dUtc - Math.floor(dUtc)) * 24 + lonDeg / 15.0;
        double hourAngle = Math.toRadians(15 * (solarHour - 12));
        double sinAlt = Math.sin(latRad) * Math.sin(decl) + Math.cos(latRad) * Math.cos(decl) * Math.cos(hourAngle);
        if (sinAlt <= 0) {
            return 0;
        }
        double day = Math.floor((tMicros + CET_MICROS) / (double) DAY_MICROS);
        double cloud = cloudMin + (cloudMax - cloudMin) * noise((long) day, 1);
        return 1000 * Math.pow(sinAlt, 1.15) * cloud;
    }

    /** Noise in [-1, 1] per day, linearly interpolated so the temperature has no jumps at midnight. */
    private double interpolatedNoise(double d, int stream) {
        long d0 = (long) Math.floor(d);
        double f = d - d0;
        return (1 - f) * (2 * noise(d0, stream) - 1) + f * (2 * noise(d0 + 1, stream) - 1);
    }

    /** Uniform [0, 1) per (day, stream), from the seed; no state, so snapshots and repeats are exact. */
    private double noise(long day, int stream) {
        return new SplittableRandom(seed * 1_000_003L + day * 31L + stream).nextDouble();
    }
}
