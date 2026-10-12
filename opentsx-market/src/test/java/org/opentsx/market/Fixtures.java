package org.opentsx.market;

import org.opentsx.market.source.Fetcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test fixtures from {@code src/test/resources/market} and a fetcher that serves them by URL. */
final class Fixtures {

    private Fixtures() {
    }

    static String text(String name) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("market/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> lines(String name) {
        return text(name).lines().toList();
    }

    /** Serves fixture files (or literal JSON) for URLs containing a registered key and records every request. */
    static final class FakeFetcher implements Fetcher {
        final Map<String, String> bySuffix = new LinkedHashMap<>();
        final List<String> requests = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<String> posts = new ArrayList<>();
        String tokenResponse = "{\"access_token\":\"tok-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

        FakeFetcher serve(String urlSuffix, String fixture) {
            bySuffix.put(urlSuffix, fixture);
            return this;
        }

        @Override
        public String get(String uri, Map<String, String> h) {
            requests.add(uri);
            headers.add(h);
            for (Map.Entry<String, String> e : bySuffix.entrySet()) {
                if (uri.endsWith(e.getKey()) || uri.contains(e.getKey())) {
                    return e.getValue().startsWith("{") ? e.getValue() : text(e.getValue());
                }
            }
            throw new IllegalStateException("no fixture for " + uri);
        }

        @Override
        public String postForm(String uri, String form, Map<String, String> h) {
            posts.add(uri + " " + form);
            return tokenResponse;
        }
    }
}
