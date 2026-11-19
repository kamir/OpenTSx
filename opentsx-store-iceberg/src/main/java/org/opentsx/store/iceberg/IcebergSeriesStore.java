package org.opentsx.store.iceberg;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.store.Capability;
import org.opentsx.store.EpisodeOps;
import org.opentsx.store.EpisodeQuery;
import org.opentsx.store.SeriesSelector;
import org.opentsx.store.SeriesStore;
import org.opentsx.store.StoreCapabilities;
import org.opentsx.store.WriteResult;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SeriesStore} on Apache Iceberg: table {@code <namespace>.episodes} (one row per episode, partitioned by
 * day(t_start) and bucket(32, series_id)) and {@code <namespace>.series}.
 *
 * <p>Writes are idempotent: episodes whose id is already in the table are skipped, so at-least-once delivery from
 * KafScale can be written directly. Any Iceberg catalog works ({@code CatalogUtil} properties): REST in production,
 * JDBC/SQLite or Hadoop for local work.
 */
public final class IcebergSeriesStore implements SeriesStore {

    public static final String DEFAULT_NAMESPACE = "tsx";

    private final Catalog catalog;
    private final Table episodes;
    private final Table series;
    private final boolean readOnly;
    private final boolean ownsCatalog;

    /** Borrows the catalog: closing the store leaves it open, and the caller closes it. */
    public IcebergSeriesStore(Catalog catalog, Namespace namespace) {
        this(catalog, namespace, false, false);
    }

    private IcebergSeriesStore(Catalog catalog, Namespace namespace, boolean readOnly, boolean ownsCatalog) {
        this.catalog = catalog;
        this.readOnly = readOnly;
        this.ownsCatalog = ownsCatalog;
        if (readOnly) {
            this.episodes = catalog.loadTable(TableIdentifier.of(namespace, Tables.EPISODES));
            this.series = catalog.loadTable(TableIdentifier.of(namespace, Tables.SERIES));
            return;
        }
        createNamespace(catalog, namespace);
        this.episodes = loadOrCreate(catalog, TableIdentifier.of(namespace, Tables.EPISODES), () -> catalog
                .buildTable(TableIdentifier.of(namespace, Tables.EPISODES), Tables.EPISODE_SCHEMA)
                .withPartitionSpec(Tables.EPISODE_SPEC)
                .withSortOrder(Tables.EPISODE_SORT)
                .withProperty("format-version", "2")
                .withProperty("write.parquet.compression-codec", "zstd")
                .create());
        this.series = loadOrCreate(catalog, TableIdentifier.of(namespace, Tables.SERIES), () -> catalog
                .buildTable(TableIdentifier.of(namespace, Tables.SERIES), Tables.SERIES_SCHEMA)
                .withProperty("format-version", "2")
                .create());
    }

    /**
     * Opens a catalog from Iceberg catalog properties, e.g. {@code type=rest, uri=..., warehouse=...}. The store owns
     * that catalog: closing the store closes it, and so does a failure to open the store.
     */
    public static IcebergSeriesStore open(Map<String, String> catalogProperties, String namespace) {
        return owning(catalog(catalogProperties), Namespace.of(namespace), false);
    }

    /** Borrows the catalog: closing the store leaves it open, and the caller closes it. */
    public static IcebergSeriesStore openExisting(Catalog catalog, Namespace namespace) {
        return new IcebergSeriesStore(catalog, namespace, true, false);
    }

    /** Owns the catalog it opens from the properties, like {@link #open(Map, String)}. */
    public static IcebergSeriesStore openExisting(Map<String, String> catalogProperties, String namespace) {
        return owning(catalog(catalogProperties), Namespace.of(namespace), true);
    }

