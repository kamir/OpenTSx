package org.opentsx.windpark.sim;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.Ulid;
import org.opentsx.model.XxHash64;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Segmentation;
import org.opentsx.model.v2.SegmentationStrategy;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.windpark.catalog.ChannelCatalog;
import org.opentsx.windpark.catalog.ChannelDef;
import org.opentsx.windpark.catalog.Park;
import org.opentsx.windpark.catalog.Turbine;
import org.opentsx.windpark.catalog.TurbineType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Deterministic wind-farm simulator.
 *
 * <p>Physics (deliberately simple, but with the structure real SCADA data has):
 * <ul>
 *   <li>free wind: hourly means drawn from Weibull(k, c), relaxed with a 30-min time constant, diurnal modulation;
 *   turbulence = shared gust (τ = 60 s) + turbine-local turbulence (τ = 8 s), total σ = TI · U</li>
 *   <li>direction: Ornstein-Uhlenbeck around the mean direction (τ = 2 h)</li>
 *   <li>wakes: Jensen model, recomputed every 10 s from direction and the thrust of running turbines</li>
 *   <li>turbine: power curve, first-order lags for power, rotor speed and generator temperature, pitch rate limit,
 *   yaw with 8° dead band and 0.5°/s, storm stop with hysteresis, curtailment, fault and maintenance windows</li>
 *   <li>sensor faults on measured values: STUCK, DRIFT, OFFSET, SPIKES, GAP (NaN)</li>
 * </ul>
 * Values are quantized to the catalog resolution and emitted as REGULAR episodes (one per turbine, channel and
 * {@code episodeDuration}, aligned), labelled with the ground truth they overlap.
 */
public final class WindparkSimulator {

    private static final int RUNNING = 1, IDLE = 2, CURTAILED = 3, FAULT = 4, MAINTENANCE = 5, STORM = 6;

    private final Scenario scenario;
    private final ChannelCatalog catalog;
    private final Park park;

    public WindparkSimulator(Scenario scenario, ChannelCatalog catalog) {
        this.scenario = scenario;
        this.catalog = catalog;
        Scenario.ParkSpec p = scenario.park();
        this.park = Park.grid(p.id(), p.name(), p.latitude(), p.longitude(), TurbineType.byModel(p.turbineModel()),
                p.rows(), p.columns(), p.spacingRotorDiameters(), p.rowSpacingRotorDiameters());
    }

    public Park park() {
        return park;
    }

    public List<GroundTruth> groundTruth() {
        long t0 = Micros.of(scenario.start());
        List<GroundTruth> out = new ArrayList<>();
        for (Scenario.Operation op : scenario.operations()) {
            for (Turbine t : park.turbines()) {
                if (op.applies(t.id())) {
                    Map<String, String> params = op.limitKw() == null ? Map.of() : Map.of("limitKw", op.limitKw().toString());
                    out.add(new GroundTruth("operation", op.type().name(), t.id(), null,
                            t0 + micros(op.offset()), t0 + micros(op.offset()) + micros(op.duration()), params));
                }
            }
        }
        for (Scenario.SensorFault f : scenario.sensorFaults()) {
            park.turbine(f.turbine());
            catalog.channel(f.channel());
            Map<String, String> params = new LinkedHashMap<>();
            if (f.ratePerHour() != null) {
                params.put("ratePerHour", f.ratePerHour().toString());
            }
            if (f.magnitude() != null) {
                params.put("magnitude", f.magnitude().toString());
            }
            if (f.probability() != null) {
                params.put("probability", f.probability().toString());
            }
            out.add(new GroundTruth("sensor-fault", f.type().name(), f.turbine(), f.channel(),
                    t0 + micros(f.offset()), t0 + micros(f.offset()) + micros(f.duration()), params));
        }
        return out;
    }

