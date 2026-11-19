package org.opentsx.windpark.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
        positive("duration", duration, "PT6H");
        positive("sampleInterval", sampleInterval, "PT1S");
        positive("episodeDuration", episodeDuration, "PT10M");
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
            if (rows < 1) {
                throw new IllegalArgumentException("park: rows missing or below 1; set rows to 1 or more");
            }
            if (columns < 1) {
                throw new IllegalArgumentException("park: columns missing or below 1; set columns to 1 or more");
            }
            String diameters = "a positive number of rotor diameters";
            spacingRotorDiameters = orDefault("spacingRotorDiameters", spacingRotorDiameters, diameters, 5);
            rowSpacingRotorDiameters = orDefault("rowSpacingRotorDiameters", rowSpacingRotorDiameters, diameters, 7);
            wakeDecay = orDefault("wakeDecay", wakeDecay, "a positive decay constant", 0.05);
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

    /**
     * @param turbines empty or ["*"] = all turbines
     * @param limitKw  required by CURTAILMENT, refused by the other types
     */
    public record Operation(OperationType type, List<String> turbines, Duration offset, Duration duration, Double limitKw) {
        public Operation {
            turbines = turbines == null ? List.of() : List.copyOf(turbines);
            if (type == null) {
                throw new IllegalArgumentException(
                        "operation: type missing; set type to one of CURTAILMENT, FAULT, MAINTENANCE");
            }
            String subject = type + " operation" + (offset == null ? "" : " at " + offset);
            window(subject, offset, duration);
            String allowed = "set limitKw to the power limit in kW, 0 or more";
            parameter(subject, "limitKw", limitKw, type == OperationType.CURTAILMENT, "CURTAILMENT", allowed);
            if (limitKw != null && limitKw < 0) {
                throw new IllegalArgumentException(subject + ": limitKw " + limitKw + " is negative; " + allowed);
            }
        }

        boolean applies(String turbine) {
            return turbines.isEmpty() || turbines.contains("*") || turbines.contains(turbine);
        }
    }

    public enum SensorFaultType { STUCK, DRIFT, OFFSET, SPIKES, GAP }

    /**
     * Each parameter is required by the types it names and refused by the others.
     *
     * @param ratePerHour DRIFT: change per hour in channel units
     * @param magnitude   OFFSET: constant offset; SPIKES: spike height
     * @param probability SPIKES: probability per sample
     */
    public record SensorFault(SensorFaultType type, String turbine, String channel, Duration offset, Duration duration,
                              Double ratePerHour, Double magnitude, Double probability) {
        public SensorFault {
            if (type == null) {
                throw new IllegalArgumentException(
                        "sensor fault: type missing; set type to one of STUCK, DRIFT, OFFSET, SPIKES, GAP");
            }
            if (turbine == null) {
                throw new IllegalArgumentException(type + " sensor fault: turbine missing; set turbine to a turbine id such as T01");
            }
            if (channel == null) {
                throw new IllegalArgumentException(type + " sensor fault of " + turbine
                        + ": channel missing; set channel to a metric of the catalog such as wind.met.wind_speed");
            }
            String subject = type + " sensor fault of " + turbine + " " + channel + (offset == null ? "" : " at " + offset);
            window(subject, offset, duration);
            parameter(subject, "ratePerHour", ratePerHour, type == SensorFaultType.DRIFT, "DRIFT",
                    "set ratePerHour to the change per hour in the unit of the channel");
            parameter(subject, "magnitude", magnitude, type == SensorFaultType.OFFSET || type == SensorFaultType.SPIKES,
                    "OFFSET and SPIKES", "set magnitude to the " + (type == SensorFaultType.OFFSET ? "offset" : "spike height")
                            + " in the unit of the channel");
            String share = "set probability to the share of samples with a spike, above 0 and at most 1";
            parameter(subject, "probability", probability, type == SensorFaultType.SPIKES, "SPIKES", share);
            if (probability != null && !(probability > 0 && probability <= 1)) {
                throw new IllegalArgumentException(subject + ": probability " + probability + " is not allowed; " + share);
            }
        }
    }

    private static void positive(String name, Duration value, String example) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " " + value + " is not positive; set " + name
                    + " to a positive length, such as " + example);
        }
    }

    /** A missing park number reads as 0 and takes the default; a negative or non-finite one is refused. */
    private static double orDefault(String name, double value, String allowed, double fallback) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("park: " + name + " " + value + " is not allowed; set " + name + " to "
                    + allowed + " or leave it out for " + BigDecimal.valueOf(fallback).stripTrailingZeros().toPlainString());
        }
        return value == 0 ? fallback : value;
    }

    private static void window(String subject, Duration offset, Duration duration) {
        if (offset == null) {
            throw new IllegalArgumentException(subject + ": offset missing; set offset to the time after start, such as PT1H");
        }
        if (offset.isNegative()) {
            throw new IllegalArgumentException(subject + ": offset " + offset + " is negative; set offset to PT0S or later");
        }
        if (duration == null) {
            throw new IllegalArgumentException(subject + ": duration missing; set duration to a positive length, such as PT30M");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(subject + ": duration " + duration
                    + " is not positive; set duration to a positive length, such as PT30M");
        }
    }

    private static void parameter(String subject, String name, Double value, boolean required, String owners, String allowed) {
        if (required && value == null) {
            throw new IllegalArgumentException(subject + ": " + name + " missing; " + allowed);
        }
        if (!required && value != null) {
            throw new IllegalArgumentException(subject + ": " + name + " belongs to " + owners + " only; remove it");
        }
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(subject + ": " + name + " " + value + " is not a finite number; " + allowed);
        }
    }

    /**
     * A missing file or a scenario that breaks a rule fails with an {@link IllegalArgumentException}
     * {@code <file>: <problem>}; a file that cannot be read fails with an {@link UncheckedIOException}.
     */
    public static Scenario load(Path yaml) {
        try (InputStream in = Files.newInputStream(yaml)) {
            return load(in);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException(yaml + ": no such file; give an existing file", e);
        } catch (JsonProcessingException e) {
            String problem = e.getCause() instanceof IllegalArgumentException rule ? rule.getMessage() : e.getOriginalMessage();
            throw new IllegalArgumentException(yaml + ": " + problem.replaceAll("\\s+", " "), e);
        } catch (IOException e) {
            throw new UncheckedIOException(yaml + ": cannot read the scenario", e);
        }
    }

    public static Scenario load(InputStream yaml) throws IOException {
        ObjectMapper m = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return m.readValue(yaml, Scenario.class);
    }
}
