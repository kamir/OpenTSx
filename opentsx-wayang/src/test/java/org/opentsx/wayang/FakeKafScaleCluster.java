package org.opentsx.wayang;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;

public final class FakeKafScaleCluster {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[1]);
        switch (args[0]) {
            case "ready" -> {
                ServerSocket proxy = new ServerSocket(port, 50, LOOPBACK);
                print("time=2026-11-21T00:53:20.197Z level=INFO msg=\"proxy listening\" addr=127.0.0.1:" + port);
                ServerSocket broker = new ServerSocket(0, 50, LOOPBACK);
                print("2026/11/21 00:53:20 broker listening on 127.0.0.1:" + broker.getLocalPort());
                while (true) proxy.accept().close();
            }
            case "no-broker" -> {
                ServerSocket proxy = new ServerSocket(port, 50, LOOPBACK);
                print("time=2026-11-21T00:53:20.197Z level=INFO msg=\"proxy listening\" addr=127.0.0.1:" + port);
                while (true) proxy.accept().close();
            }
            case "foreign-proxy" -> {
                ServerSocket broker = new ServerSocket(0, 50, LOOPBACK);
                print("2026/11/21 00:53:20 broker listening on 127.0.0.1:" + broker.getLocalPort());
                while (true) broker.accept().close();
            }
            case "proxy-taken" -> {
                ServerSocket broker = new ServerSocket(0, 50, LOOPBACK);
                print("2026/11/21 00:53:20 broker listening on 127.0.0.1:" + broker.getLocalPort());
                lose(port, "time=2026-11-21T00:53:20.853Z level=ERROR msg=\"proxy server error\" error=\"listen tcp 127.0.0.1:"
                        + port + ": bind: address already in use\"");
                print("KafScale child exited: exit status 1");
                System.exit(1);
            }
            case "broker-taken" -> {
                int taken = Integer.parseInt(args[2]);
                lose(taken, "{\"level\":\"ERROR\",\"msg\":\"broker server error\",\"component\":\"broker\",\"error\":\"listen tcp 127.0.0.1:"
                        + taken + ": bind: address already in use\"}");
                ServerSocket proxy = new ServerSocket(port, 50, LOOPBACK);
                print("time=2026-11-20T23:48:41.957Z level=INFO msg=\"proxy listening\" addr=127.0.0.1:" + proxy.getLocalPort());
                print("KafScale child exited: exit status 1");
                System.exit(1);
            }
            case "exit" -> {
                print("isolated etcd startup exceeded 30 seconds");
                System.exit(1);
            }
            default -> throw new IllegalArgumentException("unknown fake cluster mode " + args[0]);
        }
    }

    private static void lose(int port, String line) throws IOException {
        try (ServerSocket ignored = new ServerSocket(port, 50, LOOPBACK)) {
            print("fake cluster bound 127.0.0.1:" + port + " although another process holds it");
            System.exit(2);
        } catch (BindException expected) {
            print(line);
        }
    }

    private static void print(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
