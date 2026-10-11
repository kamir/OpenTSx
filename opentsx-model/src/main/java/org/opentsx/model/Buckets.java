package org.opentsx.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

/** Bucket helpers shared by writers and readers (Java and Python must agree). */
public final class Buckets {

    private Buckets() {
    }

    /**
     * Checksum of a bucket's content: lower-case hex xxHash64 (seed 0) over the distinct episode ids,
     * sorted by their UTF-8 bytes and joined with {@code '\n'}. Order of writing and duplicates do not matter.
     */
    public static String checksum(Collection<String> episodeIds) {
        String[] ids = episodeIds.stream().distinct().toArray(String[]::new);
        Arrays.sort(ids, (a, b) -> Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        return XxHash64.hex(XxHash64.hash(String.join("\n", ids).getBytes(StandardCharsets.UTF_8)));
    }
}
