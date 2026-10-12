package org.opentsx.modbus;

import org.junit.jupiter.api.Test;
import org.opentsx.modbus.map.DataType;
import org.opentsx.modbus.map.RegisterMap;
import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.map.WordOrder;
import org.opentsx.modbus.poll.BlockPlanner;
import org.opentsx.modbus.poll.ReadBlock;
import org.opentsx.modbus.protocol.FunctionCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPlannerTest {

    static RegisterPoint p(String name, FunctionCode f, int address, DataType t) {
        return new RegisterPoint(name, name, null, address, f, t, WordOrder.BIG, false, 1, 0, null, null, "MEASURED",
                RegisterPoint.Kind.GAUGE, null, null, Map.of(), null);
    }

    static RegisterPoint h(String name, int address, DataType t) {
        return p(name, FunctionCode.READ_HOLDING_REGISTERS, address, t);
    }

    @Test
    void adjacentPointsShareOneRequest() {
        List<ReadBlock> b = BlockPlanner.plan(List.of(h("c", 4, DataType.UINT16), h("a", 0, DataType.FLOAT32),
                h("b", 2, DataType.INT32), h("d", 5, DataType.FLOAT64)), 125, 0);
        assertEquals(1, b.size());
        assertEquals(0, b.get(0).start());
        assertEquals(9, b.get(0).quantity());
        assertEquals(List.of("a", "b", "c", "d"), b.get(0).points().stream().map(RegisterPoint::name).toList());
    }

    @Test
    void gapsSplitUnlessAllowed() {
        List<RegisterPoint> pts = List.of(h("a", 0, DataType.FLOAT32), h("b", 5, DataType.FLOAT32), h("c", 20, DataType.UINT16));
        assertEquals(3, BlockPlanner.plan(pts, 125, 0).size());
        List<ReadBlock> gap3 = BlockPlanner.plan(pts, 125, 3);
        assertEquals(2, gap3.size());
        assertEquals(7, gap3.get(0).quantity());
        assertEquals(1, BlockPlanner.plan(pts, 125, 20).size());
    }

    @Test
    void respectsTheRegisterLimitAndNeverSplitsAPoint() {
        List<RegisterPoint> pts = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            pts.add(h("f" + i, 2 * i, DataType.FLOAT32));
        }
        List<ReadBlock> b = BlockPlanner.plan(pts, 125, 0);
        assertEquals(2, b.size());
        assertEquals(124, b.get(0).quantity());
        assertEquals(62, b.get(0).points().size());
        assertEquals(124, b.get(1).start());
        assertEquals(16, b.get(1).quantity());
        for (ReadBlock r : b) {
            assertTrue(r.quantity() <= 125);
            for (RegisterPoint p : r.points()) {
                assertTrue(p.address() >= r.start() && p.end() <= r.end());
            }
        }
        List<ReadBlock> small = BlockPlanner.plan(pts, 10, 0);
        assertEquals(14, small.size());
        assertTrue(small.stream().allMatch(r -> r.quantity() <= 10));
    }

    @Test
    void functionsAreNeverMixed() {
        List<ReadBlock> b = BlockPlanner.plan(List.of(h("a", 0, DataType.UINT16),
                p("b", FunctionCode.READ_INPUT_REGISTERS, 1, DataType.UINT16),
                p("c", FunctionCode.READ_COILS, 0, DataType.BOOL), p("d", FunctionCode.READ_COILS, 1, DataType.BOOL)), 125, 0);
        assertEquals(3, b.size());
        assertEquals(FunctionCode.READ_COILS, b.get(0).function());
        assertEquals(2, b.get(0).quantity());
    }

    @Test
    void exampleMapNeedsFiveRequests() {
        RegisterMap m = RegisterMap.exampleGridMeter();
        List<ReadBlock> b = BlockPlanner.plan(m.points(), 125, 0);
        assertEquals(5, b.size(), b.toString());
        assertEquals(1, BlockPlanner.plan(m.points().stream().filter(p -> p.function() == FunctionCode.READ_INPUT_REGISTERS).toList(), 125, 4).size());
    }
}
