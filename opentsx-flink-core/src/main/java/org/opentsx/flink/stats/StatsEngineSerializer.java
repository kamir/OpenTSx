package org.opentsx.flink.stats;

import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSchemaCompatibility;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.opentsx.stats.StatsEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Flink state serializer for a {@link StatsEngine}: a length-prefixed {@link StatsEngine#writeState} image.
 *
 * <p>With the heap state backend the engine stays an object between records and is written at checkpoints only.
 * With RocksDB it would be written on every access, so the job documents the heap backend.
 */
public final class StatsEngineSerializer extends TypeSerializer<StatsEngine> {

    private static final long serialVersionUID = 1L;

    private final EngineSpec spec;

    public StatsEngineSerializer(EngineSpec spec) {
        this.spec = spec;
    }

    @Override
    public boolean isImmutableType() {
        return false;
    }

    @Override
    public TypeSerializer<StatsEngine> duplicate() {
        return this;    // stateless: the spec is immutable
    }

    @Override
    public StatsEngine createInstance() {
        return spec.newEngine();
    }

    @Override
    public StatsEngine copy(StatsEngine from) {
        return read(bytes(from));
    }

    @Override
    public StatsEngine copy(StatsEngine from, StatsEngine reuse) {
        return copy(from);
    }

    @Override
    public int getLength() {
        return -1;
    }

    @Override
    public void serialize(StatsEngine engine, DataOutputView target) throws IOException {
        byte[] image = bytes(engine);
        target.writeInt(image.length);
        target.write(image);
    }

    @Override
    public StatsEngine deserialize(DataInputView source) throws IOException {
        byte[] image = new byte[source.readInt()];
        source.readFully(image);
        return read(image);
    }

    @Override
    public StatsEngine deserialize(StatsEngine reuse, DataInputView source) throws IOException {
        return deserialize(source);
    }

    @Override
    public void copy(DataInputView source, DataOutputView target) throws IOException {
        // through an array: DataOutputSerializer.write(DataInputView, int) does not grow its buffer
        byte[] image = new byte[source.readInt()];
        source.readFully(image);
        target.writeInt(image.length);
        target.write(image);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StatsEngineSerializer other && other.spec.equals(spec);
    }

    @Override
    public int hashCode() {
        return spec.hashCode();
    }

    @Override
    public TypeSerializerSnapshot<StatsEngine> snapshotConfiguration() {
        return new Snapshot(spec);
    }

    private static byte[] bytes(StatsEngine engine) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            engine.writeState(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    private StatsEngine read(byte[] image) {
        StatsEngine engine = spec.newEngine();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(image))) {
            engine.readState(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return engine;
    }

    /**
     * Writes the engine settings into the checkpoint. A restore with other windows is incompatible, because
     * {@link StatsEngine#readState} cannot map open windows onto other window sizes.
     */
    public static final class Snapshot implements TypeSerializerSnapshot<StatsEngine> {

        private static final int VERSION = 1;

        private EngineSpec spec;

        /** For Flink, which creates the snapshot by reflection before {@link #readSnapshot}. */
        public Snapshot() {
        }

        Snapshot(EngineSpec spec) {
            this.spec = spec;
        }

        @Override
        public int getCurrentVersion() {
            return VERSION;
        }

        @Override
        public void writeSnapshot(DataOutputView out) throws IOException {
            out.writeInt(spec.windows().size());
            for (Duration w : spec.windows()) {
                out.writeUTF(w.toString());
            }
            out.writeUTF(spec.lateness().toString());
            out.writeDouble(spec.alpha());
        }

        @Override
        public void readSnapshot(int readVersion, DataInputView in, ClassLoader userCodeClassLoader) throws IOException {
            if (readVersion != VERSION) {
                throw new IOException("unsupported statistics state snapshot version " + readVersion);
            }
            int n = in.readInt();
            List<Duration> windows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                windows.add(Duration.parse(in.readUTF()));
            }
            Duration lateness = Duration.parse(in.readUTF());
            spec = new EngineSpec(windows, lateness, in.readDouble());
        }

        @Override
        public TypeSerializer<StatsEngine> restoreSerializer() {
            return new StatsEngineSerializer(spec);
        }

        @Override
        public TypeSerializerSchemaCompatibility<StatsEngine> resolveSchemaCompatibility(
                TypeSerializerSnapshot<StatsEngine> oldSerializerSnapshot) {
            if (oldSerializerSnapshot instanceof Snapshot old && old.spec.windows().equals(spec.windows())) {
                return TypeSerializerSchemaCompatibility.compatibleAsIs();
            }
            return TypeSerializerSchemaCompatibility.incompatible();
        }
    }
}
