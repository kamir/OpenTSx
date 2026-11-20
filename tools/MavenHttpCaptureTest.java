package org.opentsx.build;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.execchain.ClientExecChain;
import org.apache.http.message.BasicHttpResponse;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

public final class MavenHttpCaptureTest {
    private static int assertions;
    private static final String PATH = "/maven2/org/apache/wayang/wayang-core/1.1.1/wayang-core-1.1.1.pom";

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Response extends BasicHttpResponse implements CloseableHttpResponse {
        private boolean closed;

        private Response(int status) {
            super(HttpVersion.HTTP_1_1, status, "private-response-value");
            setEntity(new StringEntity("private-response-body", StandardCharsets.UTF_8));
            setHeader("Set-Cookie", "private-cookie-value");
            setHeader("Server", "private-header-value");
            setHeader("Content-Type", "text/html; private-parameter=value");
            setHeader("X-Cache", "MISS");
            setHeader("Age", "42");
            setHeader("Retry-After", "private-retry-value");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static Field field(Object target, String name) throws Exception {
        Field result = target.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        Path output = directory.resolve("responses.jsonl");
        Response response = new Response(404);
        HttpRequestWrapper request = HttpRequestWrapper.wrap(new HttpGet("https://repo.maven.apache.org" + PATH));
        request.setHeader("Authorization", "private-request-value");
        HttpRoute route = new HttpRoute(new HttpHost("repo.maven.apache.org", 443, "https"));
        int[] calls = {0};
        ClientExecChain delegate = (actualRoute, actualRequest, context, aware) -> {
            calls[0]++;
            require(actualRoute == route && actualRequest == request, "Capture changed the request");
            return response;
        };
        DiagnosticHttpTransporterFactory.CaptureExec capture =
                new DiagnosticHttpTransporterFactory.CaptureExec(delegate, output);
        require(capture.execute(route, request, HttpClientContext.create(), null) == response,
                "Capture changed the response");
        require(calls[0] == 1 && !response.closed, "Capture repeated the request or closed the response");
        require(response.getEntity().getContent().read() == 'p', "Capture consumed response content");
        String record = Files.readString(output);
        require(record.contains("\"status\":404") && record.contains(PATH), "HTTP status or request path was lost");
        require(record.contains("\"cache_state\":\"MISS\""), "Safe cache classification was lost");
        require(record.contains("\"age_seconds\":42") && record.contains("\"retry_after_seconds\":null"),
                "Numeric response metadata was not validated");
        require(!record.contains("private-") && !record.contains("Authorization") && !record.contains("Set-Cookie"),
                "Capture exposed request, response or cookie values");
        for (String uri : new String[] {"https://repo.maven.apache.org" + PATH + "?private=value",
                "https://repo.maven.apache.org" + PATH + "#private", "https://private@repo.maven.apache.org" + PATH,
                "https://repo.maven.apache.org/private/path", "http://repo.maven.apache.org" + PATH,
                "https://repo.maven.apache.org/private-prefix" + PATH}) {
            DiagnosticHttpTransporterFactory.record(output, route,
                    HttpRequestWrapper.wrap(new HttpGet(URI.create(uri))), response);
            require(record.equals(Files.readString(output)), "Capture accepted an unsafe request URI");
        }
        HttpRoute other = new HttpRoute(new HttpHost("private.invalid", 443, "https"));
        DiagnosticHttpTransporterFactory.record(output, other, request, response);
        require(record.equals(Files.readString(output)), "Capture accepted a private endpoint");
        Path link = directory.resolve("link.jsonl");
        Files.createSymbolicLink(link, output);
        try {
            DiagnosticHttpTransporterFactory.record(link, route, request, response);
            throw new AssertionError("Capture accepted a symbolic link");
        } catch (IOException expected) {
            require(record.equals(Files.readString(output)), "Capture changed the link target");
        }
        IOException failure = new IOException("private-io-value");
        capture = new DiagnosticHttpTransporterFactory.CaptureExec((a, b, c, d) -> { throw failure; }, output);
        try {
            capture.execute(route, request, HttpClientContext.create(), null);
            throw new AssertionError("Capture swallowed a transport failure");
        } catch (IOException expected) {
            require(expected == failure, "Capture replaced the transport failure");
        }
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setConfigProperty("aether.connector.http.retryHandler.interval", 0);
        RemoteRepository repository = new RemoteRepository.Builder(
                "central", "default", "https://repo.maven.apache.org/maven2/").build();
        DiagnosticHttpTransporterFactory factory = new DiagnosticHttpTransporterFactory(new HttpTransporterFactory(Map.of()));
        try (Transporter transporter = factory.newInstance(session, repository)) {
            Object client = field(transporter, "client").get(transporter);
            Object executor = field(client, "execChain").get(client);
            while (!executor.getClass().getSimpleName().equals("ServiceUnavailableRetryExec")) {
                executor = field(executor, "requestExecutor").get(executor);
            }
            Object captured = field(executor, "requestExecutor").get(executor);
            require(captured instanceof DiagnosticHttpTransporterFactory.CaptureExec,
                    "Capture was not installed before response retries");
            int[] attempts = {0};
            field(captured, "delegate").set(captured, (ClientExecChain) (a, b, c, d) -> {
                attempts[0]++;
                return new Response(404);
            });
            try {
                transporter.get(new GetTask(URI.create("org/apache/wayang/wayang-core/1.1.1/wayang-core-1.1.1.pom")));
                throw new AssertionError("A missing artifact was accepted");
            } catch (HttpResponseException expected) {
                require(expected.getStatusCode() == 404 && attempts[0] == 1,
                        "A missing artifact was retried or changed classification");
            }
        }
        System.out.println("Maven HTTP capture passed " + assertions + " assertions without network or sleeps");
    }
}