    /** Runs the scenario and streams episodes to {@code sink} in time order (window by window). */
    public SimulationReport run(Consumer<Episode> sink) {
        List<Turbine> turbines = park.turbines();
        int n = turbines.size();
        List<ChannelDef> channels = catalog.channels();
        List<GroundTruth> truth = groundTruth();

        double dt = scenario.sampleInterval().toNanos() / 1e9;
        long stepMicros = micros(scenario.sampleInterval());
        long windowMicros = micros(scenario.episodeDuration());
        int stepsPerWindow = (int) (windowMicros / stepMicros);
        long t0 = Micros.of(scenario.start());
        long tEnd = t0 + micros(scenario.duration());

        Scenario.WindSpec w = scenario.wind();
        Random weather = new Random(scenario.seed());
        Random[] local = new Random[n];
        for (int i = 0; i < n; i++) {
            local[i] = new Random(scenario.seed() * 31 + i + 1);
        }
        Random faults = new Random(scenario.seed() ^ 0x9E3779B97F4A7C15L);

        // weather state
        double slow = weibull(weather, w.weibullK(), w.weibullC());
        double target = slow;
        double direction = w.meanDirectionDeg();
        double gust = 0;
        // turbine state
        double[] turb = new double[n];
        double[] power = new double[n];
        double[] rotor = new double[n];
        double[] pitch = new double[n];
        double[] yaw = new double[n];
        double[] genTemp = new double[n];
        boolean[] storm = new boolean[n];
        int[] state = new int[n];
        double[] deficit = new double[n];
        double[] ct = new double[n];
        double[] energy = new double[n];
        double[] windSum = new double[n];
        long[] samples = new long[n];
        List<Map<Integer, Long>> stateSecs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            yaw[i] = w.meanDirectionDeg();
            genTemp[i] = w.meanTemperatureC() + 10;
            stateSecs.add(new TreeMap<>());
        }
        Map<String, Double> stuck = new LinkedHashMap<>(); // fault key -> frozen value

