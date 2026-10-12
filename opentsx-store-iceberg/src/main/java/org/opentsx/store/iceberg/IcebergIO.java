package org.opentsx.store.iceberg;

import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.PartitionedFanoutWriter;
import org.apache.iceberg.io.UnpartitionedWriter;
import org.apache.iceberg.io.TaskWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/** Writes generic records as Parquet data files and commits them in one append. */
final class IcebergIO {

    private static final long TARGET_FILE_BYTES = 128L * 1024 * 1024;
    private static final AtomicInteger TASK = new AtomicInteger();

    private IcebergIO() {
    }

    static void append(Table table, Collection<Record> records) {
        if (records.isEmpty()) {
            return;
        }
        GenericAppenderFactory appenders = new GenericAppenderFactory(table.schema(), table.spec());
        OutputFileFactory files = OutputFileFactory.builderFor(table, 1, TASK.incrementAndGet())
                .format(FileFormat.PARQUET).build();
        TaskWriter<Record> writer;
        if (table.spec().isUnpartitioned()) {
            writer = new UnpartitionedWriter<>(table.spec(), FileFormat.PARQUET, appenders, files, table.io(), TARGET_FILE_BYTES);
        } else {
            PartitionKey key = new PartitionKey(table.spec(), table.schema());
            InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
            writer = new PartitionedFanoutWriter<>(table.spec(), FileFormat.PARQUET, appenders, files, table.io(), TARGET_FILE_BYTES) {
                @Override
                protected PartitionKey partition(Record row) {
                    key.partition(wrapper.wrap(row));
                    return key;
                }
            };
        }
        try {
            for (Record r : records) {
                writer.write(r);
            }
            AppendFiles append = table.newAppend();
            for (DataFile f : writer.complete().dataFiles()) {
                append.appendFile(f);
            }
            append.commit();
        } catch (IOException e) {
            try {
                writer.abort();
            } catch (IOException ignored) {
                // best effort cleanup of orphan files
            }
            throw new UncheckedIOException(e);
        }
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(Object o) {
        return o == null ? null : ((OffsetDateTime) o).toInstant();
    }
}
