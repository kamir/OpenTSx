package org.opentsx.modbus.sim;

import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.protocol.Framing;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A simulated meter: register image + {@link MeterProfile} + {@link ModbusTcpServer}, serving the points of a
 * register map at the map's unit id. {@link #update(Instant)} advances the profile and writes the registers;
 * {@link #startTicking(Duration)} does that periodically on the wall clock (CLI demo).
 */
public final class MeterSimulator implements AutoCloseable {

    private final RegisterMap map;
    private final RegisterImage image;
    private final MeterProfile profile;
    private final ModbusTcpServer server;
    private ScheduledExecutorService ticker;

    public MeterSimulator(RegisterMap map, int port, InetAddress bind, long seed, Instant epoch) throws IOException {
        this.map = map;
        this.image = RegisterImage.of(map.points());
        this.profile = new MeterProfile(map, seed, epoch);
        Framing.Kind framing = map.device().framing();
        this.server = new ModbusTcpServer(image, framing, port, bind, map.device().unitId());
        update(epoch);
    }

    /** Loopback, ephemeral port. */
    public static MeterSimulator local(RegisterMap map, long seed, Instant epoch) throws IOException {
        return new MeterSimulator(map, 0, InetAddress.getLoopbackAddress(), seed, epoch);
    }

    public RegisterMap map() {
        return map;
    }

    /** The map pointing at this simulator (loopback and its port). */
    public RegisterMap clientMap() {
        return map.withEndpoint(InetAddress.getLoopbackAddress().getHostAddress(), server.port(), null);
    }

    public int port() {
        return server.port();
    }

    public RegisterImage image() {
        return image;
    }

    public MeterProfile profile() {
        return profile;
    }

    public ModbusTcpServer server() {
        return server;
    }

    public Map<String, Double> update(Instant t) {
        Map<String, Double> v = profile.update(t);
        for (RegisterPoint p : map.points()) {
            Double value = v.get(p.name());
            if (value != null) {
                image.write(p, value);
            }
        }
        return v;
    }

    public synchronized void startTicking(Duration every) {
        if (ticker != null) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "modbus-sim-ticker");
            t.setDaemon(true);
            return t;
        });
        long ms = Math.max(10, every.toMillis());
        ticker.scheduleAtFixedRate(() -> update(Clock.systemUTC().instant()), ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        server.close();
    }
}
