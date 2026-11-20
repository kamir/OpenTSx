package org.opentsx.wayang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignalLinkTest {

    private static Socket connect(RustOfflineCatchupIntegrationTest.SignalLink link) throws IOException {
        String bootstrap = link.bootstrap();
        return new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(bootstrap.substring(bootstrap.lastIndexOf(':') + 1)));
    }

    @Test
    void runnerResetEndsTheLinkWithoutFailure() throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var link = new RustOfflineCatchupIntegrationTest.SignalLink("127.0.0.1:" + backend.getLocalPort())) {
            link.online = true;
            try (Socket runner = connect(link); Socket observer = backend.accept()) {
                runner.getOutputStream().write(7);
                assertEquals(7, observer.getInputStream().read());
                runner.setSoLinger(true, 0);
                runner.close();
                assertEquals(-1, observer.getInputStream().read());
            }
            link.check();
        }
    }

    @Test
    void responsesRelayedToAClosedRunnerDoNotFailTheLink() throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var link = new RustOfflineCatchupIntegrationTest.SignalLink("127.0.0.1:" + backend.getLocalPort())) {
            link.online = true;
            try (Socket runner = connect(link); Socket observer = backend.accept()) {
                runner.getOutputStream().write(7);
                assertEquals(7, observer.getInputStream().read());
                runner.close();
                OutputStream out = observer.getOutputStream();
                assertThrows(SocketException.class, () -> { while (true) out.write(new byte[1024]); });
            }
            link.check();
        }
    }

    @Test
    void observerResetWhileTheRunnerIsConnectedFailsTheLink() throws Exception {
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var link = new RustOfflineCatchupIntegrationTest.SignalLink("127.0.0.1:" + backend.getLocalPort())) {
            link.online = true;
            try (Socket runner = connect(link); Socket observer = backend.accept()) {
                observer.setSoLinger(true, 0);
                observer.close();
                assertEquals(-1, runner.getInputStream().read());
            }
            IllegalStateException failure = assertThrows(IllegalStateException.class, link::check);
            assertEquals("B3 signal link failed", failure.getMessage());
            assertInstanceOf(SocketException.class, failure.getCause());
        }
    }
}
