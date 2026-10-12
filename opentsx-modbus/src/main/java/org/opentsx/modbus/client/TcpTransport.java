package org.opentsx.modbus.client;

import org.opentsx.modbus.protocol.Frame;
import org.opentsx.modbus.protocol.Framing;
import org.opentsx.modbus.protocol.ModbusException;
import org.opentsx.modbus.protocol.ModbusProtocolException;
import org.opentsx.modbus.protocol.ModbusTimeoutException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Modbus over a TCP connection with MBAP framing (Modbus TCP) or RTU framing (RTU over TCP, for serial device
 * servers). JDK sockets only.
 *
 * <ul>
 *   <li>one request in flight at a time ({@code synchronized}); transaction ids count 1..65535</li>
 *   <li>MBAP: responses with an older transaction id (late answers to a request that timed out) are skipped</li>
 *   <li>timeout per response; after a timeout or any I/O error the connection is closed and the next attempt
 *   reconnects (RTU has no transaction id, so a late answer could otherwise be taken for the next one)</li>
 *   <li>{@code retries} extra attempts per request, {@code reconnectDelay} between them</li>
 * </ul>
 */
public final class TcpTransport implements ModbusTransport {

    /** Connection settings. */
    public record Settings(String host, int port, Framing.Kind framing, Duration timeout, Duration connectTimeout,
                           int retries, Duration reconnectDelay) {

        public Settings {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host must be set");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port " + port + " outside 1..65535");
            }
            framing = framing == null ? Framing.Kind.TCP : framing;
            timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
            connectTimeout = connectTimeout == null ? timeout : connectTimeout;
            reconnectDelay = reconnectDelay == null ? Duration.ofMillis(200) : reconnectDelay;
            if (retries < 0) {
                throw new IllegalArgumentException("retries must be >= 0");
            }
        }

        public static Settings of(String host, int port) {
            return new Settings(host, port, Framing.Kind.TCP, null, null, 1, null);
        }

        public Settings withFraming(Framing.Kind kind) {
            return new Settings(host, port, kind, timeout, connectTimeout, retries, reconnectDelay);
        }

        public Settings withTimeout(Duration t) {
            return new Settings(host, port, framing, t, t, retries, reconnectDelay);
        }

        public Settings withRetries(int r) {
            return new Settings(host, port, framing, timeout, connectTimeout, r, reconnectDelay);
        }
    }

    private static final int MAX_STALE_FRAMES = 16;

    private final Settings settings;
    private final Framing framing;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int nextTid = 1;
    private long requests;
    private long connects;
    private long timeouts;

    public TcpTransport(Settings settings) {
        this.settings = settings;
        this.framing = settings.framing().framing();
    }

    @Override
    public String endpoint() {
        return (settings.framing() == Framing.Kind.TCP ? "tcp://" : "rtu-over-tcp://") + settings.host() + ":" + settings.port();
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public synchronized byte[] exchange(int unitId, byte[] requestPdu) throws ModbusException {
        if (unitId < 0 || unitId > 255) {
            throw new IllegalArgumentException("unit id " + unitId + " outside 0..255");
        }
        requests++;
        ModbusException last = null;
        for (int attempt = 0; attempt <= settings.retries(); attempt++) {
            if (attempt > 0) {
                pause();
            }
            try {
                connect();
                int tid = nextTid;
                nextTid = nextTid == 0xFFFF ? 1 : nextTid + 1;
                out.write(framing.encode(new Frame(tid, unitId, requestPdu)));
                out.flush();
                return awaitResponse(tid, unitId);
            } catch (SocketTimeoutException e) {
                timeouts++;
                disconnect();
                last = new ModbusTimeoutException("no response from " + endpoint() + " unit " + unitId + " within "
                        + settings.timeout().toMillis() + " ms (attempt " + (attempt + 1) + ")", e);
            } catch (ModbusProtocolException e) {
                disconnect();
                last = e;
            } catch (IOException e) {
                disconnect();
                last = new ModbusException("connection to " + endpoint() + " failed: " + e.getMessage()
                        + " (attempt " + (attempt + 1) + ")", e);
            }
        }
        throw last;
    }

    private byte[] awaitResponse(int tid, int unitId) throws IOException {
        for (int skipped = 0; ; skipped++) {
            Frame f = framing.readResponse(in);
            if (framing.hasTransactionIds() && f.transactionId() != tid) {
                if (skipped < MAX_STALE_FRAMES && isOlder(f.transactionId(), tid)) {
                    continue;
                }
                throw new ModbusProtocolException("response transaction id " + f.transactionId() + " does not match " + tid);
            }
            if (f.unitId() != unitId) {
                throw new ModbusProtocolException("response from unit " + f.unitId() + ", expected " + unitId);
            }
            return f.pdu();
        }
    }

    /** Transaction ids wrap at 65535; "older" means within the last 32768 ids. */
    private static boolean isOlder(int candidate, int current) {
        int d = (current - candidate) & 0xFFFF;
        return d > 0 && d < 0x8000;
    }

    private void connect() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(settings.host(), settings.port()), (int) settings.connectTimeout().toMillis());
            s.setSoTimeout((int) settings.timeout().toMillis());
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        socket = s;
        in = new BufferedInputStream(s.getInputStream());
        out = s.getOutputStream();
        connects++;
    }

    private void disconnect() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        socket = null;
        in = null;
        out = null;
    }

    private void pause() {
        try {
            Thread.sleep(settings.reconnectDelay().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Requests sent, connections opened (1 + reconnects), timeouts seen. */
    public synchronized long[] counters() {
        return new long[]{requests, connects, timeouts};
    }

    @Override
    public synchronized void close() {
        disconnect();
    }
}
