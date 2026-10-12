package org.opentsx.windpark.sim;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A reproducible wind-farm experiment (YAML). Times of operations and sensor faults are offsets from {@code start}.
 * Same scenario and seed produce identical episodes (including ids).
 */
public record Scenario(String name, long seed, Instant start, Duration duration, Duration sampleInterval,
                       Duration episodeDuration, ParkSpec park, WindSpec wind, List<Operation> operations,
                       List<SensorFault> sensorFaults) {

    public Scenario {
        sampleInterval = sampleInterval == null ? Duration.ofSeconds(1) : sampleInterval;
        episodeDuration = episodeDuration == null ? Duration.ofMinutes(10) : episodeDuration;
        operations = operations == null ? List.of() : List.copyOf(operations);
        sensorFaults = sensorFaults == null ? List.of() : List.copyOf(sensorFaults);
        wind = wind == null ? WindSpec.DEFAULT : wind;
        if (name == null || start == null || duration == null || park == null) {
            throw new IllegalArgumentException("scenario needs name, start, duration and park");
        }
        long ep = episodeDuration.toNanos();
        if (ep % sampleInterval.toNanos() != 0) {
            throw new IllegalArgumentException("episodeDuration must be a multiple of sampleInterval");
        }
        if (Math.floorMod(start.toEpochMilli() * 1_000_000L, ep) != 0) {
            throw new IllegalArgumentException("start must be aligned to episodeDuration (e.g. a full 10 minutes)");
        }
    }

    public record ParkSpec(String id, String name, double latitude, double longitude, String turbineModel,
                           int rows, int columns, double spacingRotorDiameters, double rowSpacingRotorDiameters,
                           double wakeDecay) {
        public ParkSpec {
            turbineModel = turbineModel == null ? "GENERIC-3MW" : turbineModel;
            rows = rows <= 0 ? 1 : rows;
            columns = columns <= 0 ? 1 : columns;
            spacingRotorDiameters = spacingRotorDiameters <= 0 ? 5 : spacingRotorDiameters;
            rowSpacingRotorDiameters = rowSpacingRotorDiameters <= 0 ? 7 : rowSpacingRotorDiameters;
            wakeDecay = wakeDecay <= 0 ? 0.05 : wakeDecay;
        }
    }

    /**
     * @param weibullK / weibullC   distribution of hourly mean wind speeds at hub height (scale c in m/s)
     * @param diurnalAmplitude      relative day/night modulation of the mean wind
     */
    public record WindSpec(double weibullK, double weibullC, double turbulenceIntensity, double meanDirectionDeg,
                           double directionStdDeg, double diurnalAmplitude, double meanTemperatureC) {
        public static final WindSpec DEFAULT = new WindSpec(2.0, 9.0, 0.10, 240, 15, 0.1, 10);
    }

    public enum OperationType { CURTAILMENT, FAULT, MAINTENANCE }

    /** @param turbines empty or ["*"] = all turbines */
    public record Operation(OperationType type, List<String> turbines, Duration offset, Duration duration, Double limitKw) {
        public Operation {
            turbines = turbines == null ? List.of() : List.copyOf(turbines);
        }

        boolean applies(String turbine) {
            return turbines.isEmpty() || turbines.contains("*") || turbines.contains(turbine);
        }
    }

    public enum SensorFaultType { STUCK, DRIFT, OFFSET, SPIKES, GAP }

    /**
     * @param ratePerHour DRIFT: change per hour in channel units
     * @param magnitude   OFFSET: constant offset; SPIKES: spike height
     * @param probability SPIKES: probability per sample
     */
    public record SensorFault(SensorFaultType type, String turbine, String channel, Duration offset, Duration duration,
                              Double ratePerHour, Double magnitude, Double probability) {
    }

    public static Scenario load(Path yaml) {
        try (InputStream in = Files.newInputStream(yaml)) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Scenario load(InputStream yaml) throws IOException {
        ObjectMapper m = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return m.readValue(yaml, Scenario.class);
    }
}
