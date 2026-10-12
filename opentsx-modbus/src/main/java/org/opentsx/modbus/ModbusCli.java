package org.opentsx.modbus;

import org.opentsx.model.Micros;
import org.opentsx.model.v2.Episode;
import org.opentsx.modbus.map.Device;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterMapException;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.normalize.MeterNormalizer;
import org.opentsx.modbus.normalize.NormalizationReport;
import org.opentsx.modbus.normalize.Quality;
import org.opentsx.modbus.poll.PollResult;
import org.opentsx.modbus.poll.Poller;
import org.opentsx.modbus.poll.RawReading;
import org.opentsx.modbus.poll.ReadBlock;
import org.opentsx.modbus.sim.MeterSimulator;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code bin/opentsx modbus <command>}:
 * <pre>
 * modbus check    --map file.yaml|example                         validate the map, print points and read plan
 * modbus read     --map ... [--host h] [--port p] [--unit u] [--simulate]          one poll cycle as a table
 * modbus poll     --map ... [--count n] [--interval PT5S] [--out csv|episodes] [--file f]
 *                 [--episode PT1M] [--simulate]                   poll repeatedly (count 0 = until Ctrl-C)
 * modbus simulate --map ... [--port 1502] [--bind 0.0.0.0] [--seed 42] [--seconds n]  serve a simulated meter
 * </pre>
 * {@code --simulate} starts the simulator in the same JVM and polls it; the data is then labelled
 * {@code origin=SYNTHETIC} regardless of the map.
 */
public final class ModbusCli {

    private static final String USAGE = String.join("\n",
            "usage: opentsx modbus <check|read|poll|simulate> --map <file.yaml|example> [options]",
            "  check     validate the register map, print points and the read plan",
            "  read      one poll cycle, printed as a table          [--host h] [--port p] [--unit u] [--simulate]",
            "  poll      poll repeatedly                             [--count n (0 = forever)] [--interval PT5S]",
            "                                                        [--out csv|episodes] [--file f] [--episode PT1M] [--simulate]",
            "  simulate  serve a simulated meter (Modbus TCP)        [--port 1502] [--bind 0.0.0.0] [--seed 42] [--seconds n]",
            "  --simulate polls an in-JVM simulator; such data is labelled origin=SYNTHETIC.");

    private ModbusCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) {
            System.out.println(USAGE);
            System.exit(args.length == 0 ? 2 : 0);
        }
        String cmd = args[0];
        Map<String, String> a = parse(args);
        if (!a.containsKey("map")) {
            System.err.println("--map is required (a file or 'example')\n" + USAGE);
            System.exit(2);
        }
        RegisterMap map;
        try {
            map = RegisterMap.load(a.get("map"));
        } catch (RegisterMapException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        map = map.withEndpoint(a.get("host"), a.containsKey("port") && !cmd.equals("simulate") ? Integer.valueOf(a.get("port")) : null,
                a.containsKey("unit") ? Integer.valueOf(a.get("unit")) : null);
        if (map.example()) {
            System.err.println("note: register map '" + map.name() + "' is an EXAMPLE, not a real register sheet");
        }
        int rc;
        switch (cmd) {
            case "check": rc = check(map, System.out); break;
            case "read": rc = read(map, a, System.out); break;
            case "poll": rc = poll(map, a, System.out); break;
            case "simulate": rc = simulate(map, a, System.out); break;
            default:
                System.err.println("unknown command: " + cmd + "\n" + USAGE);
                rc = 2;
        }
        System.exit(rc);
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + k);
            }
            String key = k.substring(2);
            if (key.equals("simulate")) {
                m.put(key, "true");
            } else if (i + 1 < args.length) {
                m.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("missing value for " + k);
            }
        }
        return m;
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

    static int read(RegisterMap map, Map<String, String> a, PrintStream out) throws IOException {
        try (Session s = Session.open(map, a)) {
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

    static int poll(RegisterMap map, Map<String, String> a, PrintStream stdout) throws Exception {
        if (a.containsKey("interval")) {
            Device d = map.device();
            map = map.withDevice(new Device(d.id(), d.host(), d.port(), d.unitId(), d.framing(),
                    RegisterMap.duration(a.get("interval")), d.timeout(), d.retries(), d.maxRegistersPerRequest(), d.maxGap()));
        }
        long count = Long.parseLong(a.getOrDefault("count", "0"));
        String mode = a.getOrDefault("out", "csv");
        if (!mode.equals("csv") && !mode.equals("episodes")) {
            System.err.println("--out must be csv or episodes");
            return 2;
        }
        Duration episode = RegisterMap.duration(a.getOrDefault("episode", "PT1M"));
        PrintStream file = a.containsKey("file")
                ? new PrintStream(Files.newOutputStream(Path.of(a.get("file"))), true, StandardCharsets.UTF_8) : null;
        PrintStream out = mode.equals("csv") && file != null ? file : stdout;
        try (Session s = Session.open(map, a)) {
            MeterNormalizer norm = new MeterNormalizer(s.map, episode, s.origin);
            long windowMicros = episode.toNanos() / 1_000L;
            List<RawReading> pending = new ArrayList<>();
            long[] errors = {0};
            if (mode.equals("csv")) {
                out.println("timestamp,device,point,metric,value,unit,obis,origin,quality");
            } else {
                stdout.println("episodes of " + episode + (file != null ? " -> " + a.get("file") + " (Avro JSON, one per line)" : ""));
            }
            s.poller.run(count, r -> {
                errors[0] += r.errors().size();
                for (PollResult.BlockError e : r.errors()) {
                    System.err.println(r.timestamp() + " read failed: " + e);
                }
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
            return errors[0] == 0 ? 0 : 1;
        } finally {
            if (file != null) {
                file.close();
            }
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

    static int simulate(RegisterMap map, Map<String, String> a, PrintStream out) throws Exception {
        int port = Integer.parseInt(a.getOrDefault("port", "1502"));
        InetAddress bind = InetAddress.getByName(a.getOrDefault("bind", "127.0.0.1"));
        long seed = Long.parseLong(a.getOrDefault("seed", "42"));
        long seconds = Long.parseLong(a.getOrDefault("seconds", "0"));
        try (MeterSimulator sim = new MeterSimulator(map, port, bind, seed, Instant.now())) {
            Duration tick = map.device().pollInterval().compareTo(Duration.ofSeconds(1)) < 0 ? map.device().pollInterval() : Duration.ofSeconds(1);
            sim.startTicking(tick);
            out.printf("simulated meter '%s' (%s, %d points) on %s:%d unit %d (%s) - Ctrl-C to stop%n", map.device().id(),
                    map.name(), map.points().size(), bind.getHostAddress(), sim.port(), map.device().unitId(),
                    map.device().framing().name().toLowerCase(Locale.ROOT));
            out.printf("try: bin/opentsx modbus read --map %s --host %s --port %d%n", a.get("map"), bind.getHostAddress(), sim.port());
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

        static Session open(RegisterMap map, Map<String, String> a) throws IOException {
            if (a.containsKey("simulate")) {
                MeterSimulator sim = MeterSimulator.local(map, Long.parseLong(a.getOrDefault("seed", "42")), Instant.now());
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
