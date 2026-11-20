package org.opentsx.build;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.execchain.ClientExecChain;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

@Named("opentsx-http-capture")
@Singleton
public final class DiagnosticHttpTransporterFactory implements TransporterFactory {
    private static final Set<String> HOSTS = Set.of("repo.maven.apache.org", "packages.confluent.io");
    private static final Pattern ARTIFACT = Pattern.compile(
            "/(?:(?:maven2|maven)/)?(?:org/apache/wayang/(wayang-(?:core|basic|java))/"
            + "([0-9]+\\.[0-9]+\\.[0-9]+)/\\1-\\2\\.(?:pom|jar)"
            + "|org/junit/junit-bom/5\\.14\\.4/junit-bom-5\\.14\\.4\\.pom"
            + "|com/fasterxml/jackson/jackson-bom/2\\.23\\.0/jackson-bom-2\\.23\\.0\\.pom)");
    private static final List<String> HEADERS = List.of("age", "cache-control", "cf-cache-status", "cf-ray",
            "content-length", "content-type", "date", "etag", "retry-after", "server", "via",
            "x-cache", "x-cache-hits", "x-served-by");
    private final TransporterFactory delegate;

    @Inject
    public DiagnosticHttpTransporterFactory(@Named(HttpTransporterFactory.NAME) TransporterFactory delegate) {
        this.delegate = delegate;
        System.out.println("Maven HTTP response capture enabled");
    }

    @Override
    public float getPriority() {
        return delegate.getPriority() + 1.0f;
    }

    private static Field field(Object target, String name) throws ReflectiveOperationException {
        Field result = target.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    @Override
    public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository)
            throws NoTransporterException {
        Transporter transporter = delegate.newInstance(session, repository);
        URI uri = URI.create(repository.getUrl());
        if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getRawUserInfo() != null) {
            return transporter;
        }
        try {
            Object client = field(transporter, "client").get(transporter);
            Object executor = field(client, "execChain").get(client);
            int remaining = 16;
            while (!executor.getClass().getSimpleName().equals("ServiceUnavailableRetryExec")) {
                if (--remaining == 0) {
                    throw new IllegalStateException("Pinned HTTP retry executor was not found");
                }
                executor = field(executor, "requestExecutor").get(executor);
            }
            Field requestExecutor = field(executor, "requestExecutor");
            requestExecutor.set(executor, new CaptureExec((ClientExecChain) requestExecutor.get(executor),
                    Path.of("target/maven-downloads/http-responses.jsonl").toAbsolutePath()));
            return transporter;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            try {
                transporter.close();
            } catch (RuntimeException closing) {
                failure.addSuppressed(closing);
            }
            throw new IllegalStateException("Cannot install pinned Maven HTTP response capture", failure);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String category(CloseableHttpResponse response, String name, Set<String> allowed) {
        Header header = response.getFirstHeader(name);
        if (header == null) {
            return "absent";
        }
        String value = header.getValue().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return allowed.contains(value) ? value : "other";
    }

    private static String number(CloseableHttpResponse response, String name) {
        Header[] headers = response.getHeaders(name);
        if (headers.length != 1) {
            return "null";
        }
        String value = headers[0].getValue().trim();
        return value.matches("0|[1-9][0-9]{0,8}") ? value : "null";
    }

    static synchronized void record(Path output, HttpRoute route, HttpRequestWrapper request,
                                    CloseableHttpResponse response) throws IOException {
        String host = route.getTargetHost().getHostName();
        URI uri = request.getURI();
        String path = uri.getRawPath();
        String method = request.getMethod();
        if (!HOSTS.contains(host) || !"https".equals(route.getTargetHost().getSchemeName())
                || !Set.of("GET", "HEAD").contains(method) || path == null || path.length() > 500
                || !ARTIFACT.matcher(path).matches() || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null
                || uri.isAbsolute() && !"https".equals(uri.getScheme())
                || uri.getHost() != null && !host.equals(uri.getHost())) {
            return;
        }
        String cache = category(response, "X-Cache", Set.of("hit", "miss", "error", "pass", "stale"));
        String content = category(response, "Content-Type", Set.of("text/html", "text/plain", "application/xml",
                "application/java-archive", "application/octet-stream"));
        String server = category(response, "Server", Set.of("cloudflare", "amazons3", "nginx", "envoy", "varnish"));
        String cdn = category(response, "CF-Cache-Status", Set.of("hit", "miss", "expired", "stale", "bypass",
                "dynamic", "updating", "revalidated"));
        StringBuilder record = new StringBuilder("{\"host\":\"").append(host).append("\",\"path\":\"")
                .append(path).append("\",\"method\":\"").append(method).append("\",\"status\":")
                .append(response.getStatusLine().getStatusCode()).append(",\"cache_state\":\"")
                .append(cache.toUpperCase(Locale.ROOT)).append("\",\"content_kind\":\"").append(content)
                .append("\",\"server_kind\":\"").append(server).append("\",\"cdn_cache_state\":\"")
                .append(cdn.toUpperCase(Locale.ROOT)).append("\",\"age_seconds\":")
                .append(number(response, "Age")).append(",\"content_length\":")
                .append(number(response, "Content-Length")).append(",\"retry_after_seconds\":")
                .append(number(response, "Retry-After")).append(",\"header_sha256\":{");
        boolean first = true;
        for (String name : HEADERS) {
            Header[] headers = response.getHeaders(name);
            if (headers.length == 0) {
                continue;
            }
            StringBuilder values = new StringBuilder();
            for (Header header : headers) {
                values.append(header.getValue()).append('\n');
            }
            if (!first) {
                record.append(',');
            }
            first = false;
            record.append('"').append(name).append("\":\"").append(hash(values.toString())).append('"');
        }
        record.append("}}\n");
        Path parent = output.toAbsolutePath().getParent();
        for (Path part = parent; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IOException("HTTP capture directory must not be a symbolic link");
            }
        }
        Files.createDirectories(parent);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("HTTP capture output must be a regular file");
        }
        Files.writeString(output, record, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static final class CaptureExec implements ClientExecChain {
        private final ClientExecChain delegate;
        private final Path output;

        CaptureExec(ClientExecChain delegate, Path output) {
            this.delegate = delegate;
            this.output = output;
        }

        @Override
        public CloseableHttpResponse execute(HttpRoute route, HttpRequestWrapper request, HttpClientContext context,
                                              HttpExecutionAware aware) throws IOException, HttpException {
            CloseableHttpResponse response = delegate.execute(route, request, context, aware);
            try {
                record(output, route, request, response);
                return response;
            } catch (IOException | RuntimeException failure) {
                try {
                    response.close();
                } catch (IOException closing) {
                    failure.addSuppressed(closing);
                }
                throw failure;
            }
        }
    }
}
