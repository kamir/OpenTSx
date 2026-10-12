package org.opentsx.modbus.map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.opentsx.modbus.protocol.FunctionCode;
import org.opentsx.modbus.protocol.Framing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A device's register map ({@code schema: opentsx.modbus.registermap.v1}): connection, poll interval and points
 * with address, function, type, word order, scaling, unit, OBIS code and origin. {@link #load(Path)} validates the
 * whole file and reports every problem at once ({@link RegisterMapException}).
 */
public final class RegisterMap {

    public static final String SCHEMA = "opentsx.modbus.registermap.v1";
    public static final Set<String> ORIGINS = Set.of("MEASURED", "REPORTED", "FORECAST", "ESTIMATED", "SYNTHETIC");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern OBIS = Pattern.compile("\\d{1,3}-\\d{1,3}:\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(\\*\\d{1,3})?");
    private static final Set<String> PROFILES = Set.of("constant", "sine", "clamp", "integrate");

    private final String name;
    private final String version;
    private final boolean example;
    private final String description;
    private final Device device;
    private final Map<String, String> tags;
    private final List<RegisterPoint> points;
    private final Map<String, RegisterPoint> byName = new LinkedHashMap<>();

    public RegisterMap(String name, String version, boolean example, String description, Device device,
                       Map<String, String> tags, List<RegisterPoint> points) {
        this.name = name;
        this.version = version;
        this.example = example;
        this.description = description;
        this.device = device;
        this.tags = tags == null ? Map.of() : Map.copyOf(tags);
        this.points = List.copyOf(points);
        for (RegisterPoint p : points) {
            byName.put(p.name(), p);
        }
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** True for maps that are templates, not a real register sheet; episodes get the label {@code registermap.example=true}. */
    public boolean example() {
        return example;
    }

    public String description() {
        return description;
    }

    public Device device() {
        return device;
    }

    public Map<String, String> tags() {
        return tags;
    }

    public List<RegisterPoint> points() {
        return points;
    }

    public RegisterPoint point(String name) {
        RegisterPoint p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException("no point '" + name + "' in register map " + this.name);
        }
        return p;
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    /** Same map, other endpoint (CLI overrides, tests against the simulator). */
    public RegisterMap withEndpoint(String host, Integer port, Integer unitId) {
        Device d = device;
        Device nd = new Device(d.id(), host != null ? host : d.host(), port != null ? port : d.port(),
                unitId != null ? unitId : d.unitId(), d.framing(), d.pollInterval(), d.timeout(), d.retries(),
                d.maxRegistersPerRequest(), d.maxGap());
        return new RegisterMap(name, version, example, description, nd, tags, points);
    }

    public RegisterMap withDevice(Device d) {
        return new RegisterMap(name, version, example, description, d, tags, points);
    }

    // ---------------------------------------------------------------- loading

    /** The shipped example grid-meter map (a template until the real register sheets are available). */
    public static RegisterMap exampleGridMeter() {
        return resource("/modbus/example-grid-meter.yaml");
    }

    public static RegisterMap resource(String path) {
        try (InputStream in = RegisterMap.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("resource " + path + " not found");
            }
            return load(in, "classpath:" + path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code example} loads the shipped example; anything else is a file path. */
    public static RegisterMap load(String pathOrExample) {
        return "example".equals(pathOrExample) ? exampleGridMeter() : load(Path.of(pathOrExample));
    }

    public static RegisterMap load(Path yaml) {
        try (InputStream in = Files.newInputStream(yaml)) {
            return load(in, yaml.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read register map " + yaml + ": " + e.getMessage(), e);
        }
    }

    public static RegisterMap parse(String yaml) {
        try {
            return load(new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "<string>");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static RegisterMap load(InputStream yaml, String source) throws IOException {
        ObjectMapper m = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        MapFile f;
        try {
            f = m.readValue(yaml, MapFile.class);
        } catch (JsonMappingException e) {
            throw new RegisterMapException(source, List.of(location(e) + e.getOriginalMessage()));
        } catch (JsonProcessingException e) {
            throw new RegisterMapException(source, List.of("not valid YAML: " + e.getOriginalMessage()
                    + (e.getLocation() != null ? " (line " + e.getLocation().getLineNr() + ")" : "")));
        }
        if (f == null) {
            throw new RegisterMapException(source, List.of("file is empty"));
        }
        return new Validator(source).build(f);
    }

    private static String location(JsonMappingException e) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference r : e.getPath()) {
            if (r.getFieldName() != null) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                sb.append('[').append(r.getIndex()).append(']');
            }
        }
        String line = e.getLocation() != null ? " (line " + e.getLocation().getLineNr() + ")" : "";
        return sb.length() == 0 ? "" : sb + line + ": ";
    }

    // ---------------------------------------------------------------- file format

    record MapFile(String schema, String name, String version, Boolean example, String description, DeviceFile device,
                   Map<String, String> tags, DefaultsFile defaults, List<PointFile> points) {
    }

    record DeviceFile(String id, String host, Integer port, Integer unitId, String framing, String pollInterval,
                      String timeout, Integer retries, Integer maxRegistersPerRequest, Integer maxGap) {
    }

    record DefaultsFile(String function, String wordOrder, Boolean byteSwap, String origin, Integer addressBase,
                        String metricPrefix) {
    }

    record PointFile(String name, String metric, String description, Integer address, String function, String type,
                     String wordOrder, Boolean byteSwap, Double scale, Double offset, String unit, String obis,
                     String origin, String kind, Double min, Double max, Map<String, String> tags, SimSpec sim) {
    }

    /** ISO-8601 ({@code PT5S}) or short form ({@code 500ms}, {@code 5s}, {@code 1m}, {@code 1h}). */
    public static Duration duration(String s) {
        String v = s.trim();
        try {
            if (v.toUpperCase(Locale.ROOT).startsWith("P")) {
                return Duration.parse(v.toUpperCase(Locale.ROOT));
            }
            var mt = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)").matcher(v.toLowerCase(Locale.ROOT));
            if (mt.matches()) {
                double n = Double.parseDouble(mt.group(1));
                double millis = switch (mt.group(2)) {
                    case "ms" -> n;
                    case "s" -> n * 1_000;
                    case "m" -> n * 60_000;
                    default -> n * 3_600_000;
                };
                return Duration.ofMillis(Math.round(millis));
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        throw new IllegalArgumentException("'" + s + "' is not a duration (use PT5S, 500ms, 5s, 1m, 1h)");
    }

    // ---------------------------------------------------------------- validation

    private static final class Validator {
        private final String source;
        private final List<String> problems = new ArrayList<>();

        Validator(String source) {
            this.source = source;
        }

        RegisterMap build(MapFile f) {
            if (f.schema() == null) {
                problems.add("schema: missing (expected '" + SCHEMA + "')");
            } else if (!SCHEMA.equals(f.schema())) {
                problems.add("schema: '" + f.schema() + "' is not supported (expected '" + SCHEMA + "')");
            }
            if (f.name() == null || f.name().isBlank()) {
                problems.add("name: missing");
            }
            Device device = device(f.device());
            DefaultsFile d = f.defaults() != null ? f.defaults() : new DefaultsFile(null, null, null, null, null, null);
            FunctionCode defFunction = d.function() == null ? null : parse("defaults.function", () -> FunctionCode.parse(d.function()));
            WordOrder defOrder = d.wordOrder() == null ? WordOrder.BIG : parse("defaults.wordOrder", () -> WordOrder.parse(d.wordOrder()));
            boolean defSwap = d.byteSwap() != null && d.byteSwap();
            String defOrigin = origin("defaults.origin", d.origin(), "MEASURED");
            int base = d.addressBase() == null ? 0 : d.addressBase();
            if (base != 0 && base != 1) {
                problems.add("defaults.addressBase: " + base + " (expected 0 or 1)");
            }
            if (f.tags() != null) {
                f.tags().forEach((k, v) -> {
                    if (v == null) {
                        problems.add("tags." + k + ": value missing");
                    }
                });
            }

            List<RegisterPoint> points = new ArrayList<>();
            List<PointFile> files = f.points() == null ? List.of() : f.points();
            if (files.isEmpty()) {
                problems.add("points: at least one point is required");
            }
            Set<String> names = new HashSet<>();
            for (int i = 0; i < files.size(); i++) {
                PointFile p = files.get(i);
                if (p == null) {
                    problems.add("points[" + i + "]: empty entry");
                    continue;
                }
                String at = "points[" + i + "]" + (p.name() != null ? " '" + p.name() + "'" : "");
                RegisterPoint rp = point(at, p, defFunction, defOrder, defSwap, defOrigin, base, d.metricPrefix());
                if (p.name() != null && !names.add(p.name())) {
                    problems.add(at + ": duplicate name");
                }
                if (rp != null) {
                    points.add(rp);
                }
            }
            if (problems.isEmpty()) {
                overlaps(points);
                sims(points);
            }
            if (!problems.isEmpty()) {
                throw new RegisterMapException(source, problems);
            }
            return new RegisterMap(f.name(), f.version() == null ? "0" : f.version(),
                    f.example() != null && f.example(), f.description(), device, f.tags(), points);
        }

        private Device device(DeviceFile d) {
            if (d == null) {
                problems.add("device: missing (id, host, port, unitId, pollInterval)");
                return null;
            }
            if (d.id() == null || !NAME.matcher(d.id()).matches()) {
                problems.add("device.id: " + (d.id() == null ? "missing" : "'" + d.id() + "' may only contain letters, digits, _ . -"));
            }
            if (d.host() == null || d.host().isBlank()) {
                problems.add("device.host: missing");
            }
            int port = d.port() == null ? 502 : d.port();
            if (port < 1 || port > 65535) {
                problems.add("device.port: " + port + " outside 1..65535");
            }
            int unit = d.unitId() == null ? 1 : d.unitId();
            if (unit < 0 || unit > 255) {
                problems.add("device.unitId: " + unit + " outside 0..255");
            }
            Framing.Kind framing = d.framing() == null ? Framing.Kind.TCP : parse("device.framing", () -> Framing.Kind.parse(d.framing()));
            Duration poll = d.pollInterval() == null ? Duration.ofSeconds(10) : parse("device.pollInterval", () -> duration(d.pollInterval()));
            Duration timeout = d.timeout() == null ? Duration.ofSeconds(2) : parse("device.timeout", () -> duration(d.timeout()));
            if (poll != null && (poll.isZero() || poll.isNegative())) {
                problems.add("device.pollInterval: must be positive");
            }
            if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
                problems.add("device.timeout: must be positive");
            }
            int retries = d.retries() == null ? 1 : d.retries();
            if (retries < 0 || retries > 10) {
                problems.add("device.retries: " + retries + " outside 0..10");
            }
            int max = d.maxRegistersPerRequest() == null ? 125 : d.maxRegistersPerRequest();
            if (max < 4 || max > 125) {
                problems.add("device.maxRegistersPerRequest: " + max + " outside 4..125 (Modbus allows at most 125 registers per read)");
            }
            int gap = d.maxGap() == null ? 0 : d.maxGap();
            if (gap < 0 || gap > 120) {
                problems.add("device.maxGap: " + gap + " outside 0..120");
            }
            return new Device(d.id(), d.host(), port, unit, framing, poll, timeout, retries, max, gap);
        }

        private RegisterPoint point(String at, PointFile p, FunctionCode defFunction, WordOrder defOrder, boolean defSwap,
                                    String defOrigin, int base, String metricPrefix) {
            int before = problems.size();
            if (p.name() == null || !NAME.matcher(p.name()).matches()) {
                problems.add(at + ": name " + (p.name() == null ? "missing" : "may only contain letters, digits, _ . -"));
            }
            FunctionCode fc = p.function() != null ? parse(at + ".function", () -> FunctionCode.parse(p.function())) : defFunction;
            if (fc == null && p.function() == null) {
                problems.add(at + ": function missing (set it on the point or in defaults.function)");
            }
            DataType type = p.type() == null ? null : parse(at + ".type", () -> DataType.parse(p.type()));
            if (p.type() == null) {
                problems.add(at + ": type missing");
            }
            if (fc != null && type != null) {
                if (fc.bits() && type != DataType.BOOL) {
                    problems.add(at + ": function " + fc.yamlName() + " returns bits, so type must be bool (is " + type.yamlName() + ")");
                } else if (!fc.bits() && type == DataType.BOOL) {
                    problems.add(at + ": type bool needs function coil or discrete (is " + fc.yamlName() + ")");
                }
            }
            WordOrder order = p.wordOrder() != null ? parse(at + ".wordOrder", () -> WordOrder.parse(p.wordOrder())) : defOrder;
            boolean swap = p.byteSwap() != null ? p.byteSwap() : defSwap;
            int address = -1;
            if (p.address() == null) {
                problems.add(at + ": address missing");
            } else {
                address = p.address() - base;
                if (address < 0 || address > 0xFFFF) {
                    problems.add(at + ": address " + p.address() + " outside " + base + ".." + (0xFFFF + base)
                            + (p.address() >= 30001 ? " (a 3xxxx/4xxxx register number? use the offset, e.g. 40001 -> 0 with addressBase 0)" : ""));
                } else if (type != null && address + type.registers() > 0x10000) {
                    problems.add(at + ": " + type.yamlName() + " at " + p.address() + " runs past address 65535");
                }
            }
            double scale = p.scale() == null ? 1.0 : p.scale();
            if (scale == 0 || Double.isNaN(scale) || Double.isInfinite(scale)) {
                problems.add(at + ": scale must be a finite non-zero number");
            }
            double offset = p.offset() == null ? 0.0 : p.offset();
            if (p.obis() != null && !OBIS.matcher(p.obis()).matches()) {
                problems.add(at + ": obis '" + p.obis() + "' is not an OBIS code like 1-0:1.8.0 or 1-0:1.8.0*255");
            }
            String origin = origin(at + ".origin", p.origin(), defOrigin);
            RegisterPoint.Kind kind = RegisterPoint.Kind.GAUGE;
            if (p.kind() != null) {
                String k = p.kind().trim().toUpperCase(Locale.ROOT);
                if (k.equals("GAUGE") || k.equals("COUNTER")) {
                    kind = RegisterPoint.Kind.valueOf(k);
                } else {
                    problems.add(at + ".kind: '" + p.kind() + "' (expected gauge or counter)");
                }
            }
            if (kind == RegisterPoint.Kind.COUNTER && type == DataType.BOOL) {
                problems.add(at + ": a counter cannot be bool");
            }
            if (p.min() != null && p.max() != null && p.min() > p.max()) {
                problems.add(at + ": min " + p.min() + " > max " + p.max());
            }
            if (p.sim() != null && p.sim().profile() != null && !PROFILES.contains(p.sim().profile())) {
                problems.add(at + ".sim.profile: '" + p.sim().profile() + "' (expected " + String.join(", ", new java.util.TreeSet<>(PROFILES)) + ")");
            }
            if (problems.size() > before) {
                return null;
            }
            String metric = p.metric() != null ? p.metric()
                    : (metricPrefix != null ? metricPrefix + "." + p.name() : p.name());
            return new RegisterPoint(p.name(), metric, p.description(), address, fc, type, order, swap, scale, offset,
                    p.unit(), p.obis(), origin, kind, p.min(), p.max(), p.tags() == null ? Map.of() : Map.copyOf(p.tags()), p.sim());
        }

        private void overlaps(List<RegisterPoint> points) {
            Map<FunctionCode, List<RegisterPoint>> byFunction = points.stream()
                    .collect(Collectors.groupingBy(RegisterPoint::function, LinkedHashMap::new, Collectors.toList()));
            byFunction.forEach((fc, list) -> {
                List<RegisterPoint> sorted = new ArrayList<>(list);
                sorted.sort(Comparator.comparingInt(RegisterPoint::address));
                RegisterPoint prev = null;
                for (RegisterPoint p : sorted) {
                    if (prev != null && p.address() < prev.end()) {
                        problems.add(String.format("points '%s' (%s %d..%d, %s) and '%s' (%s %d..%d, %s) overlap",
                                prev.name(), fc.yamlName(), prev.address(), prev.end() - 1, prev.type().yamlName(),
                                p.name(), fc.yamlName(), p.address(), p.end() - 1, p.type().yamlName()));
                    }
                    if (prev == null || p.end() > prev.end()) {
                        prev = p;
                    }
                }
            });
        }

        private void sims(List<RegisterPoint> points) {
            Set<String> names = points.stream().map(RegisterPoint::name).collect(Collectors.toSet());
            for (RegisterPoint p : points) {
                SimSpec s = p.sim();
                if (s == null) {
                    continue;
                }
                String at = "points '" + p.name() + "'.sim";
                String profile = s.profile() == null ? "constant" : s.profile();
                if ((profile.equals("clamp") || profile.equals("integrate"))) {
                    if (s.source() == null || !names.contains(s.source())) {
                        problems.add(at + ": profile " + profile + " needs source = an existing point (is " + s.source() + ")");
                    } else if (s.source().equals(p.name())) {
                        problems.add(at + ": source must be another point");
                    }
                }
                if (profile.equals("clamp") && s.sign() != null && !s.sign().equals("positive") && !s.sign().equals("negative")) {
                    problems.add(at + ".sign: '" + s.sign() + "' (expected positive or negative)");
                }
                if (s.period() != null) {
                    parse(at + ".period", () -> duration(s.period()));
                }
            }
        }

        private String origin(String at, String value, String dflt) {
            if (value == null) {
                return dflt;
            }
            String v = value.trim().toUpperCase(Locale.ROOT);
            if (!ORIGINS.contains(v)) {
                problems.add(at + ": '" + value + "' (expected one of MEASURED, REPORTED, FORECAST, ESTIMATED, SYNTHETIC)");
                return dflt;
            }
            return v;
        }

        private <T> T parse(String at, java.util.function.Supplier<T> s) {
            try {
                return s.get();
            } catch (IllegalArgumentException e) {
                problems.add(at + ": " + e.getMessage());
                return null;
            }
        }
    }
}
