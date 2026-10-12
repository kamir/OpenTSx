package org.opentsx.modbus;

import org.junit.jupiter.api.Test;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.TimeEncoding;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.normalize.CounterTracker;
import org.opentsx.modbus.normalize.MeterNormalizer;
import org.opentsx.modbus.normalize.Quality;
import org.opentsx.modbus.poll.PollResult;
import org.opentsx.modbus.poll.Poller;
import org.opentsx.modbus.poll.RawReading;
import org.opentsx.modbus.sim.MeterSimulator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollAndNormalizeTest {

    static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void pollReadsWhatTheSimulatedMeterServes() throws Exception {
        RegisterMap map = RegisterMap.exampleGridMeter();
        try (MeterSimulator sim = MeterSimulator.local(map, 7, T0);
             Poller poller = Poller.connect(sim.clientMap())) {
            assertEquals(5, poller.blocks().size());
            for (int k = 1; k <= 3; k++) {
                Instant t = T0.plusSeconds(5L * k);
                Map<String, Double> truth = sim.update(t);
                PollResult r = poller.poll(t);
                assertTrue(r.ok(), r.errors().toString());
                assertEquals(map.points().size(), r.readings().size());
                for (RawReading rr : r.readings()) {
                    RegisterPoint p = map.point(rr.point());
                    double expected = truth.get(rr.point());
                    double tol = switch (p.type()) {
                        case FLOAT32 -> Math.abs(expected) * 1e-6;
                        case INT32, UINT32 -> p.scale() / 2 + 1e-9;
                        default -> 0;
                    };
                    assertEquals(expected, rr.value(), tol, rr.point());
                    assertEquals(t, rr.timestamp());
                    assertEquals("grid-meter-01", rr.device());
                }
            }
            assertEquals(15, sim.server().requests());
            RawReading v1 = poller.poll(T0.plusSeconds(20)).readings().get(0);
            assertTrue(v1.value() > 10392 && v1.value() < 12702, "voltage " + v1.value());
        }
    }

    @Test
    void episodesPerSeriesAndWindowWithLabels() throws Exception {
        RegisterMap map = RegisterMap.exampleGridMeter();
        List<RawReading> all = new ArrayList<>();
        try (MeterSimulator sim = MeterSimulator.local(map, 7, T0);
             Poller poller = Poller.connect(sim.clientMap())) {
            for (int k = 0; k < 24; k++) {
                Instant t = T0.plusSeconds(5L * k);
                sim.update(t);
                all.addAll(poller.poll(t).readings());
            }
        }
        MeterNormalizer norm = new MeterNormalizer(map, Duration.ofMinutes(1), "SYNTHETIC");
        MeterNormalizer.Result res = norm.normalize(all);
        assertEquals(14 * 2, res.episodes().size());
        assertEquals(24 * 14, res.report().accepted());
        assertEquals(0, res.report().missing() + res.report().counterResets() + res.report().counterWraps());
        Episode e = res.episodes().stream()
                .filter(x -> x.getSeries().getMetric().equals("grid.meter.energy_export")).findFirst().orElseThrow();
        Episodes.validate(e);
        assertEquals(TimeEncoding.REGULAR, e.getTimeEncoding());
        assertEquals(12, e.getCount());
        assertEquals(5_000_000L, e.getIntervalMicros());
        assertEquals("kWh", e.getSeries().getUnit());
        assertEquals(Map.of("device", "grid-meter-01", "meter", "grid-connection", "site", "example-site"), e.getSeries().getTags());
        assertEquals("1-0:2.8.0", e.getLabels().get("obis"));
        assertEquals("SYNTHETIC", e.getLabels().get("origin"));
        assertEquals("counter", e.getLabels().get("kind"));
        assertEquals("true", e.getLabels().get("registermap.example"));
        assertEquals("example-grid-meter@0.1.0", e.getLabels().get("registermap"));
        assertEquals("modbus:grid-meter-01", e.getProvenance().getSource());
        assertNull(e.getQuality());
        double[] v = Episodes.values(e);
        for (int i = 1; i < v.length; i++) {
            assertTrue(v[i] >= v[i - 1], "export counter must not decrease");
        }
        assertEquals("MEASURED", new MeterNormalizer(map, Duration.ofMinutes(1)).normalize(all).episodes().get(0).getLabels().get("origin"));
    }

    @Test
    void counterWrapResetAndMissingAreFlagged() throws Exception {
        RegisterMap map = RegisterMap.exampleGridMeter();
        RegisterPoint imp = map.point("energy_import");
        RegisterPoint volt = map.point("voltage_l1");
        MeterNormalizer norm = new MeterNormalizer(map, Duration.ofMinutes(10));
        List<RawReading> rs = new ArrayList<>();
        try (MeterSimulator sim = MeterSimulator.local(map, 7, T0);
             Poller poller = Poller.connect(sim.clientMap())) {
            double[] raws = {4294967290.0, 4294967295.0, 3, 10, 2, 5};
            for (int k = 0; k < raws.length; k++) {
                sim.image().writeRaw(imp, raws[k]);
                sim.image().write(volt, k == 4 ? 99999 : 11547);
                rs.addAll(poller.poll(T0.plusSeconds(k)).readings());
            }
            sim.server().failNext(4, poller.blocks().size());
            PollResult broken = poller.poll(T0.plusSeconds(6));
            assertEquals(5, broken.errors().size());
            assertTrue(broken.errors().get(0).toString().contains("SERVER_DEVICE_FAILURE"));
            rs.addAll(broken.readings());
        }
        MeterNormalizer.Result res = norm.normalize(rs);
        assertEquals(1, res.report().counterWraps());
        assertEquals(1, res.report().counterResets());
        assertEquals(1, res.report().implausible());
        assertEquals(14, res.report().missing(), "all blocks of the last cycle failed");
        Episode e = res.episodes().stream().filter(x -> x.getSeries().getMetric().equals("grid.meter.energy_import")).findFirst().orElseThrow();
        assertNotNull(e.getQuality());
        byte[] q = new byte[e.getQuality().remaining()];
        e.getQuality().duplicate().get(q);
        assertEquals(List.of(Quality.GOOD, Quality.GOOD, Quality.COUNTER_WRAP, Quality.GOOD, Quality.COUNTER_RESET,
                Quality.GOOD, Quality.MISSING), toList(q));
        assertEquals(0.4, CounterTracker.increment(imp, 4294967295.0, 3, Quality.COUNTER_WRAP), 1e-9);
        assertTrue(Double.isNaN(CounterTracker.increment(imp, 10, 2, Quality.COUNTER_RESET)));
        Episode v = res.episodes().stream().filter(x -> x.getSeries().getMetric().equals("grid.meter.voltage_l1")).findFirst().orElseThrow();
        byte[] vq = new byte[v.getQuality().remaining()];
        v.getQuality().duplicate().get(vq);
        assertEquals(Quality.IMPLAUSIBLE, vq[4]);
        assertEquals(Quality.MISSING, vq[6]);
        assertTrue(Double.isNaN(Episodes.values(v)[6]));
    }

    private static List<Byte> toList(byte[] b) {
        List<Byte> out = new ArrayList<>();
        for (byte x : b) {
            out.add(x);
        }
        return out;
    }

    @Test
    void rtuOverTcpEndToEnd() throws Exception {
        RegisterMap base = RegisterMap.exampleGridMeter();
        RegisterMap rtu = base.withDevice(new org.opentsx.modbus.map.Device("rtu-meter", "127.0.0.1", 502, 9,
                org.opentsx.modbus.protocol.Framing.Kind.RTU_OVER_TCP, Duration.ofSeconds(1), Duration.ofMillis(500), 0, 8, 0));
        try (MeterSimulator sim = MeterSimulator.local(rtu, 1, T0);
             Poller poller = Poller.connect(sim.clientMap())) {
            PollResult r = poller.poll(T0);
            assertTrue(r.ok(), r.errors().toString());
            assertEquals(6, poller.blocks().size(), "8-register limit splits the first input block");
            assertFalse(Double.isNaN(r.readings().get(0).value()));
        }
    }

    @Test
    void runAlignsCyclesToTheInterval() throws Exception {
        RegisterMap map = RegisterMap.exampleGridMeter();
        RegisterMap fast = map.withDevice(new org.opentsx.modbus.map.Device("m", "127.0.0.1", 502, 1,
                org.opentsx.modbus.protocol.Framing.Kind.TCP, Duration.ofMillis(100), Duration.ofSeconds(1), 0, 125, 0));
        try (MeterSimulator sim = MeterSimulator.local(fast, 1, Instant.now());
             Poller poller = Poller.connect(sim.clientMap())) {
            List<Instant> ts = new ArrayList<>();
            poller.run(3, r -> ts.add(r.timestamp()));
            assertEquals(3, ts.size());
            for (Instant t : ts) {
                assertEquals(0, t.toEpochMilli() % 100);
            }
            assertEquals(100, ts.get(2).toEpochMilli() - ts.get(1).toEpochMilli());
        }
    }

    @Test
    void cliCheckAndRead() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ModbusCli.check(RegisterMap.exampleGridMeter(), new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("read plan (5 requests per cycle)"), out);
        assertTrue(out.contains("energy_export"), out);

        buf.reset();
        int rc = ModbusCli.read(RegisterMap.exampleGridMeter(), Map.of("simulate", "true"), new PrintStream(buf, true, StandardCharsets.UTF_8));
        out = buf.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc, out);
        assertTrue(out.contains("1-0:16.7.0"), out);
        assertFalse(out.contains("MISSING"), out);
    }

    @Test
    void cliPollWritesCsv() throws Exception {
        RegisterMap map = RegisterMap.exampleGridMeter();
        RegisterMap fast = map.withDevice(new org.opentsx.modbus.map.Device("m", "127.0.0.1", 502, 1,
                org.opentsx.modbus.protocol.Framing.Kind.TCP, Duration.ofMillis(100), Duration.ofSeconds(1), 0, 125, 0));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int rc = ModbusCli.poll(fast, Map.of("simulate", "true", "count", "2"), new PrintStream(buf, true, StandardCharsets.UTF_8));
        String[] lines = buf.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(0, rc);
        assertEquals(1 + 2 * 14, lines.length);
        assertTrue(lines[1].contains(",SYNTHETIC,GOOD"), lines[1]);

        buf.reset();
        rc = ModbusCli.poll(fast, Map.of("simulate", "true", "count", "3", "out", "episodes", "episode", "PT1M"),
                new PrintStream(buf, true, StandardCharsets.UTF_8));
        assertEquals(0, rc);
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("grid.meter.frequency"), buf.toString(StandardCharsets.UTF_8));
    }
}
