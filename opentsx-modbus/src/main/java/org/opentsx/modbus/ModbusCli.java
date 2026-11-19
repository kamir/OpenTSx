package org.opentsx.modbus;

import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.modbus.map.Device;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.normalize.MeterNormalizer;
import org.opentsx.modbus.normalize.NormalizationReport;
import org.opentsx.modbus.normalize.Quality;
import org.opentsx.modbus.poll.PollResult;
import org.opentsx.modbus.poll.Poller;
import org.opentsx.modbus.poll.RawReading;
import org.opentsx.modbus.poll.ReadBlock;
import org.opentsx.modbus.sim.MeterSimulator;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The commands {@code modbus check}, {@code modbus read}, {@code modbus poll} and {@code modbus simulate}. They read their
 * keys through {@link OpenTsxConf#load}; {@code bin/opentsx modbus <command> --help} lists them. {@code --simulate}
 * starts the simulator in the same JVM and polls it; the data is then labelled {@code origin=SYNTHETIC} regardless of
 * the map. The keys and the register map are checked before the first connection. Exit codes: 0 success, 1 runtime
 * failure or a failed read, 2 usage or configuration error.
 */
public final class ModbusCli {

    static final String COMMAND = "modbus";
    static final String MAP = "opentsx.modbus.map";
    static final String HOST = "opentsx.modbus.host";
    static final String PORT = "opentsx.modbus.port";
    static final String UNIT = "opentsx.modbus.unitId";
    static final String INTERVAL = "opentsx.modbus.interval";
    static final String COUNT = "opentsx.modbus.count";
    static final String OUT = "opentsx.modbus.out";
    static final String FILE = "opentsx.modbus.file";
    static final String EPISODE = "opentsx.modbus.episodeLength";
    static final String SIMULATE = "opentsx.modbus.simulate";
    static final String SEED = "opentsx.modbus.simulator.seed";
    static final String BIND = "opentsx.modbus.simulator.bind";
    static final String SIM_PORT = "opentsx.modbus.simulator.port";
    static final String SECONDS = "opentsx.modbus.simulator.seconds";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    private ModbusCli() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(run(List.of(args), System.getenv(), out, err));
    }

    /** {@code args} without the word {@code modbus}, as bin/opentsx passes them. */
    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(COMMAND);
        argv.addAll(args);
        String command;
        OpenTsxConf conf;
        RegisterMap map;
        try {
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                Registry registry = Registry.shipped();
                out.print(loaded.command() == null ? registry.help() : registry.help(loaded.command()));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            command = loaded.command();
            conf = loaded.conf();
            map = checkedMap(command, conf);
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + oneLine(e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            return failed(e, err);
        }
        if (map.example()) {
            err.println("note: register map '" + map.name() + "' is an EXAMPLE, not a real register sheet");
        }
        int rc;
        try {
            rc = switch (command) {
                case "modbus check" -> check(map, out);
                case "modbus read" -> read(map, conf, out);
                case "modbus poll" -> poll(map, conf, out, err);
                default -> simulate(map, conf, out);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(e, err);
        } catch (IOException | RuntimeException e) {
            return failed(e, err);
        }
        return written(out, err) == 0 ? rc : 1;
    }

    /** The register map with the endpoint and interval of the keys; every value is checked, nothing is opened. */
    static RegisterMap checkedMap(String command, OpenTsxConf conf) {
        String file = conf.get(MAP);
        if (!file.equals("example") && !Files.isRegularFile(Path.of(file))) {
            throw new OpenTsxConfException(file, "no such file", "give an existing register map file or example");
        }
        boolean client = !command.equals("modbus simulate");
        if (command.equals("modbus read") || command.equals("modbus poll")) {
            boolean simulate = conf.getBoolean(SIMULATE);
            if (!simulate && conf.contains(SEED)) {
                throw new OpenTsxConfException(SEED, "needs " + SIMULATE, "give --simulate with --seed");
            }
            if (simulate && conf.contains(HOST)) {
                throw new OpenTsxConfException(HOST, "cannot be combined with " + SIMULATE, "give --host or --simulate, not both");
            }
            if (simulate && conf.contains(PORT)) {
                throw new OpenTsxConfException(PORT, "cannot be combined with " + SIMULATE, "give --port or --simulate, not both");
            }
        }
        String host = client ? conf.getOption(HOST).orElse(null) : null;
        Integer port = client ? conf.getOption(PORT).map(v -> range(PORT, conf.getInt(PORT), 1, 65535)).orElse(null) : null;
        Integer unit = conf.getOption(UNIT).map(v -> range(UNIT, conf.getInt(UNIT), 0, 255)).orElse(null);
        if (command.equals("modbus poll")) {
            if (conf.getLong(COUNT) < 0) {
                throw new OpenTsxConfException(COUNT, conf.get(COUNT) + " is not allowed", "expected 0 (poll until stopped) or more");
            }
            positive(conf, INTERVAL);
            positive(conf, EPISODE);
        }
        if (command.equals("modbus simulate")) {
            range(SIM_PORT, conf.getInt(SIM_PORT), 0, 65535);
            if (conf.getLong(SECONDS) < 0) {
                throw new OpenTsxConfException(SECONDS, conf.get(SECONDS) + " is not allowed", "expected 0 (run until stopped) or more");
            }
            try {
                InetAddress.getByName(conf.get(BIND));
            } catch (UnknownHostException e) {
                throw new OpenTsxConfException(BIND, "\"" + conf.get(BIND) + "\" is not an address",
                        "expected an address of this host such as 127.0.0.1, or 0.0.0.0 for every interface");
            }
        }
        RegisterMap map = RegisterMap.load(file).withEndpoint(host, port, unit);
        if (command.equals("modbus poll") && conf.getOption(INTERVAL).isPresent()) {
            Device d = map.device();
            map = map.withDevice(new Device(d.id(), d.host(), d.port(), d.unitId(), d.framing(), conf.getDuration(INTERVAL),
                    d.timeout(), d.retries(), d.maxRegistersPerRequest(), d.maxGap()));
        }
        return map;
    }

    private static int range(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new OpenTsxConfException(key, value + " is not allowed", "expected " + min + " to " + max);
        }
        return value;
    }

    private static void positive(OpenTsxConf conf, String key) {
        if (conf.getOption(key).isPresent() && conf.getDuration(key).isZero()) {
            throw new OpenTsxConfException(key, conf.get(key) + " is not allowed", "expected a positive duration such as 5s");
        }
    }

    static int check(RegisterMap map, PrintStream out) {
        Device d = map.device();
        out.printf("register map %s@%s%s: %d points, device %s at %s:%d unit %d (%s), poll %s%n", map.name(), map.version(),
                map.example() ? " (EXAMPLE)" : "", map.points().size(), d.id(), d.host(), d.port(), d.unitId(),
                d.framing().name().toLowerCase(Locale.ROOT), d.pollInterval());
        out.printf("%-22s %-9s %6s %-8s %-6s %10s %-6s %-12s %-9s %s%n", "point", "function", "addr", "type", "order",
                "scale", "unit", "obis", "origin", "metric");
        for (RegisterPoint p : map.points()) {
            out.printf("%-22s %-9s %6d %-8s %-6s %10s %-6s %-12s %-9s %s%s%n", p.name(), p.function().yamlName(), p.address(),
                    p.type().yamlName(), p.length() == 1 && !p.byteSwap() ? "-" : p.wordOrder().name().toLowerCase(Locale.ROOT) + (p.byteSwap() ? "/sw" : ""),
                    fmt(p.scale()), nz(p.unit()), nz(p.obis()), p.origin(), p.metric(),
                    p.kind() == RegisterPoint.Kind.COUNTER ? "  [counter]" : "");
        }
        List<ReadBlock> blocks = org.opentsx.modbus.poll.BlockPlanner.plan(map.points(), d.maxRegistersPerRequest(), d.maxGap());
        out.println("read plan (" + blocks.size() + " requests per cycle):");
        for (ReadBlock b : blocks) {
            out.println("  " + b);
        }
        return 0;
    }

    static int read(RegisterMap map, OpenTsxConf conf, PrintStream out) throws IOException {
        try (Session s = Session.open(map, conf)) {
            PollResult r = s.poller.poll();
            MeterNormalizer norm = new MeterNormalizer(s.map, Duration.ofMinutes(1), s.origin);
            out.printf("%s  %s unit %d  %d requests, %d ms%n", r.timestamp(), s.poller.client().transport().endpoint(),
                    s.map.device().unitId(), s.poller.blocks().size(), r.elapsedMillis());
            out.printf("%-22s %-9s %6s %-8s %16s %-6s %-12s %s%n", "point", "function", "addr", "type", "value", "unit", "obis", "quality");
            for (RawReading rr : r.readings()) {
                MeterNormalizer.Assessed as = norm.assess(rr);
                RegisterPoint p = as.point();
                out.printf("%-22s %-9s %6d %-8s %16s %-6s %-12s %s%n", p.name(), p.function().yamlName(), p.address(),
                        p.type().yamlName(), fmt(rr.value()), nz(p.unit()), nz(p.obis()),
                        rr.failed() ? "MISSING (" + rr.error() + ")" : Quality.name(as.quality()));
            }
            return r.ok() ? 0 : 1;
        }
    }

    static int poll(RegisterMap map, OpenTsxConf conf, PrintStream stdout, PrintStream err) throws IOException, InterruptedException {
        long count = conf.getLong(COUNT);
        String mode = conf.get(OUT);
        Duration episode = conf.getDuration(EPISODE);
        String fileName = conf.getOption(FILE).orElse(null);
        PrintStream file = fileName != null
                ? new PrintStream(Files.newOutputStream(Path.of(fileName)), true, StandardCharsets.UTF_8) : null;
        PrintStream out = mode.equals("csv") && file != null ? file : stdout;
        ReadFailures failures = new ReadFailures(err);
        try (Session s = Session.open(map, conf)) {
            MeterNormalizer norm = new MeterNormalizer(s.map, episode, s.origin);
            long windowMicros = episode.toNanos() / 1_000L;
            List<RawReading> pending = new ArrayList<>();
            if (mode.equals("csv")) {
                out.println("timestamp,device,point,metric,value,unit,obis,origin,quality");
            } else {
                stdout.println("episodes of " + episode + (file != null ? " -> " + fileName + " (Avro JSON, one per line)" : ""));
            }
            s.poller.run(count, r -> {
                failures.cycle(r);
                if (mode.equals("csv")) {
                    for (RawReading rr : r.readings()) {
                        MeterNormalizer.Assessed as = norm.assess(rr);
                        RegisterPoint p = as.point();
                        out.println(String.join(",", rr.timestamp().toString(), rr.device(), p.name(), p.metric(),
                                Double.isNaN(rr.value()) ? "" : fmt(rr.value()), nz(p.unit()), nz(p.obis()),
                                norm.origin(p), Quality.name(as.quality())));
                    }
                    out.flush();
                    return;
                }
                pending.addAll(r.readings());
                long windowStart = Math.floorDiv(Micros.of(r.timestamp()), windowMicros) * windowMicros;
                List<RawReading> closed = new ArrayList<>();
                pending.removeIf(x -> {
                    boolean done = Micros.of(x.timestamp()) < windowStart;
                    if (done) {
                        closed.add(x);
                    }
                    return done;
                });
                if (!closed.isEmpty()) {
                    emit(norm.normalize(closed), stdout, file);
                }
            });
            if (!pending.isEmpty()) {
                emit(norm.normalize(pending), stdout, file);
            }
            return failures.finish() == 0 ? 0 : 1;
        } finally {
            if (file != null) {
                file.close();
            }
        }
    }

    /**
     * The read failures of a poll on stderr: the first failure in full, then while reads keep failing one count per
     * minute of cycle time, and one line when reads succeed again. A device that is off writes one line per minute,
     * not one per block and cycle.
     */
    static final class ReadFailures {
        static final Duration REPORT = Duration.ofMinutes(1);

        private final PrintStream err;
        private long total;
        private Instant first;
        private Instant reported;
        private Instant latest;
        private long unreported;
        private long outage;
        private PollResult.BlockError last;

        ReadFailures(PrintStream err) {
            this.err = err;
        }

        void cycle(PollResult r) {
            latest = r.timestamp();
            List<PollResult.BlockError> errors = r.errors();
            if (errors.isEmpty()) {
                if (first != null) {
                    err.println(r.timestamp() + " reads succeed again after " + outage + " failed reads since " + first);
                    first = null;
                    unreported = 0;
                }
                return;
            }
            total += errors.size();
            outage = first == null ? errors.size() : outage + errors.size();
            last = errors.get(errors.size() - 1);
            if (first == null) {
                first = r.timestamp();
                reported = r.timestamp();
                unreported = errors.size() - 1;
                err.println(r.timestamp() + " read failed: " + errors.get(0));
                return;
            }
            unreported += errors.size();
            if (!r.timestamp().isBefore(reported.plus(REPORT))) {
                count(r.timestamp());
            }
        }

        /** Prints the failures not counted yet and returns the number of failed reads of the whole poll. */
        long finish() {
            if (first != null && unreported > 0) {
                count(latest);
            }
            return total;
        }

        private void count(Instant at) {
            err.println(at + " read failed " + unreported + " more times since " + reported + "; last: " + last);
            reported = at;
            unreported = 0;
        }
    }

    private static void emit(MeterNormalizer.Result res, PrintStream stdout, PrintStream file) {
        for (Episode e : res.episodes()) {
            String flagged = e.getQuality() == null ? "" : "  quality-flags";
            stdout.printf("%s  %-36s %s  n=%-4d mean=%s%s%n", e.getTStart(), e.getSeries().getMetric(),
                    e.getLabels().get("origin"), e.getCount(),
                    e.getSummary() == null ? "-" : fmt(e.getSummary().getMean()), flagged);
            if (file != null) {
                file.println(e.toString());
            }
        }
        NormalizationReport rep = res.report();
        stdout.printf("  window done: %d readings, %d accepted, %d missing, %d implausible, %d counter wraps, %d counter resets, %d episodes%n",
                rep.readings(), rep.accepted(), rep.missing(), rep.implausible(), rep.counterWraps(), rep.counterResets(),
                rep.episodes());
    }

    static int simulate(RegisterMap map, OpenTsxConf conf, PrintStream out) throws IOException, InterruptedException {
        int port = conf.getInt(SIM_PORT);
        InetAddress bind = InetAddress.getByName(conf.get(BIND));
        long seed = conf.getLong(SEED);
        long seconds = conf.getLong(SECONDS);
        try (MeterSimulator sim = new MeterSimulator(map, port, bind, seed, Instant.now())) {
            Duration tick = map.device().pollInterval().compareTo(Duration.ofSeconds(1)) < 0 ? map.device().pollInterval() : Duration.ofSeconds(1);
            sim.startTicking(tick);
            out.printf("simulated meter '%s' (%s, %d points) on %s:%d unit %d (%s); stop with Ctrl-C%n", map.device().id(),
                    map.name(), map.points().size(), bind.getHostAddress(), sim.port(), map.device().unitId(),
                    map.device().framing().name().toLowerCase(Locale.ROOT));
            out.printf("try: bin/opentsx modbus read --map %s --host %s --port %d%n", conf.get(MAP), bind.getHostAddress(), sim.port());
            long until = seconds > 0 ? System.currentTimeMillis() + seconds * 1000 : Long.MAX_VALUE;
            while (System.currentTimeMillis() < until) {
                Thread.sleep(Math.min(1000, Math.max(1, until - System.currentTimeMillis())));
            }
            out.println("served " + sim.server().requests() + " requests");
        }
        return 0;
    }

    /** A poller, optionally against an in-JVM simulator. */
    private static final class Session implements AutoCloseable {
        final RegisterMap map;
        final Poller poller;
        final MeterSimulator sim;
        final String origin;

        private Session(RegisterMap map, Poller poller, MeterSimulator sim, String origin) {
            this.map = map;
            this.poller = poller;
            this.sim = sim;
            this.origin = origin;
        }

        static Session open(RegisterMap map, OpenTsxConf conf) throws IOException {
            if (conf.getBoolean(SIMULATE)) {
                MeterSimulator sim = MeterSimulator.local(map, conf.getLong(SEED), Instant.now());
                sim.startTicking(Duration.ofMillis(Math.min(1000, map.device().pollInterval().toMillis())));
                RegisterMap m = sim.clientMap();
                return new Session(m, Poller.connect(m), sim, "SYNTHETIC");
            }
            return new Session(map, Poller.connect(map), null, null);
        }

        @Override
        public void close() {
            poller.close();
            if (sim != null) {
                sim.close();
            }
        }
    }

    private static int failed(Exception e, PrintStream err) {
        LoggerFactory.getLogger(ModbusCli.class).debug("stack trace of the failed run", e);
        err.print("opentsx: " + causes(e) + "\n");
        err.flush();
        return 1;
    }

    private static int written(PrintStream out, PrintStream err) {
        out.flush();
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    /** The message and its causes on one line; a checked exception with its class, its message is often a bare path. */
    static String causes(Throwable t) {
        boolean bare = !(t instanceof RuntimeException) || t.getMessage() == null;
        StringBuilder s = new StringBuilder(bare ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return oneLine(s.toString());
    }

    private static String oneLine(String s) {
        return String.valueOf(s).replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
