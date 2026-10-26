package org.opentsx.flink.stats;

import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.core.memory.DataInputDeserializer;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.junit.jupiter.api.Test;
import org.opentsx.model.v2.Episode;
import org.opentsx.stats.StatsEngine;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsEngineSerializerTest {

    private static final EngineSpec SPEC = EngineSpec.defaults();

    private static byte[] image(StatsEngine engine) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        engine.writeState(new DataOutputStream(buffer));
        return buffer.toByteArray();
    }

    /** An engine with open windows, GLOBAL and recent episode ids. */
    private static StatsEngine busy() {
        StatsEngine engine = SPEC.newEngine();
        List<Episode> episodes = StatsData.episodes("serializer", Duration.ofMinutes(25), 1);
        episodes.forEach(engine::onEpisode);
        return engine;
    }

    @Test
    void roundTripKeepsTheCompleteState() throws IOException {
        StatsEngine engine = busy();
        StatsEngineSerializer serializer = new StatsEngineSerializer(SPEC);
        DataOutputSerializer out = new DataOutputSerializer(1024);
        serializer.serialize(engine, out);
        StatsEngine back = serializer.deserialize(new DataInputDeserializer(out.getCopyOfBuffer()));
        assertArrayEquals(image(engine), image(back));
        assertEquals(engine.flush(), back.flush(), "the restored engine closes the same windows");
    }

    @Test
    void copyIsIndependentOfTheOriginal() throws IOException {
        StatsEngine engine = busy();
        byte[] before = image(engine);
        StatsEngine copy = new StatsEngineSerializer(SPEC).copy(engine);
        copy.flush();
        assertArrayEquals(before, image(engine), "changing the copy leaves the original as it was");
    }

    @Test
    void binaryCopyEqualsSerializedForm() throws IOException {
        StatsEngineSerializer serializer = new StatsEngineSerializer(SPEC);
        DataOutputSerializer first = new DataOutputSerializer(1024);
        serializer.serialize(busy(), first);
        DataOutputSerializer second = new DataOutputSerializer(1024);
        serializer.copy(new DataInputDeserializer(first.getCopyOfBuffer()), second);
        assertArrayEquals(first.getCopyOfBuffer(), second.getCopyOfBuffer());
    }

    @Test
    void snapshotRestoresTheSameSerializer() throws IOException {
        StatsEngineSerializer serializer = new StatsEngineSerializer(SPEC);
        TypeSerializerSnapshot<StatsEngine> restored = writeAndRead(serializer.snapshotConfiguration());
        TypeSerializer<StatsEngine> again = restored.restoreSerializer();
        assertEquals(serializer, again);
        assertTrue(serializer.snapshotConfiguration().resolveSchemaCompatibility(restored).isCompatibleAsIs());
    }

    @Test
    void otherWindowsAreIncompatible() throws IOException {
        TypeSerializerSnapshot<StatsEngine> old = writeAndRead(new StatsEngineSerializer(SPEC).snapshotConfiguration());
        EngineSpec hourly = new EngineSpec(List.of(Duration.ofHours(1)), Duration.ZERO, SPEC.alpha());
        assertTrue(new StatsEngineSerializer(hourly).snapshotConfiguration().resolveSchemaCompatibility(old).isIncompatible());
        EngineSpec later = new EngineSpec(SPEC.windows(), Duration.ofMinutes(1), SPEC.alpha());
        assertFalse(new StatsEngineSerializer(later).snapshotConfiguration().resolveSchemaCompatibility(old).isIncompatible(),
                "only the windows decide; lateness applies from the restore on");
    }

    private static TypeSerializerSnapshot<StatsEngine> writeAndRead(TypeSerializerSnapshot<StatsEngine> snapshot)
            throws IOException {
        DataOutputSerializer out = new DataOutputSerializer(256);
        TypeSerializerSnapshot.writeVersionedSnapshot(out, snapshot);
        return TypeSerializerSnapshot.readVersionedSnapshot(new DataInputDeserializer(out.getCopyOfBuffer()),
                StatsEngineSerializerTest.class.getClassLoader());
    }
}
