package org.opentsx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.opentsx.model.OpenTsxAvro;
import org.opentsx.model.v2.BucketManifest;
import org.opentsx.model.v2.BucketState;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Current manifests, derived by scanning the buckets topic (no compaction on KafScale).
 * A SEALED manifest always wins over OPEN; otherwise the later record wins.
 */
public final class BucketCatalog {

    private BucketCatalog() {
    }

    public static Map<String, BucketManifest> load(Consumer<byte[], byte[]> consumer, TopicLayout layout, Duration timeout) {
        OpenTsxAvro avro = new OpenTsxAvro();
        Map<String, BucketManifest> out = new LinkedHashMap<>();
        LogReader.readAll(consumer, layout.buckets(), r -> {
            BucketManifest m = avro.decode(r.value(), BucketManifest.class);
            out.merge(m.getBucketId(), m, (old, neu) ->
                    old.getState() == BucketState.SEALED && neu.getState() != BucketState.SEALED ? old : neu);
        }, timeout);
        return out;
    }
}
