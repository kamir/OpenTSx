package org.opentsx.twin.schema;

import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.opentsx.twin.Atom;
import org.opentsx.twin.AtomCatalog;
import org.opentsx.twin.Origin;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Avro schemas of the twin's topic contract: the envelopes shared by all atoms ({@code PortMessage}, {@code Barrier},
 * {@code Intervention}) and one snapshot record per atom type with its state variables, outputs (value and origin)
 * and the controls in force. Variant L and non-Java atoms (Python, Go) exchange exactly these records; the
 * checked-in files under {@code twin/avro/} are generated from the atom catalog and kept in sync by a test.
 *
 * <pre>bin/opentsx twin-schemas [--out dir]</pre>
 */
public final class AtomSchemas {

    public static final String NAMESPACE = "org.opentsx.twin.v1";
    public static final String ATOM_NAMESPACE = NAMESPACE + ".atoms";
    /** Where the generated schemas are checked in (classpath and source tree). */
    public static final String RESOURCE_DIR = "twin/avro";

    private AtomSchemas() {
    }

    public static Schema origin() {
        return SchemaBuilder.enumeration("Origin").namespace(NAMESPACE)
                .doc("Origin ladder, best first; a value is as good as its worst input.")
                .symbols(names(Origin.values()));
    }

    public static Schema phase() {
        return SchemaBuilder.enumeration("Phase").namespace(NAMESPACE)
                .doc("Layer of a superstep in which the atom runs.")
                .symbols(names(Phase.values()));
    }

