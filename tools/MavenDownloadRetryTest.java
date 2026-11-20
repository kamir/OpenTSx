import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.http.HttpVersion;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.BasicHttpContext;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.UpdateCheck;
import org.eclipse.aether.internal.impl.DefaultTrackingFileManager;
import org.eclipse.aether.internal.impl.DefaultTransporterProvider;
import org.eclipse.aether.internal.impl.DefaultUpdateCheckManager;
import org.eclipse.aether.internal.impl.DefaultUpdatePolicyAnalyzer;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.ArtifactTransfer;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.repository.SimpleResolutionErrorPolicy;

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
        boolean errors = false;
        for (String option : Files.readAllLines(path)) {
            if (option.isBlank()) {
                continue;
            }
            if (option.equals("--errors")) {
                require(!errors, "Duplicate Maven error diagnostics option");
                errors = true;
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
        String logger = "org.slf4j.simpleLogger.log.org.eclipse.aether.internal.impl.DefaultTransporterProvider";
        require("debug".equals(properties.get(logger)), "The selected Maven transporter must be logged");
        System.setProperty(logger, properties.get(logger).toString());
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

    private static void verifyFailureClassification(Transporter transporter, RemoteRepository repository) throws Exception {
        Class<?> type = Class.forName("org.eclipse.aether.connector.basic.ArtifactTransportListener");
        Constructor<?> constructor = type.getDeclaredConstructor(
                ArtifactTransfer.class, RemoteRepository.class, TransferEvent.Builder.class);
        constructor.setAccessible(true);
        Method failed = type.getDeclaredMethod("transferFailed", Exception.class, int.class);
        failed.setAccessible(true);
        ArtifactDownload download = new ArtifactDownload().setArtifact(
                new DefaultArtifact("org.opentsx.test:missing:jar:1.0.0"));
        Object listener = constructor.newInstance(download, repository, null);
        for (int status : new int[] {401, 403, 404, 429, 500, 502, 503, 504}) {
            Exception failure = new org.apache.http.client.HttpResponseException(status, "test");
            failed.invoke(listener, failure, transporter.classify(failure));
            ArtifactTransferException reported = download.getException();
            require(!reported.isFromCache(), "A fresh transport failure was reported as cached");
            if (status == 404) {
                require(reported instanceof ArtifactNotFoundException && reported.getCause() == null,
                        "The pinned connector's missing-artifact diagnostic behavior changed");
            } else {
                require(!(reported instanceof ArtifactNotFoundException) && reported.getCause() == failure,
                        "A transfer error lost its HTTP cause");
            }
        }
    }

    private static void verifyCachedFailure(RemoteRepository repository) throws Exception {
        Path directory = Files.createTempDirectory("opentsx-maven-cache-");
        Path artifactFile = directory.resolve("missing-1.0.0.jar");
        Path tracking = directory.resolve("missing-1.0.0.jar.lastUpdated");
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setResolutionErrorPolicy(new SimpleResolutionErrorPolicy(true, true));
        DefaultUpdateCheckManager manager = new DefaultUpdateCheckManager(
                new DefaultTrackingFileManager(), new DefaultUpdatePolicyAnalyzer());
        Artifact artifact = new DefaultArtifact("org.opentsx.test:missing:jar:1.0.0");
        try {
            for (String error : new String[] {"", "status code: 429"}) {
                Files.writeString(tracking, "https\\://invalid.invalid/.error=" + error
                        + "\nhttps\\://invalid.invalid/.lastUpdated=1\n"
                        + "@default-central-https\\://invalid.invalid/.lastUpdated=1\n");
                UpdateCheck<Artifact, ArtifactTransferException> check = new UpdateCheck<>();
                check.setItem(artifact).setFile(artifactFile.toFile()).setRepository(repository)
                        .setPolicy(RepositoryPolicy.UPDATE_POLICY_NEVER);
                manager.checkArtifact(session, check);
                require(!check.isRequired() && check.getException() != null && check.getException().isFromCache(),
                        "A cached failure did not retain its cache classification");
                require(check.getException().getMessage().contains("cached in the local repository"),
                        "A cached failure omitted the cache diagnostic");
                check = new UpdateCheck<>();
                check.setItem(artifact).setFile(artifactFile.toFile()).setRepository(repository)
                        .setPolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
                manager.checkArtifact(session, check);
                require(check.isRequired() && check.getException() == null,
                        "An explicit update policy did not request resolution");
            }
        } finally {
            Files.deleteIfExists(tracking);
            Files.delete(directory);
        }
    }

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1, "Expected the Maven configuration path");
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setConfigProperties(configuration(Path.of(arguments[0])));
        RemoteRepository repository = new RemoteRepository.Builder(
                "central", "default", "https://invalid.invalid/").build();
        DefaultTransporterProvider provider = new DefaultTransporterProvider(
                Set.of(new HttpTransporterFactory(Map.of())));
        try (Transporter transporter = provider.newTransporter(session, repository)) {
            require(transporter.getClass().getSimpleName().equals("HttpTransporter"),
                    "The verified Maven transporter must be the native HTTP implementation");
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
            verifyFailureClassification(transporter, repository);
            verifyCachedFailure(repository);
        }
        System.out.println("Maven download retry policy passed " + assertions
                + " assertions without network or sleeps");
    }
}
