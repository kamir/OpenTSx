package org.opentsx.modbus.poll;

import org.opentsx.modbus.map.RegisterPoint;
import org.opentsx.modbus.protocol.FunctionCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plans as few read requests as possible: per function, points sorted by address are merged into one block while
 * the gap to the previous point is at most {@code maxGap} registers and the block stays within
 * {@code maxRegisters} (125 at most for registers; bit functions use their protocol limit of 2000 and never span gaps
 * larger than {@code maxGap} either). A point is never split across two blocks.
 */
public final class BlockPlanner {

    private BlockPlanner() {
    }

    public static List<ReadBlock> plan(List<RegisterPoint> points, int maxRegisters, int maxGap) {
        if (maxRegisters < 4 || maxRegisters > 125) {
            throw new IllegalArgumentException("maxRegisters " + maxRegisters + " outside 4..125");
        }
        if (maxGap < 0) {
            throw new IllegalArgumentException("maxGap must be >= 0");
        }
        Map<FunctionCode, List<RegisterPoint>> byFunction = new TreeMap<>();
        for (RegisterPoint p : points) {
            byFunction.computeIfAbsent(p.function(), k -> new ArrayList<>()).add(p);
        }
        List<ReadBlock> out = new ArrayList<>();
        byFunction.forEach((fc, list) -> {
            int limit = fc.bits() ? fc.maxQuantity() : maxRegisters;
            List<RegisterPoint> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparingInt(RegisterPoint::address).thenComparingInt(RegisterPoint::end));
            int start = -1;
            int end = -1;
            List<RegisterPoint> current = new ArrayList<>();
            for (RegisterPoint p : sorted) {
                boolean fits = start >= 0
                        && p.address() - end <= maxGap
                        && Math.max(end, p.end()) - start <= limit;
                if (!fits) {
                    if (start >= 0) {
                        out.add(new ReadBlock(fc, start, end - start, List.copyOf(current)));
                    }
                    start = p.address();
                    end = p.end();
                    current = new ArrayList<>();
                } else {
                    end = Math.max(end, p.end());
                }
                current.add(p);
            }
            if (start >= 0) {
                out.add(new ReadBlock(fc, start, end - start, List.copyOf(current)));
            }
        });
        return out;
    }
}
