import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpVersion;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.BasicHttpContext;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

public final class MavenDownloadRetryTest {
    private static int assertions;

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Map<String, Object> configuration(Path path) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        for (String option : Files.readAllLines(path)) {
            if (option.isBlank()) {
                continue;
            }
            int separator = option.indexOf('=');
            if (!option.startsWith("-D") || separator <= 2 || separator == option.length() - 1) {
                throw new IllegalArgumentException("Expected a Maven configuration property with a value");
            }
            String key = option.substring(2, separator);
            if (key.chars().anyMatch(Character::isWhitespace)
                    || properties.putIfAbsent(key, option.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("Invalid or duplicate Maven configuration property");
            }
        }
        return properties;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static ServiceUnavailableRetryStrategy responseRetryStrategy(Transporter transporter) throws Exception {
        Object executor = field(field(transporter, "client"), "execChain");
        while (!executor.getClass().getSimpleName().equals("ServiceUnavailableRetryExec")) {
            executor = field(executor, "requestExecutor");
        }
        return (ServiceUnavailableRetryStrategy) field(executor, "retryStrategy");
    }

    private static BasicHttpResponse response(int status) {
        return new BasicHttpResponse(HttpVersion.HTTP_1_1, status, "test");
    }

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1, "Expected the Maven configuration path");
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setConfigProperties(configuration(Path.of(arguments[0])));
        RemoteRepository repository = new RemoteRepository.Builder(
                "central", "default", "https://invalid.invalid/").build();
        try (Transporter transporter = new HttpTransporterFactory(Map.of()).newInstance(session, repository)) {
            ServiceUnavailableRetryStrategy strategy = responseRetryStrategy(transporter);
            BasicHttpContext context = new BasicHttpContext();
            for (int status : new int[] {429, 500, 502, 503, 504}) {
                for (int execution = 1; execution <= 5; execution++) {
                    require(strategy.retryRequest(response(status), execution, context),
                            "Configured transient response did not retry through the fifth attempt");
                    require(strategy.getRetryInterval() == execution * 3000L,
                            "Configured retry interval did not increase linearly");
                }
                require(!strategy.retryRequest(response(status), 6, context),
                        "Configured retry count did not bound the attempts");
                require(strategy.getRetryInterval() == 0L, "Exhausted retries retained an old interval");
            }
            for (int status : new int[] {200, 400, 401, 403, 404}) {
                require(!strategy.retryRequest(response(status), 1, context),
                        "A successful or permanent response was retried");
                require(strategy.getRetryInterval() == 0L, "A permanent response retained an old interval");
            }
            BasicHttpResponse rateLimited = response(429);
            rateLimited.setHeader("Retry-After", "7");
            require(strategy.retryRequest(rateLimited, 1, context), "A bounded Retry-After was not retried");
            require(strategy.getRetryInterval() == 7000L, "Retry-After seconds were not respected");
            require(strategy.getRetryInterval() == 0L, "A consumed retry interval was retained");
            rateLimited.setHeader("Retry-After", "301");
            require(!strategy.retryRequest(rateLimited, 1, context), "An excessive Retry-After was accepted");
            require(strategy.getRetryInterval() == 0L, "An excessive Retry-After retained an old interval");
            rateLimited.setHeader("Retry-After", "invalid");
            require(strategy.retryRequest(rateLimited, 2, context),
                    "An invalid Retry-After blocked configured retries");
            require(strategy.getRetryInterval() == 6000L, "An invalid Retry-After replaced the configured interval");
            require(transporter.classify(new org.apache.http.client.HttpResponseException(404, "test"))
                    == Transporter.ERROR_NOT_FOUND, "HTTP 404 was not classified as a missing artifact");
            for (int status : new int[] {401, 403, 429, 500, 502, 503, 504}) {
                require(transporter.classify(new org.apache.http.client.HttpResponseException(status, "test"))
                        == Transporter.ERROR_OTHER, "An HTTP failure was classified as a missing artifact");
            }
        }
        System.out.println("Maven download retry policy passed " + assertions
                + " assertions without network or sleeps");
    }
}
