package org.opentsx.wayang;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

final class FederationBrokers implements AutoCloseable {
    private static final int ATTEMPTS = 5;
    private static final Pattern BROKER_LISTENING = Pattern.compile(" broker listening on 127\\.0\\.0\\.1:[1-9][0-9]*\n");
    private final List<Process> processes = new ArrayList<>();
    private final Map<String, Integer> ports = new LinkedHashMap<>();

    FederationBrokers(Path directory) throws IOException {
        this(directory, List.of("s1", "s2", "s3", "signal"));
    }

    FederationBrokers(Path directory, List<String> clusters) throws IOException {
        this(directory, validated(clusters), kafscaleCluster(), Duration.ofSeconds(30));
    }

    FederationBrokers(Path directory, List<String> clusters, BiFunction<Integer, Path, List<String>> command, Duration readiness) throws IOException {
        Files.createDirectories(directory);
        try {
            for (String cluster : clusters) start(directory, cluster, command, readiness);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("interrupted while starting disposable clusters");
            interrupted.initCause(error);
            try { close(); } catch (RuntimeException cleanup) { interrupted.addSuppressed(cleanup); }
            throw interrupted;
        } catch (IOException | RuntimeException error) {
            try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static List<String> validated(List<String> clusters) {
        if (clusters == null || clusters.isEmpty() || clusters.stream().anyMatch(cluster -> cluster == null
                || !cluster.matches("[a-z0-9][a-z0-9-]{0,62}")) || new java.util.HashSet<>(clusters).size() != clusters.size()) {
            throw new IllegalArgumentException("disposable cluster names must be unique valid identifiers");
        }
        return clusters;
    }

    private static BiFunction<Integer, Path, List<String>> kafscaleCluster() throws IOException {
        String binary = executable("KAFSCALE_CLUSTER_BINARY").toString();
        String broker = executable("KAFSCALE_BROKER_BINARY").toString();
        String proxy = executable("KAFSCALE_PROXY_BINARY").toString();
        return (port, state) -> List.of(binary, "--listen", "127.0.0.1:" + port, "--advertised-host", "127.0.0.1",
                "--advertised-port", Integer.toString(port), "--data-dir", state.toAbsolutePath().toString(),
                "--broker-binary", broker, "--proxy-binary", proxy);
    }

    private void start(Path directory, String cluster, BiFunction<Integer, Path, List<String>> command, Duration readiness) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            String name = attempt == 1 ? cluster : cluster + "." + attempt;
            Path log = directory.resolve(name + ".log");
            int port;
            try (ServerSocket reserved = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                port = reserved.getLocalPort();
            }
            ProcessBuilder builder = new ProcessBuilder(command.apply(port, directory.resolve(name + "-state")));
            builder.environment().clear();
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            Process process = builder.start();
            processes.add(process);
            if (ready(cluster, process, port, log, readiness)) {
                ports.put(cluster, port);
                return;
            }
            stop(process);
            processes.remove(process);
            if (attempt == ATTEMPTS) throw new IOException("disposable cluster " + cluster + " lost its listener port in " + ATTEMPTS + " attempts; inspect " + log);
        }
    }

    // The reserved port is released before the cluster binds it, so only the cluster's own log proves that its Proxy
    // and broker hold their listeners; an answer on the port can come from another process.
    private static boolean ready(String cluster, Process process, int port, Path log, Duration readiness) throws IOException, InterruptedException {
        String proxyListening = " msg=\"proxy listening\" addr=127.0.0.1:" + port + "\n";
        long deadline = System.nanoTime() + readiness.toNanos();
        IOException last = null;
        while (true) {
            boolean exited = process.waitFor(10, TimeUnit.MILLISECONDS);
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            if (output.contains("bind: address already in use")) return false;
            if (exited) throw new IOException("disposable cluster " + cluster + " exited; inspect " + log);
            if (output.contains(proxyListening) && BROKER_LISTENING.matcher(output).find()) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 100);
                    if (process.isAlive()) return true;
                } catch (IOException error) {
                    last = error;
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("disposable cluster " + cluster + " did not report its Proxy and broker listeners within " + readiness.toSeconds() + " seconds; inspect " + log, last);
            }
        }
    }

    private static Path executable(String name) throws IOException {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) throw new IllegalStateException(name + " must name a source-built KafScale v1.6.0 executable");
        Path binary = Path.of(configured).toRealPath();
        if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) throw new IllegalStateException(name + " is not executable");
        return binary;
    }

    int port(String cluster) {
        Integer port = ports.get(cluster);
        if (port == null) throw new IllegalArgumentException("unknown disposable cluster " + cluster);
        return port;
    }

    String bootstrap(String cluster) {
        return "127.0.0.1:" + port(cluster);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int index = processes.size() - 1; index >= 0; index--) {
            try {
                stop(processes.get(index));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                IllegalStateException interrupted = new IllegalStateException("interrupted while stopping disposable broker", error);
                if (failure == null) failure = interrupted; else failure.addSuppressed(interrupted);
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("disposable broker did not stop");
        }
    }
}
