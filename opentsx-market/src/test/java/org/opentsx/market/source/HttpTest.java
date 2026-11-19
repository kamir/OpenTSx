package org.opentsx.market.source;

import org.junit.jupiter.api.Test;
import org.opentsx.conf.OpenTsxConf;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Http} against a stub {@link HttpClient}: which failures are tried again; and the proxy rule. */
class HttpTest {

    static final String URL = "https://www.smard.de/app/chart_data/4169/DE-LU/index_hour.json";

    /** Answers the queued status codes or throws the queued exceptions, one per call. */
    static final class StubHttp extends HttpClient {
        final Deque<Object> answers = new ArrayDeque<>();
        int calls;

        StubHttp(Object... answers) {
            this.answers.addAll(List.of(answers));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            calls++;
            Object a = answers.removeFirst();
            if (a instanceof IOException e) {
                throw e;
            }
            if (a instanceof Response r) {
                return (HttpResponse<T>) new Response(request, r.statusCode(), r.headerMap());
            }
            return (HttpResponse<T>) new Response(request, (Integer) a, Map.of());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    record Response(HttpRequest request, int statusCode, Map<String, List<String>> headerMap)
            implements HttpResponse<String> {
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(headerMap, (k, v) -> true);
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public String body() {
            return "body " + statusCode;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private final List<Long> pauses = new ArrayList<>();

    private Http http(StubHttp stub) {
        return new Http(stub, 0, 4, pauses::add);
    }

    @Test
    void anUnknownHostOrATlsErrorFailsAfterOneCall() {
        ConnectException inner = new ConnectException();
        inner.initCause(new UnresolvedAddressException());
        ConnectException unresolved = new ConnectException();
        unresolved.initCause(inner);
        for (IOException failure : List.of(new UnknownHostException("www.smard.de"), unresolved,
                new SSLHandshakeException("PKIX path building failed"))) {
            StubHttp stub = new StubHttp(failure, 200);
            UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> http(stub).get(URL));
            assertEquals("GET " + URL + " failed after 1 attempt", e.getMessage());
            assertSame(failure, e.getCause());
            assertEquals(1, stub.calls, failure.toString());
        }
        assertEquals(List.of(), pauses);
    }

    @Test
    void aRefusedConnectionIsTriedAgainWithBackoff() {
        ConnectException refused = new ConnectException("Connection refused");
        StubHttp stub = new StubHttp(refused, refused, refused, refused, refused);
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> http(stub).get(URL));
        assertEquals("GET " + URL + " failed after 5 attempts", e.getMessage());
        assertEquals(5, stub.calls);
        assertEquals(List.of(2_000L, 4_000L, 8_000L, 16_000L), pauses);
        StubHttp once = new StubHttp(refused, 200);
        assertEquals("body 200", http(once).get(URL));
        assertEquals(2, once.calls);
    }

    @Test
    void aLongRetryAfterIsCappedAtSixtySecondsAndLogged() {
        Response r3600 = new Response(null, 503, Map.of("Retry-After", List.of("3600")));
        StubHttp stub = new StubHttp(r3600, r3600, 200);
        String log = stderrOf(() -> assertEquals("body 200", http(stub).get(URL)));
        assertEquals(List.of(60_000L, 60_000L), pauses);
        assertTrue(log.contains("WARN " + Http.class.getName() + " - Retry-After capped at 60 s url=" + URL
                + " status=503 retryAfterSeconds=3600"), log);
    }

    /** What {@code action} wrote to stderr, where slf4j-simple writes its lines. */
    static String stderrOf(Runnable action) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(stderr);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theJvmProxySettingsWinOverTheProxyKey() {
        assertSame(ProxySelector.getDefault(), Http.proxy("jvm-proxy.example", Optional.of("proxy.example:3128")));
        assertSame(ProxySelector.getDefault(), Http.proxy(null, Optional.empty()));
    }

    @Test
    void theProxyKeyComesFromOpentsxHttpsProxyWithoutJvmSettings() {
        OpenTsxConf conf = OpenTsxConf.load(List.of("market", "prices"),
                Map.of("OPENTSX_HTTPS_PROXY", "proxy.example:3128", "HTTPS_PROXY", "legacy.example:8080"), null).conf();
        assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 3128))),
                Http.proxy(null, conf.getOption(Http.PROXY)).select(URI.create(URL)));
        OpenTsxConf legacy = OpenTsxConf.load(List.of("market", "backfill"),
                Map.of("https_proxy", "http://legacy.example:8080"), null).conf();
        assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("legacy.example", 8080))),
                Http.proxy(null, legacy.getOption(Http.PROXY)).select(URI.create(URL)));
    }

    @Test
    void aMalformedProxyIsRefusedWithoutEchoingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Http.proxy("jvm-proxy.example", Optional.of("http://user:s3cret@")));
        assertEquals("opentsx.https.proxy: not a proxy address; expected <host>:<port> or http://<host>:<port>",
                e.getMessage());
    }
}
