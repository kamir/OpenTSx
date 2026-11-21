package org.opentsx.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Bucket helpers shared by writers and readers (Java and Python must agree). */
public final class Buckets {

    /** Prefix of a {@link #contentChecksum}; a manifest checksum without it is an id-only {@link #checksum}. */
    public static final String CONTENT_PREFIX = "sha256:";

    private static final byte[] CONTENT_TAG = "opentsx.bucket.content.v1\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private Buckets() {
    }

    /**
     * Id-only checksum: lower-case hex xxHash64 (seed 0) over the distinct episode ids, sorted by their UTF-8 bytes
     * and joined with {@code '\n'}. Order of writing and duplicates do not matter. It does not cover episode contents;
     * readers use it to check manifests that hold no {@link #CONTENT_PREFIX}.
     */
    public static String checksum(Collection<String> episodeIds) {
        String[] ids = episodeIds.stream().distinct().toArray(String[]::new);
        Arrays.sort(ids, (a, b) -> Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        return XxHash64.hex(XxHash64.hash(String.join("\n", ids).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Checksum of a bucket's content: {@code "sha256:"} + lower-case hex SHA-256 over
     * {@code "opentsx.bucket.content.v1\n" || count || (len(utf8(id)) || utf8(id) || ascii(digest))} for every
     * distinct episode id in UTF-8 byte order, with {@code digest} its {@link Episodes#contentDigest} and every
     * integer signed 64-bit little-endian. Order of writing and repeated episodes with the same content do not
     * matter. One episode id with two contents throws {@code IllegalArgumentException}.
     */
    public static String contentChecksum(List<String> episodeIds, List<String> contentDigests) {
        if (episodeIds.size() != contentDigests.size()) {
            throw new IllegalArgumentException(contentDigests.size() + " content digests for " + episodeIds.size()
                    + " episode ids");
        }
        Map<byte[], String> byId = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < episodeIds.size(); i++) {
            String id = episodeIds.get(i);
            String digest = contentDigests.get(i);
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("episode ids must not be empty");
            }
            if (digest == null || !DIGEST.matcher(digest).matches()) {
                throw new IllegalArgumentException("content digest of episode " + id
                        + " is not 64 lower-case hex characters");
            }
            String earlier = byId.putIfAbsent(id.getBytes(StandardCharsets.UTF_8), digest);
            if (earlier != null && !earlier.equals(digest)) {
                throw new IllegalArgumentException("episode " + id + " appears with two contents");
            }
        }
        MessageDigest sha = sha256();
        ByteBuffer number = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        sha.update(CONTENT_TAG);
        sha.update(number.putLong(0, byId.size()).array());
        for (Map.Entry<byte[], String> e : byId.entrySet()) {
            sha.update(number.putLong(0, e.getKey().length).array());
            sha.update(e.getKey());
            sha.update(e.getValue().getBytes(StandardCharsets.US_ASCII));
        }
        return CONTENT_PREFIX + HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
