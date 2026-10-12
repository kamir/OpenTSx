package org.opentsx.modbus;

import org.junit.jupiter.api.Test;
import org.opentsx.modbus.map.DataType;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterMapException;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.map.WordOrder;
import org.opentsx.modbus.protocol.Framing;
import org.opentsx.modbus.protocol.FunctionCode;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterMapTest {

    static final String HEAD = String.join("\n",
            "schema: opentsx.modbus.registermap.v1",
            "name: t",
            "device: { id: m1, host: 127.0.0.1, port: 1502, unitId: 7, pollInterval: 2s }",
            "defaults: { function: holding }",
            "points:", "");

    static RegisterMapException invalid(String yaml) {
        return assertThrows(RegisterMapException.class, () -> RegisterMap.parse(yaml));
    }

    static void assertProblem(RegisterMapException e, String fragment) {
        assertTrue(e.problems().stream().anyMatch(p -> p.contains(fragment)), "no problem containing '" + fragment + "' in " + e.problems());
    }

    @Test
    void exampleMapLoadsWithAllFeatures() {
        RegisterMap m = RegisterMap.exampleGridMeter();
        assertTrue(m.example());
        assertEquals("grid-meter-01", m.device().id());
        assertEquals(Duration.ofSeconds(5), m.device().pollInterval());
        assertEquals(14, m.points().size());
        RegisterPoint net = m.point("active_power_net");
        assertEquals(FunctionCode.READ_HOLDING_REGISTERS, net.function());
        assertEquals(DataType.INT32, net.type());
        assertEquals(WordOrder.LITTLE, net.wordOrder());
        assertEquals(0.001, net.scale());
        assertEquals("1-0:16.7.0", net.obis());
        assertEquals("grid.meter.active_power_net", net.metric());
        assertEquals(RegisterPoint.Kind.COUNTER, m.point("energy_export").kind());
        assertEquals("MEASURED", m.point("frequency").origin());
        assertEquals(FunctionCode.READ_INPUT_REGISTERS, m.point("frequency").function());
    }

    @Test
    void defaultsAndOneBasedAddresses() {
        RegisterMap m = RegisterMap.parse(HEAD.replace("{ function: holding }", "{ function: holding, addressBase: 1, wordOrder: little, byteSwap: true, origin: reported }")
                + "  - { name: a, address: 1, type: float32 }\n"
                + "  - { name: b, address: 3, type: uint16, wordOrder: big, byteSwap: false, function: 4, metric: x.b }\n");
        assertEquals(0, m.point("a").address());
        assertEquals(WordOrder.LITTLE, m.point("a").wordOrder());
        assertTrue(m.point("a").byteSwap());
        assertEquals("REPORTED", m.point("a").origin());
        assertEquals(FunctionCode.READ_INPUT_REGISTERS, m.point("b").function());
        assertEquals(2, m.point("b").address());
        assertEquals("x.b", m.point("b").metric());
        assertEquals(7, m.device().unitId());
        assertEquals(Framing.Kind.TCP, m.device().framing());
        assertEquals(Duration.ofSeconds(2), m.device().pollInterval());
    }

    @Test
    void overlapsAreReportedWithBothPoints() {
        RegisterMapException e = invalid(HEAD
                + "  - { name: a, address: 10, type: float32 }\n"
                + "  - { name: b, address: 11, type: uint16 }\n"
                + "  - { name: c, address: 11, type: uint16, function: input }\n");
        assertEquals(1, e.problems().size(), e.problems().toString());
        assertProblem(e, "points 'a' (holding 10..11, float32) and 'b' (holding 11..11, uint16) overlap");
    }

    @Test
    void badTypesAndFunctions() {
        RegisterMapException e = invalid(HEAD
                + "  - { name: a, address: 0, type: float16 }\n"
                + "  - { name: b, address: 2, type: bool }\n"
                + "  - { name: c, address: 0, type: uint16, function: coil }\n"
                + "  - { name: d, address: 3, type: uint16, function: write }\n"
                + "  - { name: e, address: 4, type: uint16, wordOrder: middle }\n");
        assertProblem(e, "points[0] 'a'.type: unknown type 'float16'");
        assertProblem(e, "type bool needs function coil or discrete");
        assertProblem(e, "function coil returns bits, so type must be bool");
        assertProblem(e, "unknown function 'write'");
        assertProblem(e, "unknown wordOrder 'middle'");
        assertEquals(5, e.problems().size(), e.problems().toString());
    }

    @Test
    void everyProblemIsListedAtOnce() {
        RegisterMapException e = invalid(String.join("\n",
                "schema: opentsx.modbus.registermap.v2",
                "name: t",
                "device: { id: 'm 1', host: '', port: 70000, unitId: 300, pollInterval: soon, maxRegistersPerRequest: 200 }",
                "points:",
                "  - { name: a, address: 400001, type: uint16, function: holding }",
                "  - { name: a, address: 1, type: uint16, function: holding, scale: 0, obis: '1.8.0', origin: guessed }",
                "  - { name: c, type: uint16, kind: meter, function: holding, min: 5, max: 1 }",
                "  - { name: d, address: 65535, type: uint32, function: holding }",
                "  - { name: e, address: 7, type: uint16 }",
                ""));
        assertProblem(e, "schema: 'opentsx.modbus.registermap.v2' is not supported");
        assertProblem(e, "device.id: 'm 1'");
        assertProblem(e, "device.host: missing");
        assertProblem(e, "device.port: 70000");
        assertProblem(e, "device.unitId: 300");
        assertProblem(e, "device.pollInterval: 'soon' is not a duration");
        assertProblem(e, "device.maxRegistersPerRequest: 200");
        assertProblem(e, "use the offset, e.g. 40001 -> 0");
        assertProblem(e, "duplicate name");
        assertProblem(e, "scale must be a finite non-zero number");
        assertProblem(e, "obis '1.8.0' is not an OBIS code");
        assertProblem(e, "origin: 'guessed'");
        assertProblem(e, "address missing");
        assertProblem(e, "kind: 'meter'");
        assertProblem(e, "min 5.0 > max 1.0");
        assertProblem(e, "runs past address 65535");
        assertProblem(e, "points[4] 'e': function missing");
        assertTrue(e.getMessage().startsWith("register map <string> is invalid:"));
    }

    @Test
    void unknownFieldsAndBrokenYaml() {
        RegisterMapException e = invalid(HEAD + "  - { name: a, adress: 0, type: uint16 }\n");
        assertTrue(e.problems().get(0).startsWith("points[0]"), e.problems().toString());
        assertTrue(e.problems().get(0).contains("adress"), e.problems().toString());
        assertProblem(invalid("name: x\n  bad: indent\n"), "not valid YAML");
        assertProblem(invalid("name: x\n"), "schema: missing");
        assertProblem(invalid(HEAD), "at least one point");
    }

    @Test
    void simulatorReferencesAreChecked() {
        RegisterMapException e = invalid(HEAD
                + "  - { name: a, address: 0, type: uint32, sim: { profile: integrate, source: nope } }\n"
                + "  - { name: b, address: 2, type: uint16, sim: { profile: zigzag } }\n");
        assertProblem(e, "sim.profile: 'zigzag'");
        RegisterMapException e2 = invalid(HEAD
                + "  - { name: a, address: 0, type: uint32, sim: { profile: integrate, source: nope } }\n");
        assertProblem(e2, "needs source = an existing point");
    }

    @Test
    void durations() {
        assertEquals(Duration.ofMillis(500), RegisterMap.duration("500ms"));
        assertEquals(Duration.ofMinutes(1), RegisterMap.duration("1m"));
        assertEquals(Duration.ofSeconds(5), RegisterMap.duration("PT5S"));
        assertThrows(IllegalArgumentException.class, () -> RegisterMap.duration("5 parsecs"));
    }
}