        long episodes = 0;
        long values = 0;
        long step = 0;
        for (long windowStart = t0; windowStart < tEnd; windowStart += windowMicros) {
            double[][][] buf = new double[n][channels.size()][stepsPerWindow];
            for (int s = 0; s < stepsPerWindow; s++, step++) {
                long t = windowStart + s * stepMicros;
                double hour = ((t / 1_000_000L) % 86_400L) / 3600.0;
                // --- weather
                if (step % Math.max(1, (long) (3600 / dt)) == 0 && step > 0) {
                    target = weibull(weather, w.weibullK(), w.weibullC());
                }
                slow = lag(slow, target, 1800, dt);
                direction = ou(weather, direction, w.meanDirectionDeg(), w.directionStdDeg(), 7200, dt);
                gust = ou(weather, gust, 0, 1, 60, dt);
                double free = Math.max(0, slow * (1 + w.diurnalAmplitude() * Math.sin(2 * Math.PI * (hour - 8) / 24)));
                double ambient = w.meanTemperatureC() + 4 * Math.sin(2 * Math.PI * (hour - 9) / 24);
                if (step % Math.max(1, (long) (10 / dt)) == 0) {
                    deficit = WakeModel.deficits(turbines, direction, ct, scenario.park().wakeDecay());
                }
                // --- turbines
                for (int i = 0; i < n; i++) {
                    Turbine tb = turbines.get(i);
                    TurbineType type = tb.type();
                    turb[i] = ou(local[i], turb[i], 0, 1, 8, dt);
                    double mean = free * (1 - deficit[i]);
                    double v = Math.max(0, mean * (1 + w.turbulenceIntensity() * (0.6 * gust + 0.8 * turb[i])));

                    if (mean > type.cutOutMs()) {
                        storm[i] = true;
                    } else if (mean < type.cutOutMs() - 3) {
                        storm[i] = false;
                    }
                    double limit = Double.POSITIVE_INFINITY;
                    boolean fault = false;
                    boolean maint = false;
                    for (GroundTruth g : truth) {
                        if (g.channel() == null && g.turbine().equals(tb.id()) && t >= g.fromMicros() && t < g.toMicros()) {
                            switch (g.type()) {
                                case "CURTAILMENT" -> limit = Math.min(limit, Double.parseDouble(g.params().getOrDefault("limitKw", "0")));
                                case "FAULT" -> fault = true;
                                case "MAINTENANCE" -> maint = true;
                                default -> { }
                            }
                        }
                    }
                    double available = PowerCurve.powerKw(type, v);
                    int st;
                    if (maint) {
                        st = MAINTENANCE;
                    } else if (fault) {
                        st = FAULT;
                    } else if (storm[i]) {
                        st = STORM;
                    } else if (v < type.cutInMs()) {
                        st = IDLE;
                    } else if (available > limit) {
                        st = CURTAILED;
                    } else {
                        st = RUNNING;
                    }
                    double pT;
                    double rT;
                    double bT;
                    switch (st) {
                        case RUNNING, CURTAILED -> {
                            pT = Math.min(available, limit);
                            rT = clamp(type.ratedRotorRpm() * v / type.ratedWindMs(), type.minRotorRpm(), type.ratedRotorRpm());
                            bT = v > type.ratedWindMs() ? Math.min(30, 2.2 * (v - type.ratedWindMs())) : 0;
                            if (st == CURTAILED) {
                                bT += 6 * (1 - limit / Math.max(available, 1));
                            }
                        }
                        case IDLE -> {
                            pT = 0;
                            rT = type.minRotorRpm() * 0.3;
                            bT = 60;
                        }
                        default -> {
                            pT = 0;
                            rT = 0;
                            bT = 90;
                        }
                    }
                    power[i] = lag(power[i], pT, 3, dt);
                    rotor[i] = lag(rotor[i], rT, 6, dt);
                    pitch[i] += clamp(bT - pitch[i], -6 * dt, 6 * dt);
                    if (st != MAINTENANCE) {
                        double err = wrap180(direction - yaw[i]);
                        if (Math.abs(err) > 8) {
                            yaw[i] = wrap360(yaw[i] + Math.signum(err) * Math.min(0.5 * dt, Math.abs(err)));
                        }
                    }
                    genTemp[i] = lag(genTemp[i], ambient + 75 * power[i] / type.ratedPowerKw(), 1200, dt);
                    state[i] = st;
                    ct[i] = PowerCurve.thrustCoefficient(type, v, st == RUNNING || st == CURTAILED);
                    energy[i] += power[i] * dt / 3600;
                    windSum[i] += v;
                    samples[i]++;
                    stateSecs.get(i).merge(st, (long) Math.round(dt), Long::sum);

                    for (int c = 0; c < channels.size(); c++) {
                        ChannelDef ch = channels.get(c);
                        double value = switch (ch.metric()) {
                            case "wind.met.wind_speed" -> v;
                            case "wind.met.wind_direction" -> wrap360(direction + local[i].nextGaussian() * 2);
                            case "wind.met.ambient_temperature" -> ambient + local[i].nextGaussian() * 0.05;
                            case "wind.nacelle.direction" -> yaw[i];
                            case "wind.rotor.speed" -> Math.max(0, rotor[i]);
                            case "wind.rotor.pitch_angle" -> pitch[i];
                            case "wind.generator.speed" -> Math.max(0, rotor[i] * type.gearRatio());
                            case "wind.generator.temperature" -> genTemp[i];
                            case "wind.turbine.power_active" -> power[i];
                            case "wind.turbine.state" -> st;
                            default -> Double.NaN;
                        };
                        value = applySensorFaults(truth, tb.id(), ch, t, value, faults, stuck);
                        buf[i][c][s] = ch.quantize(value);
                    }
                }
            }
            // --- emit one episode per turbine and channel
            long wEnd = windowStart + windowMicros;
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < channels.size(); c++) {
                    ChannelDef ch = channels.get(c);
                    SeriesKey key = park.seriesKey(ch, turbines.get(i).id());
                    Episodes.Draft d = Episodes.regular(key, windowStart, stepMicros, buf[i][c])
                            .episodeId(Ulid.deterministic(windowStart / 1000, idSeed(key, windowStart)))
                            .createdAt(wEnd)
                            .source("simulator:" + scenario.name())
                            .producer("opentsx-windpark/3.0.1")
                            .pipeline("windpark-sim")
                            .label("scenario", scenario.name())
                            .label("seed", Long.toString(scenario.seed()))
                            .label("catalog", catalog.name() + "@" + catalog.version())
                            .label("iec", ch.iecReference())
                            .segmentation(Segmentation.newBuilder()
                                    .setStrategy(SegmentationStrategy.FIXED_DURATION)
                                    .setParams(Map.of("duration", scenario.episodeDuration().toString()))
                                    .setAnchorTs(null).setSourceEpisodeId(null).build());
                    labelGroundTruth(d, truth, turbines.get(i).id(), ch.metric(), windowStart, wEnd);
                    byte[] quality = qualityOf(buf[i][c], ch);
                    if (quality != null) {
                        d.quality(quality);
                    }
                    sink.accept(d.build());
                    episodes++;
                    values += stepsPerWindow;
                }
            }
        }

        Map<String, Double> energyKwh = new LinkedHashMap<>();
        Map<String, Double> meanWind = new LinkedHashMap<>();
        Map<String, Map<Integer, Long>> states = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String id = turbines.get(i).id();
            energyKwh.put(id, energy[i]);
            meanWind.put(id, samples[i] == 0 ? 0 : windSum[i] / samples[i]);
            states.put(id, stateSecs.get(i));
        }
        return new SimulationReport(scenario.name(), episodes, values, truth, energyKwh, meanWind, states);
    }

    private static double applySensorFaults(List<GroundTruth> truth, String turbine, ChannelDef ch, long t, double value,
                                            Random rnd, Map<String, Double> stuck) {
        for (GroundTruth g : truth) {
            if (!"sensor-fault".equals(g.category()) || !g.turbine().equals(turbine) || !ch.metric().equals(g.channel())
                    || t < g.fromMicros() || t >= g.toMicros()) {
                continue;
            }
            Map<String, String> p = g.params();
            switch (g.type()) {
                case "STUCK" -> {
                    double current = value;
                    // frozen at the value measured when the fault started
                    value = stuck.computeIfAbsent(turbine + "|" + ch.metric() + "|" + g.fromMicros(), k -> current);
                }
                case "DRIFT" -> value += Double.parseDouble(p.getOrDefault("ratePerHour", "1")) * (t - g.fromMicros()) / 3.6e9;
                case "OFFSET" -> value += Double.parseDouble(p.getOrDefault("magnitude", "1"));
                case "SPIKES" -> {
                    if (rnd.nextDouble() < Double.parseDouble(p.getOrDefault("probability", "0.01"))) {
                        value += (rnd.nextBoolean() ? 1 : -1) * Double.parseDouble(p.getOrDefault("magnitude", "5"));
                    }
                }
                case "GAP" -> value = Double.NaN;
                default -> { }
            }
        }
        return value;
    }

    private static void labelGroundTruth(Episodes.Draft d, List<GroundTruth> truth, String turbine, String metric,
                                         long from, long to) {
        List<String> ops = new ArrayList<>();
        List<String> faults = new ArrayList<>();
        for (GroundTruth g : truth) {
            if (!g.turbine().equals(turbine) || !g.overlaps(from, to)) {
                continue;
            }
            if (g.channel() == null) {
                ops.add(g.type());
            } else if (g.channel().equals(metric)) {
                faults.add(g.type());
            }
        }
        if (!ops.isEmpty()) {
            d.label("gt.operation", String.join(",", ops));
        }
        if (!faults.isEmpty()) {
            d.label("gt.sensor_fault", String.join(",", faults));
        }
    }

    /** Catalog quality codes: 2 = MISSING (NaN), 1 = IMPLAUSIBLE; null if every value is good. */
    private static byte[] qualityOf(double[] values, ChannelDef ch) {
        byte[] q = null;
        for (int k = 0; k < values.length; k++) {
            byte code = Double.isNaN(values[k]) ? (byte) 2 : ch.plausible(values[k]) ? 0 : (byte) 1;
            if (code != 0) {
                if (q == null) {
                    q = new byte[values.length];
                }
                q[k] = code;
            }
        }
        return q;
    }

    private long idSeed(SeriesKey key, long windowStart) {
        return XxHash64.hash((scenario.seed() + "|" + key.getSeriesId() + "|" + windowStart).getBytes(StandardCharsets.UTF_8));
    }

    private static double weibull(Random r, double k, double c) {
        return c * Math.pow(-Math.log(1 - r.nextDouble()), 1 / k);
    }

    /**
     * Exact Ornstein-Uhlenbeck step (stationary standard deviation {@code sigma}, time constant {@code tau});
     * stable for any {@code dt}, also when the sample interval exceeds tau.
     */
    private static double ou(Random r, double x, double mu, double sigma, double tau, double dt) {
        double a = Math.exp(-dt / tau);
        return mu + (x - mu) * a + sigma * Math.sqrt(1 - a * a) * r.nextGaussian();
    }

    /** First-order lag towards {@code target}, exact for any dt. */
    private static double lag(double x, double target, double tau, double dt) {
        return target + (x - target) * Math.exp(-dt / tau);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double wrap180(double a) {
        return ((a + 180) % 360 + 360) % 360 - 180;
    }

    private static double wrap360(double a) {
        return ((a % 360) + 360) % 360;
    }

    private static long micros(java.time.Duration d) {
        return d == null ? 0 : d.toNanos() / 1_000L;
    }
}
