package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.wayang.site.EphemeralSiteTlsFixture;
import org.opentsx.wayang.site.TsxPlanClient;
import org.opentsx.wayang.site.TsxPlanService;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.SocketException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "KAFSCALE_BOOTSTRAP", matches = ".+")
class ConformanceHandshakeTest {
    @TempDir Path temporary;

    @Test void anActualWrongInputFingerprintIsRefusedOverMutualTls() throws Exception {
        try (var fixture = new FederationFixture(temporary.resolve("fingerprint"), FederationFixture.Fault.WRONG_FINGERPRINT);
             var session = fixture.conformanceSession()) {
            var response = session.conform("s1");
            assertEquals("refused", response.path("result").asText());
            assertEquals("topics.fingerprints", response.path("check").asText());
        }
    }

    @Test void anActualSeriesFromAnAcceptedSiteIsRefusedAtAnotherSite() throws Exception {
        try (var fixture = new FederationFixture(temporary.resolve("overlap"), FederationFixture.Fault.OVERLAPPING_SERIES);
             var session = fixture.conformanceSession()) {
            assertEquals("accepted", session.conform("s1").path("result").asText());
            var response = session.conform("s2");
            assertEquals("refused", response.path("result").asText());
            assertEquals("series.disjoint", response.path("check").asText());
        }
    }

    @Test void aTrustedServerRejectsAClientWithoutACertificateDuringTls() throws Exception {
        var driver = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var site = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicInteger callbacks = new AtomicInteger();
        try (var tls = EphemeralSiteTlsFixture.create("s1");
             var service = new TsxPlanService(driver.getPublic(), Map.of("s1", site.getPublic()), tls.certificateSites(),
                     temporary.resolve("tls-service"), Clock.systemUTC(), (plan, value) -> callbacks.incrementAndGet(),
                     (plan, value) -> callbacks.incrementAndGet())) {
            var server = service.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), tls.serverContext());
            URI endpoint = URI.create("https://localhost:" + server.getAddress().getPort());
            TsxPlanClient authenticated = new TsxPlanClient(endpoint, "s1", tls.clientContext("s1"));
            assertEquals(404, assertThrows(TsxPlanService.HttpFailure.class, () -> authenticated.challenge("missing")).status());
            try (SSLSocket socket = (SSLSocket) tls.anonymousClientContext().getSocketFactory()
                    .createSocket("localhost", server.getAddress().getPort())) {
                try {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setProtocols(new String[]{"TLSv1.3"});
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                    socket.setSoTimeout(10_000);
                    socket.startHandshake();
                    socket.getOutputStream().write("GET /v1/sites/s1/plans?after=0 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertEquals(-1, socket.getInputStream().read(), "an unauthenticated TLS connection must close without an HTTP response");
                } catch (IOException failure) {
                    assertTrue(failure instanceof SSLException || failure instanceof SocketException, failure.getClass().getName());
                }
            }
            assertEquals(404, assertThrows(TsxPlanService.HttpFailure.class, () -> authenticated.challenge("missing")).status());
            assertEquals(0, callbacks.get());
        }
    }
}
