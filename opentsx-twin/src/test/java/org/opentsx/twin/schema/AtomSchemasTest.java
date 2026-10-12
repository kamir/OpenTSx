package org.opentsx.twin.schema;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;
import org.opentsx.twin.Atom;
import org.opentsx.twin.AtomCatalog;
import org.opentsx.twin.Mode;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.RunResult;
import org.opentsx.twin.Snapshot;
import org.opentsx.twin.TwinRunner;
import org.opentsx.twin.studio.Scenarios;
import org.opentsx.twin.studio.StudioScenario;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomSchemasTest {

    /** The checked-in schemas are exactly what the catalog generates (regenerate with bin/opentsx twin-schemas). */
    @Test
    void checkedInSchemasMatchTheCatalog() throws IOException {
        for (Map.Entry<String, String> e : AtomSchemas.files(AtomCatalog.standard()).entrySet()) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(AtomSchemas.RESOURCE_DIR + "/" + e.getKey())) {
                assertNotNull(in, "missing " + e.getKey() + " – run bin/opentsx twin-schemas");
                assertEquals(e.getValue(), new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        e.getKey() + " is stale – run bin/opentsx twin-schemas");
            }
            new Schema.Parser().parse(e.getValue());
        }
    }

    /** Every atom instance of every scenario fits its type's schema, and its snapshots round-trip through Avro. */
    @Test
    void scenarioSnapshotsRoundTrip() throws IOException {
        AtomCatalog catalog = AtomCatalog.standard();
        Map<String, Schema> schemas = AtomSchemas.snapshots(catalog);
        for (StudioScenario s : Scenarios.all()) {
            RunResult r = new TwinRunner(s.plan()).run(new TwinRunner.RunSpec(Mode.SHADOW, s.observed(), s.reference(),
                    null, 0, null, null));
            List<Atom> atoms = s.plan().instantiate(catalog);
            for (int k : new int[]{r.firstK(), r.firstK() + r.size() / 2, r.firstK() + r.size() - 1}) {
                Snapshot snap = r.snapshotAfter(k);
                for (Atom a : atoms) {
                    Schema schema = schemas.get(a.type());
                    Set<String> fields = schema.getField("state").schema().getFields().stream()
                            .map(Schema.Field::name).collect(Collectors.toSet());
                    assertTrue(snap.states().get(a.id()).keySet().containsAll(fields), s.id() + "/" + a.id());
                    assertEquals(schema.getField("outputs").schema().getFields().stream().map(Schema.Field::name)
                            .collect(Collectors.toList()), a.outputs().stream().map(PortSpec::name)
                            .collect(Collectors.toList()), a.id());
                    GenericRecord rec = AtomSchemas.encode(schema, r.runId(), a.id(), k, snap.tMicros() - r.stepMicros(),
                            snap.states().get(a.id()), snap.outputs().get(a.id()), snap.overrides().get(a.id()));
                    GenericRecord back = roundTrip(schema, rec);
                    assertEquals(rec, back, s.id() + "/" + a.id());
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> extra = (Map<Object, Object>) back.get("state_extra");
                    for (Map.Entry<String, Double> v : snap.states().get(a.id()).entrySet()) {
                        Object got = fields.contains(v.getKey())
                                ? ((GenericRecord) back.get("state")).get(v.getKey())
                                : extra.get(new org.apache.avro.util.Utf8(v.getKey()));
                        assertEquals(v.getValue(), (Double) got, a.id() + "." + v.getKey());
                    }
                }
            }
        }
    }

    @Test
    void envelopesParse() {
        assertTrue(AtomSchemas.portMessage().getField("origin").schema().getEnumSymbols().contains("MEASURED"));
        assertTrue(AtomSchemas.barrier().getField("phase").schema().isNullable());
        assertTrue(AtomSchemas.intervention().getField("value").schema().isNullable());
        assertEquals("H2OfftakeSnapshot", AtomSchemas.recordName("h2-offtake"));
    }

    private static GenericRecord roundTrip(Schema schema, GenericRecord rec) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryEncoder enc = EncoderFactory.get().binaryEncoder(bytes, null);
        new GenericDatumWriter<GenericRecord>(schema).write(rec, enc);
        enc.flush();
        return new GenericDatumReader<GenericRecord>(schema)
                .read(null, DecoderFactory.get().binaryDecoder(bytes.toByteArray(), null));
    }
}
