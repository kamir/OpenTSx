package org.opentsx.modbus.poll;

import org.opentsx.modbus.client.ModbusClient;
import org.opentsx.modbus.client.TcpTransport;
import org.opentsx.modbus.map.Device;
import org.opentsx.modbus.map.RegisterCodec;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.protocol.ModbusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Polls a device according to its register map: reads the planned blocks, decodes every point and returns one
 * {@link RawReading} per point. A failing block does not stop the cycle; its points get NaN and the error.
 * The timestamp of a cycle is its start (all points of a cycle share it).
 */
public final class Poller implements AutoCloseable {

    private final RegisterMap map;
    private final ModbusClient client;
    private final List<ReadBlock> blocks;
    private final Clock clock;

    public Poller(RegisterMap map, ModbusClient client, Clock clock) {
        this.map = map;
        this.client = client;
        this.clock = clock;
        Device d = map.device();
        this.blocks = BlockPlanner.plan(map.points(), d.maxRegistersPerRequest(), d.maxGap());
    }

    public Poller(RegisterMap map, ModbusClient client) {
        this(map, client, Clock.systemUTC());
    }

    /** A poller with its own TCP client built from the map's device section. */
    public static Poller connect(RegisterMap map) {
        Device d = map.device();
        TcpTransport.Settings s = new TcpTransport.Settings(d.host(), d.port(), d.framing(), d.timeout(), d.timeout(),
                d.retries(), null);
        return new Poller(map, new ModbusClient(new TcpTransport(s)));
    }

    public RegisterMap map() {
        return map;
    }

    public List<ReadBlock> blocks() {
        return blocks;
    }

    public ModbusClient client() {
        return client;
    }

    public PollResult poll() {
        return poll(clock.instant());
    }

    public PollResult poll(Instant ts) {
        long t0 = System.nanoTime();
        Device d = map.device();
        Map<String, double[]> raw = new HashMap<>();
        Map<String, String> failed = new HashMap<>();
        List<PollResult.BlockError> errors = new ArrayList<>();
        for (ReadBlock b : blocks) {
            try {
                if (b.function().bits()) {
                    boolean[] bits = client.readBits(b.function(), d.unitId(), b.start(), b.quantity());
                    for (RegisterPoint p : b.points()) {
                        raw.put(p.name(), new double[]{bits[p.address() - b.start()] ? 1 : 0});
                    }
                } else {
                    int[] regs = client.readRegisters(b.function(), d.unitId(), b.start(), b.quantity());
                    for (RegisterPoint p : b.points()) {
                        raw.put(p.name(), new double[]{RegisterCodec.decode(regs, p.address() - b.start(), p.type(),
                                p.wordOrder(), p.byteSwap())});
                    }
                }
            } catch (ModbusException | RuntimeException e) {
                errors.add(new PollResult.BlockError(b, e));
                for (RegisterPoint p : b.points()) {
                    failed.put(p.name(), e.getMessage());
                }
            }
        }
        List<RawReading> readings = new ArrayList<>(map.points().size());
        for (RegisterPoint p : map.points()) {
            double[] r = raw.get(p.name());
            if (r != null) {
                readings.add(new RawReading(d.id(), p.name(), ts, p.toEngineering(r[0]), r[0], p.unit(), null));
            } else {
                readings.add(new RawReading(d.id(), p.name(), ts, Double.NaN, Double.NaN, p.unit(), failed.get(p.name())));
            }
        }
        return new PollResult(ts, readings, errors, (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * Polls {@code cycles} times (0 = until interrupted) at the device's poll interval, aligned to multiples of
     * the interval on the clock (so several pollers stamp the same instants).
     */
    public void run(long cycles, Consumer<PollResult> sink) throws InterruptedException {
        long interval = map.device().pollInterval().toMillis();
        long last = Long.MIN_VALUE;
        for (long n = 0; cycles <= 0 || n < cycles; n++) {
            long now = clock.millis();
            long next = Math.floorDiv(now + interval - 1, interval) * interval;
            if (last != Long.MIN_VALUE && next <= last) {
                next = last + interval;
            }
            last = next;
            if (next > now) {
                Thread.sleep(next - now);
            }
            sink.accept(poll(Instant.ofEpochMilli(next)));
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