    private static Schema timestampMicros() {
        return LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG));
    }

    /** One output value between atoms; id {@code run/k/phase/atom/port} is deterministic. */
    public static Schema portMessage() {
        return SchemaBuilder.record("PortMessage").namespace(NAMESPACE)
                .doc("One output value as it travels between atoms (topic twin.<id>.<run>.msgs).")
                .fields()
                .requiredString("run_id")
                .requiredInt("k")
                .name("phase").type(phase()).noDefault()
                .requiredString("atom")
                .requiredString("port")
                .name("t").type(timestampMicros()).noDefault()
                .requiredDouble("value")
                .name("origin").type(origin()).noDefault()
                .endRecord();
    }

    /** Phase end ({@code phase} set) or superstep commit with chained state hash ({@code phase} null). */
    public static Schema barrier() {
        return SchemaBuilder.record("Barrier").namespace(NAMESPACE)
                .doc("Phase end or superstep commit (topic twin.<id>.<run>.barrier).")
                .fields()
                .requiredString("run_id")
                .requiredInt("k")
                .name("phase").type().optional().type(phase())
                .requiredInt("count")
                .optionalString("state_hash")
                .endRecord();
    }

    /** A decision in the intervention log; {@code value} null releases the control. */
    public static Schema intervention() {
        return SchemaBuilder.record("Intervention").namespace(NAMESPACE)
                .doc("Override of an atom control from time t on (topic twin.<id>.<run>.interventions).")
                .fields()
                .name("t").type(timestampMicros()).noDefault()
                .requiredString("atom")
                .requiredString("control")
                .optionalDouble("value")
                .requiredString("author")
                .requiredString("reason")
                .endRecord();
    }

    /** Record name of an atom type: {@code h2-offtake} → {@code H2OfftakeSnapshot}. */
    public static String recordName(String type) {
        StringBuilder sb = new StringBuilder();
        for (String part : type.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb + "Snapshot";
    }

    /**
     * Snapshot of one atom instance after superstep {@code k}: state, outputs and the controls in force. The
     * instance id is the key ({@code atom}), so one topic per atom type holds all its instances.
     */
    public static Schema snapshot(Atom prototype) {
        String base = recordName(prototype.type()).replace("Snapshot", "");
        Schema portValue = SchemaBuilder.record("PortValue").namespace(NAMESPACE)
                .doc("Value with origin.")
                .fields()
                .requiredDouble("value")
                .name("origin").type(origin()).noDefault()
                .endRecord();

        SchemaBuilder.FieldAssembler<Schema> state = SchemaBuilder.record(base + "State").namespace(ATOM_NAMESPACE)
                .doc("State variables of " + prototype.type()).fields();
        for (String v : prototype.state().keySet()) {
            state = state.requiredDouble(check(v));
        }
        SchemaBuilder.FieldAssembler<Schema> outs = SchemaBuilder.record(base + "Outputs").namespace(ATOM_NAMESPACE)
                .doc("Output ports of " + prototype.type()).fields();
        for (PortSpec p : prototype.outputs()) {
            // the named type PortValue is defined at its first use and referenced afterwards
            outs = outs.name(check(p.name())).doc(p.kind() + " [" + p.unit() + "]").type(portValue).noDefault();
        }
        SchemaBuilder.FieldAssembler<Schema> ctl = SchemaBuilder.record(base + "Controls").namespace(ATOM_NAMESPACE)
                .doc("Controls of " + prototype.type() + " overridden by interventions (null: policy decides)").fields();
        for (Map.Entry<String, Double> c : prototype.controlDefaults().entrySet()) {
            ctl = ctl.name(check(c.getKey())).doc("default " + c.getValue()).type().optional().doubleType();
        }

        Schema s = SchemaBuilder.record(recordName(prototype.type())).namespace(ATOM_NAMESPACE)
                .doc("Snapshot of one " + prototype.type() + " atom after superstep k (phase " + prototype.phase() + ")")
                .fields()
                .requiredString("run_id")
                .name("atom").doc("instance id, the record key").type().stringType().noDefault()
                .requiredInt("k")
                .name("t").type(timestampMicros()).noDefault()
                .name("state").type(state.endRecord()).noDefault()
                .name("state_extra").doc("state variables that depend on the instance's parameters, e.g. one "
                        + "counter per configured appliance").type().map().values().doubleType().mapDefault(Map.of())
                .name("outputs").type(outs.endRecord()).noDefault()
                .name("controls").type(ctl.endRecord()).noDefault()
                .endRecord();
        s.addProp("opentsx.atomType", prototype.type());
        s.addProp("opentsx.phase", prototype.phase().name());
        List<Map<String, String>> inputs = new ArrayList<>();
        for (PortSpec p : prototype.inputs()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("kind", p.kind().name());
            m.put("unit", p.unit());
            inputs.add(m);
        }
        s.addProp("opentsx.inputs", inputs);
        return s;
    }

    /** Snapshot schemas of all types in the catalog, instantiated with default parameters. */
    public static Map<String, Schema> snapshots(AtomCatalog catalog) {
        Map<String, Schema> out = new LinkedHashMap<>();
        for (String type : catalog.types()) {
            out.put(type, snapshot(catalog.create(type, "prototype", new Params(Map.of()))));
        }
        return out;
    }

    /** Encodes one atom instance's snapshot. Missing outputs are encoded as NaN with origin SYNTHETIC. */
    public static GenericRecord encode(Schema schema, String runId, String atom, int k, long tMicros,
                                       Map<String, Double> state, Map<String, Value> outputs,
                                       Map<String, Double> controls) {
        GenericRecord r = new GenericData.Record(schema);
        r.put("run_id", runId);
        r.put("atom", atom);
        r.put("k", k);
        r.put("t", tMicros);
        Schema ss = schema.getField("state").schema();
        GenericRecord st = new GenericData.Record(ss);
        for (Schema.Field f : ss.getFields()) {
            Double v = state.get(f.name());
            st.put(f.name(), v == null ? Double.NaN : v);
        }
        r.put("state", st);
        Map<String, Double> extra = new LinkedHashMap<>();
        state.forEach((k2, v) -> {
            if (ss.getField(k2) == null) {
                extra.put(k2, v);
            }
        });
        r.put("state_extra", extra);
        Schema os = schema.getField("outputs").schema();
        GenericRecord out = new GenericData.Record(os);
        for (Schema.Field f : os.getFields()) {
            Value v = outputs.get(f.name());
            GenericRecord pv = new GenericData.Record(f.schema());
            pv.put("value", v == null ? Double.NaN : v.v());
            pv.put("origin", new GenericData.EnumSymbol(f.schema().getField("origin").schema(),
                    (v == null ? Origin.SYNTHETIC : v.origin()).name()));
            out.put(f.name(), pv);
        }
        r.put("outputs", out);
        Schema cs = schema.getField("controls").schema();
        GenericRecord ctl = new GenericData.Record(cs);
        for (Schema.Field f : cs.getFields()) {
            ctl.put(f.name(), controls == null ? null : controls.get(f.name()));
        }
        r.put("controls", ctl);
        return r;
    }

    /** All schemas as file name → pretty JSON, in a stable order. */
    public static Map<String, String> files(AtomCatalog catalog) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("PortMessage.avsc", portMessage().toString(true) + "\n");
        out.put("Barrier.avsc", barrier().toString(true) + "\n");
        out.put("Intervention.avsc", intervention().toString(true) + "\n");
        snapshots(catalog).forEach((type, s) -> out.put("atoms/" + type + ".avsc", s.toString(true) + "\n"));
        return out;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("opentsx-twin/src/main/resources/" + RESOURCE_DIR);
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out")) {
                dir = Path.of(args[++i]);
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        for (Map.Entry<String, String> e : files(AtomCatalog.standard()).entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        System.out.println("wrote " + files(AtomCatalog.standard()).size() + " schemas to " + dir);
    }

    private static String check(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("not a valid Avro field name: " + name);
        }
        return name;
    }

    private static String[] names(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].name();
        }
        return out;
    }
}
