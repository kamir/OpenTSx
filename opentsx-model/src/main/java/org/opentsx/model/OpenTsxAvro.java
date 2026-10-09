package org.opentsx.model;

import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.Observation;
import org.opentsx.model.v2.PatternMatch;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.SeriesStats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Wire format of the v2 model.
 *
 * <p>Writes Avro single-object encoding (self-describing, no schema registry needed - KafScale has none).
 * Reads single-object payloads by fingerprint, and Confluent-framed payloads when a schema resolver is given.
 * Writer schemas of older versions can be registered for schema evolution.
 */
public final class OpenTsxAvro {

    /** Top-level record types that travel on topics or in files. */
    public static final List<Class<? extends SpecificRecord>> TOP_LEVEL_TYPES = List.of(
            Episode.class, Observation.class, SeriesKey.class, BucketManifest.class, SeriesStats.class, PatternMatch.class);

    private final Map<Long, Class<? extends SpecificRecord>> readerByFingerprint = new ConcurrentHashMap<>();
    private final Map<Long, Schema> writerByFingerprint = new ConcurrentHashMap<>();
    private final Map<String, Class<? extends SpecificRecord>> readerByName = new ConcurrentHashMap<>();
    private final Map<Class<?>, BinaryMessageEncoder<SpecificRecord>> encoders = new ConcurrentHashMap<>();
    private final Map<Class<?>, BinaryMessageDecoder<SpecificRecord>> decoders = new ConcurrentHashMap<>();
    private final IntFunction<Schema> confluentResolver;

    public OpenTsxAvro() {
        this(null);
    }

    /** @param confluentResolver schema id to writer schema, e.g. backed by a schema registry client; may be null */
    public OpenTsxAvro(IntFunction<Schema> confluentResolver) {
        this.confluentResolver = confluentResolver;
        for (Class<? extends SpecificRecord> type : TOP_LEVEL_TYPES) {
            Schema schema = SpecificData.get().getSchema(type);
            readerByName.put(schema.getFullName(), type);
            registerWriterSchema(schema);
        }
    }

    public static long fingerprint(Schema schema) {
        return SchemaNormalization.parsingFingerprint64(schema);
    }

    /** Makes payloads written with an older version of a known record readable (schema evolution). */
    public void registerWriterSchema(Schema writer) {
        Class<? extends SpecificRecord> reader = readerByName.get(writer.getFullName());
        if (reader == null) {
            throw new IllegalArgumentException("no reader type for " + writer.getFullName());
        }
        long fp = fingerprint(writer);
        writerByFingerprint.put(fp, writer);
        readerByFingerprint.put(fp, reader);
        decoders.computeIfPresent(reader, (k, d) -> {
            d.addSchema(writer);
            return d;
        });
    }

    public byte[] encode(SpecificRecord record) {
        BinaryMessageEncoder<SpecificRecord> encoder = encoders.computeIfAbsent(record.getClass(),
                c -> new BinaryMessageEncoder<>(SpecificData.getForClass(c), record.getSchema()));
        try {
            ByteBuffer buf = encoder.encode(record);
            byte[] out = new byte[buf.remaining()];
            buf.get(out);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public SpecificRecord decode(byte[] payload) {
        switch (WireFormat.detect(payload)) {
            case SINGLE_OBJECT:
                long fp = ByteBuffer.wrap(payload, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                Class<? extends SpecificRecord> type = readerByFingerprint.get(fp);
                if (type == null) {
                    throw new IllegalArgumentException("unknown schema fingerprint " + Long.toHexString(fp));
                }
                try {
                    return decoderFor(type).decode(payload);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            case CONFLUENT:
                return decodeConfluent(payload);
            default:
                throw new IllegalArgumentException("unrecognised payload framing");
        }
    }

    public <T extends SpecificRecord> T decode(byte[] payload, Class<T> type) {
        SpecificRecord r = decode(payload);
        if (!type.isInstance(r)) {
            throw new IllegalArgumentException("payload holds " + r.getSchema().getFullName() + ", not " + type.getName());
        }
        return type.cast(r);
    }

    private BinaryMessageDecoder<SpecificRecord> decoderFor(Class<? extends SpecificRecord> type) {
        return decoders.computeIfAbsent(type, t -> {
            Schema reader = SpecificData.get().getSchema(t);
            BinaryMessageDecoder<SpecificRecord> d = new BinaryMessageDecoder<>(SpecificData.getForClass(t), reader);
            writerByFingerprint.forEach((fp, writer) -> {
                if (writer.getFullName().equals(reader.getFullName())) {
                    d.addSchema(writer);
                }
            });
            return d;
        });
    }

    private SpecificRecord decodeConfluent(byte[] payload) {
        Objects.requireNonNull(confluentResolver, "Confluent-framed payload but no schema resolver configured");
        int id = ByteBuffer.wrap(payload, 1, 4).getInt();
        Schema writer = confluentResolver.apply(id);
        Class<? extends SpecificRecord> type = readerByName.get(writer.getFullName());
        if (type == null) {
            throw new IllegalArgumentException("no reader type for " + writer.getFullName());
        }
        Schema reader = SpecificData.get().getSchema(type);
        SpecificDatumReader<SpecificRecord> datumReader = new SpecificDatumReader<>(writer, reader, SpecificData.getForClass(type));
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(payload, 5, payload.length - 5, null);
        try {
            return datumReader.read(null, decoder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