    private static IcebergSeriesStore owning(Catalog catalog, Namespace namespace, boolean readOnly) {
        try {
            return new IcebergSeriesStore(catalog, namespace, readOnly, true);
        } catch (RuntimeException | Error failure) {
            try {
                closeCatalog(catalog);
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure instanceof UncheckedIOException
                        ? closeFailure.getCause() : closeFailure);
            }
            throw failure;
        }
    }

    static Catalog catalog(Map<String, String> props) {
        Configuration conf = new Configuration();
        return CatalogUtil.buildIcebergCatalog(props.getOrDefault("name", "opentsx"), props, conf);
    }

    @Override
    public StoreCapabilities capabilities() {
        return StoreCapabilities.of(Capability.EPISODES, Capability.MICROSECOND_PRECISION, Capability.NAN_VALUES,
                Capability.SQL, Capability.TIME_TRAVEL);
    }

    @Override
    public synchronized WriteResult write(Collection<Episode> batch) {
        if (readOnly) {
            throw new UnsupportedOperationException("existing-only store does not accept writes");
        }
        Map<String, Episode> unique = new LinkedHashMap<>();
        batch.forEach(e -> unique.putIfAbsent(e.getEpisodeId(), e));
        Set<String> existing = existingEpisodeIds(unique.keySet());
        List<Record> rows = new ArrayList<>();
        Map<String, SeriesKey> batchSeries = new LinkedHashMap<>();
        for (Episode e : unique.values()) {
            if (!existing.contains(e.getEpisodeId())) {
                rows.add(EpisodeRows.toRow(e));
            }
            batchSeries.putIfAbsent(e.getSeries().getSeriesId(), e.getSeries());
        }
        IcebergIO.append(episodes, rows);
        if (!batchSeries.isEmpty()) {
            // every series of the batch, not only those of new episodes: a retry after a failed series append
            // finds its episodes written and must still add the series row
            series.refresh();
            Set<String> known = new HashSet<>();
            scan(series, Expressions.in("series_id", batchSeries.keySet().toArray()), r -> known.add((String) r.getField("series_id")));
            IcebergIO.append(series, batchSeries.values().stream()
                    .filter(k -> !known.contains(k.getSeriesId())).map(EpisodeRows::seriesRow).toList());
        }
        return new WriteResult(rows.size(), batch.size() - rows.size(), 0);
    }

    @Override
    public synchronized List<Episode> read(EpisodeQuery q) {
        episodes.refresh();
        Expression filter = Expressions.and(
                Expressions.lessThan("t_start", q.toMicros()),
                Expressions.greaterThan("t_end", q.fromMicros()));
        SeriesSelector s = q.selector();
        if (s.metric() != null) {
            filter = Expressions.and(filter, Expressions.equal("metric", s.metric()));
        }
        if (s.seriesIds() != null) {
            filter = Expressions.and(filter, Expressions.in("series_id", s.seriesIds().toArray()));
        }
        Map<String, Episode> byId = new LinkedHashMap<>();
        scan(episodes, filter, r -> {
            Episode e = EpisodeRows.fromRow(r);
            if (s.matches(e.getSeries())) {
                byId.merge(e.getEpisodeId(), e, (a, b) -> b.getRevision() > a.getRevision() ? b : a);
            }
        });
        List<Episode> out = new ArrayList<>();
        for (Episode e : byId.values()) {
            Episode r = q.clip() ? EpisodeOps.clip(e, q.fromMicros(), q.toMicros()) : e;
            if (r != null) {
                out.add(r);
            }
        }
        out.sort(EpisodeOps.READ_ORDER);
        return out.size() > q.limit() ? new ArrayList<>(out.subList(0, q.limit())) : out;
    }

    @Override
    public synchronized Optional<Episode> episode(String episodeId) {
        episodes.refresh();
        Episode[] found = new Episode[1];
        scan(episodes, Expressions.equal("episode_id", episodeId), r -> {
            Episode e = EpisodeRows.fromRow(r);
            if (found[0] == null || e.getRevision() > found[0].getRevision()) {
                found[0] = e;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    @Override
    public synchronized List<SeriesKey> listSeries(SeriesSelector selector) {
        series.refresh();
        List<SeriesKey> out = new ArrayList<>();
        Expression filter = selector.metric() == null ? Expressions.alwaysTrue() : Expressions.equal("metric", selector.metric());
        scan(series, filter, r -> {
            SeriesKey k = EpisodeRows.seriesFromRow(r);
            if (selector.matches(k)) {
                out.add(k);
            }
        });
        return out;
    }

    /** The underlying episodes table, e.g. for snapshot-based (time-travel) reads. */
    public Table episodesTable() {
        if (readOnly) {
            throw new UnsupportedOperationException("existing-only store does not expose a mutable table");
        }
        return episodes;
    }

    @Override
    public void close() {
        if (ownsCatalog) {
            closeCatalog(catalog);
        }
    }

    private static void closeCatalog(Catalog catalog) {
        if (catalog instanceof Closeable c) {
            try {
                c.close();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot close catalog " + catalog.name(), e);
            }
        }
    }

    private Set<String> existingEpisodeIds(Set<String> ids) {
        Set<String> out = new HashSet<>();
        if (ids.isEmpty()) {
            return out;
        }
        episodes.refresh();
        try (CloseableIterable<Record> rows = IcebergGenerics.read(episodes)
                .select("episode_id").where(Expressions.in("episode_id", ids.toArray())).build()) {
            rows.forEach(r -> out.add((String) r.getField("episode_id")));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read table " + episodes.name(), e);
        } catch (NotFoundException e) {
            throw new UncheckedIOException("cannot read table " + episodes.name(), new IOException(e.getMessage(), e));
        }
        return out;
    }

    static void scan(Table table, Expression filter, java.util.function.Consumer<Record> sink) {
        try (CloseableIterable<Record> rows = IcebergGenerics.read(table).where(filter).build()) {
            rows.forEach(sink);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read table " + table.name(), e);
        } catch (NotFoundException e) {
            throw new UncheckedIOException("cannot read table " + table.name(), new IOException(e.getMessage(), e));
        }
    }

    static void createNamespace(Catalog catalog, Namespace namespace) {
        if (catalog instanceof SupportsNamespaces ns && !ns.namespaceExists(namespace)) {
            try {
                ns.createNamespace(namespace);
            } catch (org.apache.iceberg.exceptions.AlreadyExistsException ignored) {
                // created concurrently
            }
        }
    }

    static Table loadOrCreate(Catalog catalog, TableIdentifier id, java.util.function.Supplier<Table> create) {
        if (catalog.tableExists(id)) {
            return catalog.loadTable(id);
        }
        try {
            return create.get();
        } catch (org.apache.iceberg.exceptions.AlreadyExistsException e) {
            return catalog.loadTable(id);
        }
    }
}
