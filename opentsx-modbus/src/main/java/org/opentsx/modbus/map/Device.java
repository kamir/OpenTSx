package org.opentsx.modbus.map;

import org.opentsx.modbus.protocol.Framing;

import java.time.Duration;

/**
 * Where and how to poll.
 *
 * @param maxRegistersPerRequest block size limit (protocol maximum 125; some devices accept less)
 * @param maxGap                 unused registers a merged block may span between two points (0 = only adjacent
 *                               points are merged; many meters reject reads of undefined registers)
 */
public record Device(String id, String host, int port, int unitId, Framing.Kind framing, Duration pollInterval,
                     Duration timeout, int retries, int maxRegistersPerRequest, int maxGap) {
}
