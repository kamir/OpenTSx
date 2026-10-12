package org.opentsx.modbus.map;

/**
 * Optional simulator profile of a point (ignored by the poller). Profiles:
 * <ul>
 *   <li>{@code constant}: {@code value}</li>
 *   <li>{@code sine}: {@code mean + amplitude * sin(2 pi (t / period + phase)) + noise * N(0,1)}, clamped to min/max</li>
 *   <li>{@code clamp}: the positive ({@code sign: positive}) or negated negative part of another point
 *   ({@code source}) - e.g. import/export power from a signed net power</li>
 *   <li>{@code integrate}: {@code start + factor * integral(source dt [s])} - an energy counter; wraps like the
 *   register type would</li>
 * </ul>
 */
public record SimSpec(String profile, Double value, Double mean, Double amplitude, String period, Double phase,
                      Double noise, Double min, Double max, String source, String sign, Double factor, Double start) {
}
