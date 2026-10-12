package org.opentsx.market.source;

import java.util.Map;

/** Fetches a document by URL; {@link Http} online, a map of fixtures in tests. */
@FunctionalInterface
public interface Fetcher {

    String get(String uri, Map<String, String> headers);

    default String get(String uri) {
        return get(uri, Map.of());
    }

    /** GET of a binary document (zip files). */
    default byte[] getBytes(String uri) {
        return get(uri).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** POST of a form; only needed for OAuth token requests. */
    default String postForm(String uri, String form, Map<String, String> headers) {
        throw new UnsupportedOperationException("POST not supported by " + getClass().getSimpleName());
    }
}
