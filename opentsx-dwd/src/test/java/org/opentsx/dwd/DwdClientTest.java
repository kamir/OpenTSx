package org.opentsx.dwd;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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

/** {@link DwdClient#get} against a stub {@link HttpClient}: which answers are tried again, and how long it waits. */
class DwdClientTest {

    static final String URL = "https://opendata.dwd.de/weather/x.kmz";

    /** Answers the queued status codes (with headers) or throws the queued exceptions, one per call. */
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
            if (a instanceof RuntimeException e) {
                throw e;
            }
            Map<String, List<String>> headers = a instanceof Answer x ? x.headers() : Map.of();
            int status = a instanceof Answer x ? x.status() : (Integer) a;
            return (HttpResponse<T>) new Response(request, status, HttpHeaders.of(headers, (k, v) -> true));
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

    record Answer(int status, Map<String, List<String>> headers) {
    }

    record Response(HttpRequest request, int statusCode, HttpHeaders headers) implements HttpResponse<byte[]> {
        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public byte[] body() {
            return ("body " + statusCode).getBytes(StandardCharsets.UTF_8);
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

    private final List<Duration> pauses = new ArrayList<>();

    private DwdClient client(StubHttp http) {
        return new DwdClient(http, pauses::add);
    }

    @Test
    void a503ThenA200SucceedsWithTwoCallsAndOnePause() throws IOException {
        StubHttp http = new StubHttp(503, 200);
        assertEquals("body 200", new String(client(http).get(URL), StandardCharsets.UTF_8));
        assertEquals(2, http.calls);
        assertEquals(List.of(Duration.ofSeconds(2)), pauses);
    }

    @Test
    void aTimeoutAndA429AreTriedAgainAndRetryAfterIsHonoured() throws IOException {
        StubHttp http = new StubHttp(new HttpTimeoutException("request timed out"),
                new Answer(429, Map.of("Retry-After", List.of("30"))), 200);
        client(http).get(URL);
        assertEquals(3, http.calls);
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(30)), pauses);
    }

    @Test
    void theRetriesAreBounded() {
        StubHttp http = new StubHttp(500, 502, new Answer(503, Map.of("Retry-After", List.of("3600"))), 504);
        IOException e = assertThrows(IOException.class, () -> client(http).get(URL));
        assertEquals("HTTP 504 for " + URL + " after 4 tries", e.getMessage());
        assertEquals(4, http.calls);
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(60)), pauses);
    }

    @Test
    void anUnknownHostFailsAfterOneCall() {
        UnknownHostException unknown = new UnknownHostException("opendata.dwd.de");
        StubHttp http = new StubHttp(unknown);
        IOException e = assertThrows(IOException.class, () -> client(http).get(URL));
        assertEquals("cannot fetch " + URL, e.getMessage());
        assertSame(unknown, e.getCause());
        assertEquals(1, http.calls);
        assertEquals(List.of(), pauses);

        ConnectException unresolved = new ConnectException();
        unresolved.initCause(new UnresolvedAddressException());
        StubHttp jdk = new StubHttp(unresolved);
        assertSame(unresolved, assertThrows(IOException.class, () -> client(jdk).get(URL)).getCause());
        assertEquals(1, jdk.calls);
    }

    @Test
    void aTlsErrorAndA404FailAfterOneCall() {
        StubHttp tls = new StubHttp(new SSLHandshakeException("PKIX path building failed"));
        assertThrows(IOException.class, () -> client(tls).get(URL));
        assertEquals(1, tls.calls);
        StubHttp missing = new StubHttp(404);
        assertEquals("HTTP 404 for " + URL, assertThrows(IOException.class, () -> client(missing).get(URL)).getMessage());
        assertEquals(1, missing.calls);
        assertEquals(List.of(), pauses);
    }

    @Test
    void anInterruptDuringThePauseStopsAndKeepsTheFlag() {
        StubHttp http = new StubHttp(503, 200);
        DwdClient c = new DwdClient(http, d -> {
            throw new InterruptedException();
        });
        try {
            assertThrows(IOException.class, () -> c.get(URL));
            assertEquals(true, Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, http.calls);
    }
}
