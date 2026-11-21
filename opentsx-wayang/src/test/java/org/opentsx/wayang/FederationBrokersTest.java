package org.opentsx.wayang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederationBrokersTest {

    @Test
    void aReservedPortThatAnotherProcessAnswersIsRetriedWithANewPort(@TempDir Path directory) throws Exception {
        List<Integer> launched = new ArrayList<>();
        List<Path> states = new ArrayList<>();
        List<ServerSocket> foreign = new ArrayList<>();
        try (var brokers = new FederationBrokers(directory, List.of("s3"), (port, state) -> {
            launched.add(port);
            states.add(state);
            if (launched.size() > 1) return fake("ready", port);
            foreign.add(hold(port));
            return fake("proxy-taken", port);
        }, Duration.ofSeconds(30))) {
            assertEquals(2, launched.size());
            assertEquals(launched.get(1), brokers.port("s3"));
            assertNotEquals(foreign.get(0).getLocalPort(), brokers.port("s3"));
            assertEquals(List.of(directory.resolve("s3-state"), directory.resolve("s3.2-state")), states);
            assertTrue(Files.readString(directory.resolve("s3.log")).contains("bind: address already in use"));
            assertTrue(Files.readString(directory.resolve("s3.2.log")).contains("msg=\"proxy listening\" addr=" + brokers.bootstrap("s3") + "\n"));
        } finally {
            close(foreign);
        }
    }

    @Test
    void aClusterWhoseBrokerLostItsPortIsRetriedAlthoughItsProxyListened(@TempDir Path directory) throws Exception {
        List<Integer> launched = new ArrayList<>();
        List<ServerSocket> foreign = new ArrayList<>();
        try (var brokers = new FederationBrokers(directory, List.of("s2"), (port, state) -> {
            launched.add(port);
            if (launched.size() > 1) return fake("ready", port);
            foreign.add(hold(0));
            return fake("broker-taken", port, foreign.get(0).getLocalPort());
        }, Duration.ofSeconds(30))) {
            assertEquals(2, launched.size());
            assertEquals(launched.get(1), brokers.port("s2"));
            assertTrue(Files.readString(directory.resolve("s2.log")).contains("broker server error"));
        } finally {
            close(foreign);
        }
    }

    @Test
    void aClusterThatKeepsLosingItsPortFailsAfterFiveAttemptsAndNamesTheLastLog(@TempDir Path directory) throws Exception {
        List<Integer> launched = new ArrayList<>();
        List<ServerSocket> foreign = new ArrayList<>();
        try {
            IOException error = assertThrows(IOException.class, () -> new FederationBrokers(directory, List.of("s1"), (port, state) -> {
                launched.add(port);
                foreign.add(hold(port));
                return fake("proxy-taken", port);
            }, Duration.ofSeconds(30)).close());
            assertEquals("disposable cluster s1 lost its listener port in 5 attempts; inspect " + directory.resolve("s1.5.log"), error.getMessage());
            assertEquals(5, launched.size());
        } finally {
            close(foreign);
        }
    }

    @Test
    void aClusterThatExitsForAnotherReasonFailsWithoutRetry(@TempDir Path directory) {
        List<Integer> launched = new ArrayList<>();
        IOException error = assertThrows(IOException.class, () -> new FederationBrokers(directory, List.of("s1"), (port, state) -> {
            launched.add(port);
            return fake("exit", port);
        }, Duration.ofSeconds(30)).close());
        assertEquals("disposable cluster s1 exited; inspect " + directory.resolve("s1.log"), error.getMessage());
        assertEquals(1, launched.size());
    }

    @Test
    void aClusterWhoseLogShowsItsProxyButNoBrokerIsNotAccepted(@TempDir Path directory) {
        List<Integer> launched = new ArrayList<>();
        IOException error = assertThrows(IOException.class, () -> new FederationBrokers(directory, List.of("s2"), (port, state) -> {
            launched.add(port);
            return fake("no-broker", port);
        }, Duration.ofSeconds(2)).close());
        assertEquals("disposable cluster s2 did not report its Proxy and broker listeners within 2 seconds; inspect " + directory.resolve("s2.log"), error.getMessage());
        assertEquals(1, launched.size());
    }

    @Test
    void aClusterWhoseLogShowsItsBrokerButNoProxyIsNotAcceptedAlthoughItsPortAnswers(@TempDir Path directory) throws Exception {
        List<Integer> launched = new ArrayList<>();
        List<ServerSocket> foreign = new ArrayList<>();
        try {
            IOException error = assertThrows(IOException.class, () -> new FederationBrokers(directory, List.of("s3"), (port, state) -> {
                launched.add(port);
                foreign.add(hold(port));
                return fake("foreign-proxy", port);
            }, Duration.ofSeconds(2)).close());
            assertEquals("disposable cluster s3 did not report its Proxy and broker listeners within 2 seconds; inspect " + directory.resolve("s3.log"), error.getMessage());
            assertEquals(1, launched.size());
        } finally {
            close(foreign);
        }
    }

    private static ServerSocket hold(int port) {
        try {
            return new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static List<String> fake(Object... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes(), FakeKafScaleCluster.class.getName()));
        for (Object arg : args) command.add(arg.toString());
        return command;
    }

    private static String classes() {
        try {
            return Path.of(FakeKafScaleCluster.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void close(List<ServerSocket> sockets) throws IOException {
        for (ServerSocket socket : sockets) socket.close();
    }
}
