package org.opentsx.modbus.poll;

import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.protocol.FunctionCode;

import java.util.List;

/** One read request covering one or more points: {@code quantity} registers (or bits) from {@code start}. */
public record ReadBlock(FunctionCode function, int start, int quantity, List<RegisterPoint> points) {

    public int end() {
        return start + quantity;
    }

    @Override
    public String toString() {
        return function.yamlName() + " " + start + ".." + (end() - 1) + " (" + quantity + ", " + points.size() + " points)";
    }
}
