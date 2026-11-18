package org.opentsx.wayang;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class FederationBrokers implements AutoCloseable {
    private final List<Process> processes = new ArrayList<>();
    private final Map<String, Integer> ports = new LinkedHashMap<>();

    FederationBrokers(Path directory) throws IOException {
        String configured = System.getenv("KAFSCALE_BROKER_BINARY");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("KAFSCALE_BROKER_BINARY must name the source-built KafScale v1.6.0 broker");
        Path binary = Path.of(configured).toRealPath();
        if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) throw new IllegalStateException("KafScale broker binary is not executable");
        Files.createDirectories(directory);
        try {
            for (String cluster : List.of("s1", "s2", "s3", "signal")) {
                int port;
                try (ServerSocket reserved = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                    port = reserved.getLocalPort();
                }
                ProcessBuilder builder = new ProcessBuilder(binary.toString());
                builder.environment().clear();
                builder.environment().putAll(Map.of("KAFSCALE_USE_MEMORY_S3", "1", "KAFSCALE_BROKER_ID", "0",
                        "KAFSCALE_BROKER_ADDR", "127.0.0.1:" + port, "KAFSCALE_BROKER_HOST", "127.0.0.1",
                        "KAFSCALE_BROKER_PORT", Integer.toString(port), "KAFSCALE_METRICS_ADDR", "127.0.0.1:0",
                        "KAFSCALE_CONTROL_ADDR", "127.0.0.1:0"));
                builder.redirectErrorStream(true).redirectOutput(directory.resolve(cluster + ".log").toFile());
                Process process = builder.start();
                processes.add(process);
                ports.put(cluster, port);
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                IOException last = null;
                while (true) {
                    if (!process.isAlive()) throw new IOException("disposable broker exited; inspect " + directory.resolve(cluster + ".log"));
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 100);
                        break;
                    } catch (IOException error) {
                        last = error;
                    }
                    if (System.nanoTime() >= deadline) throw new IOException("disposable broker did not open its listener", last);
                    Thread.onSpinWait();
                }
            }
        } catch (IOException | RuntimeException error) {
            try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
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
            Process process = processes.get(index);
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("disposable broker did not stop");
                }
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
}
