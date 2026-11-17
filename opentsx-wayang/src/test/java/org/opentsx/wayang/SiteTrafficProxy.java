package org.opentsx.wayang;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class SiteTrafficProxy implements AutoCloseable {
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "site-traffic-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicLong clientBytes = new AtomicLong();
    private volatile boolean closing;

    SiteTrafficProxy(int backend) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(() -> {
            while (!closing) {
                try {
                    Socket client = server.accept();
                    Socket remote = new Socket(InetAddress.getLoopbackAddress(), backend);
                    client.setTcpNoDelay(true);
                    remote.setTcpNoDelay(true);
                    sockets.add(client);
                    sockets.add(remote);
                    AtomicBoolean finished = new AtomicBoolean();
                    threads.submit(() -> copy(client, remote, true, finished));
                    threads.submit(() -> copy(remote, client, false, finished));
                } catch (IOException error) {
                    if (!closing) failure.compareAndSet(null, error);
                }
            }
        });
    }

    int port() { return server.getLocalPort(); }

    private void copy(Socket from, Socket to, boolean outgoing, AtomicBoolean finished) {
        try {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = from.getInputStream().read(buffer)) != -1) {
                to.getOutputStream().write(buffer, 0, count);
                if (outgoing) clientBytes.addAndGet(count);
            }
        } catch (IOException error) {
            if (!closing && !finished.get()) failure.compareAndSet(null, error);
        } finally {
            finished.set(true);
            try { from.close(); } catch (IOException error) { if (!closing) failure.compareAndSet(null, error); }
            try { to.close(); } catch (IOException error) { if (!closing) failure.compareAndSet(null, error); }
            sockets.remove(from);
            sockets.remove(to);
        }
    }

    void check() {
        if (failure.get() != null) throw new IllegalStateException("TLS traffic observer failed", failure.get());
    }

    @Override
    public void close() throws IOException {
        closing = true;
        IOException error = null;
        try { server.close(); } catch (IOException problem) { error = problem; }
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException problem) {
                if (error == null) error = problem; else error.addSuppressed(problem);
            }
        }
        threads.shutdownNow();
        try {
            if (!threads.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("TLS traffic observer did not stop");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while stopping TLS traffic observer", interrupted);
        }
        check();
        if (error != null) throw error;
    }
}
