package org.opentsx.store.iceberg;

import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.types.Types;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;

/**
 * Iceberg table layouts. Column names are snake_case so Spark SQL, Trino and PyIceberg users can query them
 * directly; a v2 Episode maps to one row without loss.
 */
final class Tables {

    static final String EPISODES = "episodes";
    static final String SERIES = "series";
    static final String EPISODE_INDEX = "episode_index";

    private static final Types.MapType STRING_MAP_1 = Types.MapType.ofRequired(101, 102, Types.StringType.get(), Types.StringType.get());
    private static final Types.MapType STRING_MAP_2 = Types.MapType.ofRequired(103, 104, Types.StringType.get(), Types.StringType.get());
    private static final Types.MapType STRING_MAP_3 = Types.MapType.ofRequired(105, 106, Types.StringType.get(), Types.StringType.get());

    static final Schema EPISODE_SCHEMA = new Schema(
            required(1, "episode_id", Types.StringType.get()),
            required(2, "series_id", Types.StringType.get()),
            required(3, "metric", Types.StringType.get()),
            required(4, "tags", STRING_MAP_1),
            optional(5, "unit", Types.StringType.get()),
            required(6, "t_start", Types.TimestampType.withZone()),
            required(7, "t_end", Types.TimestampType.withZone()),
            required(8, "count", Types.IntegerType.get()),
            required(9, "time_encoding", Types.StringType.get()),
            optional(10, "interval_us", Types.LongType.get()),
            optional(11, "time_deltas", Types.BinaryType.get()),
            required(12, "values", Types.ListType.ofRequired(107, Types.DoubleType.get())),
            optional(13, "quality", Types.BinaryType.get()),
            required(14, "s_valid_count", Types.LongType.get()),
            required(15, "s_nan_count", Types.LongType.get()),
            required(16, "s_min", Types.DoubleType.get()),
            required(17, "s_max", Types.DoubleType.get()),
            required(18, "s_mean", Types.DoubleType.get()),
            required(19, "s_m2", Types.DoubleType.get()),
            optional(20, "s_sax", Types.StringType.get()),
            optional(21, "seg_strategy", Types.StringType.get()),
            optional(22, "seg_params", STRING_MAP_2),
            optional(23, "seg_anchor_ts", Types.TimestampType.withZone()),
            optional(24, "seg_source_episode_id", Types.StringType.get()),
            required(25, "labels", STRING_MAP_3),
            optional(26, "bucket_id", Types.StringType.get()),
            required(27, "revision", Types.IntegerType.get()),
            required(28, "source", Types.StringType.get()),
            required(29, "producer", Types.StringType.get()),
            required(30, "created_at", Types.TimestampType.withZone()),
            optional(31, "pipeline", Types.StringType.get()),
            optional(32, "parent_episode_id", Types.StringType.get()));

    static final PartitionSpec EPISODE_SPEC = PartitionSpec.builderFor(EPISODE_SCHEMA)
            .day("t_start").bucket("series_id", 32).build();

    static final SortOrder EPISODE_SORT = SortOrder.builderFor(EPISODE_SCHEMA).asc("series_id").asc("t_start").build();

    private static final Types.MapType SERIES_TAGS = Types.MapType.ofRequired(201, 202, Types.StringType.get(), Types.StringType.get());

    static final Schema SERIES_SCHEMA = new Schema(
            required(1, "series_id", Types.StringType.get()),
            required(2, "metric", Types.StringType.get()),
            required(3, "tags", SERIES_TAGS),
            optional(4, "unit", Types.StringType.get()));

    static final Schema INDEX_SCHEMA = new Schema(
            required(1, "episode_id", Types.StringType.get()),
            required(2, "series_id", Types.StringType.get()),
            required(3, "t_start", Types.TimestampType.withZone()),
            required(4, "t_end", Types.TimestampType.withZone()),
            optional(5, "bucket_id", Types.StringType.get()),
            required(6, "topic", Types.StringType.get()),
            required(7, "kafka_partition", Types.IntegerType.get()),
            required(8, "kafka_offset", Types.LongType.get()));

    static final PartitionSpec INDEX_SPEC = PartitionSpec.builderFor(INDEX_SCHEMA).day("t_start").build();

    private Tables() {
    }
}
