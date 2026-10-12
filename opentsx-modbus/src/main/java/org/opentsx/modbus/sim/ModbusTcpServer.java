package org.opentsx.modbus.sim;

import org.opentsx.modbus.protocol.Frame;
import org.opentsx.modbus.protocol.Framing;
import org.opentsx.modbus.protocol.FunctionCode;
import org.opentsx.modbus.protocol.ModbusExceptionResponse;
import org.opentsx.modbus.protocol.Pdu;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small Modbus TCP (or RTU-over-TCP) server on JDK sockets serving a {@link RegisterImage}: functions 0x01-0x04;
 * other functions get ILLEGAL_FUNCTION. A request for another unit id gets GATEWAY_TARGET_FAILED (TCP) or no
 * answer (RTU, like a silent slave on the bus). Test hooks: {@link #responseDelay}, {@link #failNext},
 * {@link #dropConnections}.
 */
public final class ModbusTcpServer implements AutoCloseable {

    private final RegisterImage image;
    private final Framing.Kind framing;
    private final int unitId;
    private final ServerSocket server;
    private final ExecutorService pool;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicInteger failRemaining = new AtomicInteger();
    private volatile int failCode;
    private volatile Duration responseDelay = Duration.ZERO;
    private volatile boolean running = true;

    /**
     * @param port   0 = ephemeral
     * @param bind   address to bind, e.g. loopback for tests; null = all interfaces
     * @param unitId served unit id; -1 answers every unit id
     */
    public ModbusTcpServer(RegisterImage image, Framing.Kind framing, int port, InetAddress bind, int unitId) throws IOException {
        this.image = image;
        this.framing = framing;
        this.unitId = unitId;
        this.server = new ServerSocket(port, 50, bind);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "modbus-sim-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        pool.submit(this::acceptLoop);
    }

    /** Loopback, ephemeral port, MBAP, any unit id. */
    public static ModbusTcpServer local(RegisterImage image) throws IOException {
        return new ModbusTcpServer(image, Framing.Kind.TCP, 0, InetAddress.getLoopbackAddress(), -1);
    }

    public int port() {
        return server.getLocalPort();
    }

    public long requests() {
        return requests.get();
    }

    /** Delay before every response (timeout tests). */
    public void responseDelay(Duration d) {
        this.responseDelay = d;
    }

    /** Answers the next request with this exception code. */
    public void failNext(int exceptionCode) {
        failNext(exceptionCode, 1);
    }

    /** Answers the next {@code count} requests with this exception code. */
    public void failNext(int exceptionCode, int count) {
        failCode = exceptionCode;
        failRemaining.set(count);
    }

    /** Closes all client connections (reconnect tests); the server keeps listening. */
    public void dropConnections() {
        for (Socket s : clients) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        clients.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                clients.add(s);
                pool.submit(() -> serve(s));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void serve(Socket s) {
        Framing f = framing.framing();
        try (s; InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream()) {
            while (running) {
                Frame req = f.readRequest(in);
                requests.incrementAndGet();
                byte[] pdu;
                if (unitId >= 0 && req.unitId() != unitId) {
                    if (framing == Framing.Kind.RTU_OVER_TCP) {
                        continue;
                    }
                    pdu = Pdu.exceptionResponse(req.pdu()[0] & 0xFF, ModbusExceptionResponse.GATEWAY_TARGET_FAILED);
                } else {
                    pdu = handle(req.pdu());
                }
                long delay = responseDelay.toMillis();
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                out.write(f.encode(new Frame(req.transactionId(), req.unitId(), pdu)));
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // client gone, broken frame or shutdown: drop the connection
        } finally {
            clients.remove(s);
        }
    }

    byte[] handle(byte[] pdu) {
        int fc = pdu[0] & 0xFF;
        if (failRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            return Pdu.exceptionResponse(fc, failCode);
        }
        if (fc < 1 || fc > 4) {
            return Pdu.exceptionResponse(fc, ModbusExceptionResponse.ILLEGAL_FUNCTION);
        }
        try {
            Pdu.ReadRequest r = Pdu.parseReadRequest(pdu);
            FunctionCode function = FunctionCode.ofCode(fc);
            if (function.bits()) {
                return Pdu.bitsResponse(function, image.readBits(function, r.address(), r.quantity()));
            }
            return Pdu.registersResponse(function, image.readRegisters(function, r.address(), r.quantity()));
        } catch (ModbusExceptionResponse e) {
            return Pdu.exceptionResponse(fc, e.exceptionCode());
        } catch (IOException | RuntimeException e) {
            return Pdu.exceptionResponse(fc, ModbusExceptionResponse.ILLEGAL_DATA_VALUE);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        dropConnections();
        pool.shutdownNow();
    }
}
