package org.opentsx.openmetadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

class HttpOmApiTest {

    private record Answer(int status, Map<String, List<String>> headers, IOException failure) {
    }

    /** Answers each request with the next answer and counts the requests. */
    private static final class ScriptedClient extends HttpClient {
        final Deque<Answer> answers;
        int requests;

        ScriptedClient(Answer... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            requests++;
            Answer a = answers.removeFirst();
            if (a.failure() != null) {
                throw a.failure();
            }
            return (HttpResponse<T>) new HttpResponse<String>() {
                public int statusCode() {
                    return a.status();
                }

                public HttpRequest request() {
                    return request;
                }

                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                public HttpHeaders headers() {
                    return HttpHeaders.of(a.headers(), (k, v) -> true);
                }

                public String body() {
                    return "{}";
                }

                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                public URI uri() {
                    return request.uri();
                }

                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        public Version version() {
            return Version.HTTP_1_1;
        }

        public Optional<Executor> executor() {
            return Optional.empty();
        }

        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }
    }

    private static Answer status(int status, Map<String, List<String>> headers) {
        return new Answer(status, headers, null);
    }

    private static Answer failure(IOException e) {
        return new Answer(0, Map.of(), e);
    }

    @Test
    void anIoErrorIsRetriedWithTheBackoff() {
        ScriptedClient http = new ScriptedClient(failure(new IOException("connection reset")), status(200, Map.of()));
        List<Long> waits = new ArrayList<>();
        HttpOmApi api = new HttpOmApi(URI.create("http://om.invalid/api"), "t", 5, http, waits::add);
        api.put("/v1/tags", new ObjectMapper().createObjectNode());
        assertEquals(2, http.requests);
        assertEquals(List.of(1000L), waits);
    }

    @Test
    void anIoErrorOnEveryAttemptFailsWithTheCause() {
        IOException last = new IOException("connection refused");
        ScriptedClient http = new ScriptedClient(failure(new IOException("connection reset")), failure(last));
        List<Long> waits = new ArrayList<>();
        HttpOmApi api = new HttpOmApi(URI.create("http://om.invalid/api"), "t", 2, http, waits::add);
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> api.put("/v1/tags", new ObjectMapper().createObjectNode()));
        assertEquals("PUT /v1/tags: no answer after 2 attempts", e.getMessage());
        assertSame(last, e.getCause());
        assertEquals(List.of(1000L), waits);
    }

    @Test
    void retryAfterSetsTheWaitAndTheFourStatusesAreRetried() {
        ScriptedClient http = new ScriptedClient(status(429, Map.of("Retry-After", List.of("5"))),
                status(502, Map.of()), status(503, Map.of("Retry-After", List.of("120"))), status(504, Map.of()),
                status(200, Map.of()));
        List<Long> waits = new ArrayList<>();
        HttpOmApi api = new HttpOmApi(URI.create("http://om.invalid/api"), "t", 5, http, waits::add);
        api.put("/v1/tags", new ObjectMapper().createObjectNode());
        assertEquals(5, http.requests);
        assertEquals(List.of(5000L, 2000L, 30000L, 8000L), waits);
    }

    @Test
    void otherStatusesFailAtOnce() {
        ScriptedClient http = new ScriptedClient(status(500, Map.of()));
        List<Long> waits = new ArrayList<>();
        HttpOmApi api = new HttpOmApi(URI.create("http://om.invalid/api"), "t", 5, http, waits::add);
        OmException e = assertThrows(OmException.class, () -> api.put("/v1/tags", new ObjectMapper().createObjectNode()));
        assertEquals(500, e.status());
        assertEquals(1, http.requests);
        assertEquals(List.of(), waits);
    }
}
