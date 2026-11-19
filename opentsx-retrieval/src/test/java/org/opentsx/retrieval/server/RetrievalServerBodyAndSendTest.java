package org.opentsx.retrieval.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalServerBodyAndSendTest {
    private static final String BODY_ERROR = "Request body must be a single JSON object";

    @Test
    void readsANormalObjectWithoutSendingOrClosingTheExchange() throws Throwable {
        FakeHttpExchange exchange = new FakeHttpExchange("{\"value\":2,\"enabled\":true,\"nested\":{\"name\":\"sample\"}}");
        JsonNode result = body(exchange);
        assertTrue(result.isObject());
        assertEquals(2, result.path("value").intValue());
        assertTrue(result.path("enabled").booleanValue());
        assertEquals("sample", result.path("nested").path("name").textValue());
        assertExchangeHasNoResponse(exchange);
    }

    @Test
    void acceptsAnEmptyObject() throws Throwable {
        FakeHttpExchange exchange = new FakeHttpExchange("{}");
        JsonNode result = body(exchange);
        assertTrue(result.isObject());
        assertEquals(0, result.size());
        assertExchangeHasNoResponse(exchange);
    }

    @Test
    void acceptsWhitespaceAroundOneObject() throws Throwable {
        FakeHttpExchange exchange = new FakeHttpExchange(" \t\r\n {\"pattern\":[2,4]} \n\t");
        JsonNode result = body(exchange);
        assertTrue(result.isObject());
        assertEquals(2, result.path("pattern").size());
        assertEquals(2, result.path("pattern").get(0).intValue());
        assertEquals(4, result.path("pattern").get(1).intValue());
        assertExchangeHasNoResponse(exchange);
    }

    @Test
    void decodesUtf8ObjectValues() throws Throwable {
        FakeHttpExchange exchange = new FakeHttpExchange("{\"name\":\"\u00e9\u6f22\ud83d\ude80\"}");
        assertEquals("\u00e9\u6f22\ud83d\ude80", body(exchange).path("name").textValue());
        assertExchangeHasNoResponse(exchange);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\r\n", "null", "[]", "[1,2]", "true", "17", "\"synthetic-private-marker\""})
    void rejectsMissingAndNonObjectBodiesWithAFixedMessage(String text) {
        assertBadBody(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"value\":", "{\"value\":\"unfinished", "{\"synthetic-private-marker\":?}"})
    void malformedJsonKeepsTheParsingCauseWithoutExposingInput(String text) {
        IllegalArgumentException failure = assertBadBody(text);
        assertInstanceOf(JsonProcessingException.class, failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{} {}", "{} []", "{} true", "{} synthetic-private-marker"})
    void rejectsTrailingTokensAndKeepsTheParsingCause(String text) {
        IllegalArgumentException failure = assertBadBody(text);
        assertInstanceOf(JsonProcessingException.class, failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"value\":"})
    void requestReadFailuresRemainTheOriginalTransportException(String prefix) {
        IOException transport = new IOException("Synthetic request read failure");
        byte[] bytes = prefix.getBytes(StandardCharsets.UTF_8);
        InputStream input = new InputStream() {
            private int position;

            @Override
            public int read() throws IOException {
                if (position < bytes.length) return bytes[position++] & 0xff;
                throw transport;
            }
        };
        FakeHttpExchange exchange = new FakeHttpExchange(input);
        assertSame(transport, assertThrows(IOException.class, () -> body(exchange)));
        assertExchangeHasNoResponse(exchange);
    }

    @Test
    void requestStreamCloseFailureRemainsTheOriginalTransportException() {
        IOException transport = new IOException("Synthetic request close failure");
        InputStream input = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                throw transport;
            }
        };
        FakeHttpExchange exchange = new FakeHttpExchange(input);
        assertSame(transport, assertThrows(IOException.class, () -> body(exchange)));
        assertExchangeHasNoResponse(exchange);
    }

    @Test
    void sendsExactBinaryBytesHeadersAndLengthThenClosesOnce() throws Throwable {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        byte[] bytes = {0, 1, 2, (byte) 0xff, 0, 9};
        send(exchange, 200, RetrievalServer.AVRO_STREAM, bytes);
        assertResponse(exchange, 200, RetrievalServer.AVRO_STREAM, bytes);
    }

    @Test
    void sendsUtf8JsonWithItsByteLengthThenClosesOnce() throws Throwable {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        byte[] bytes = "{\"name\":\"\u00e9\u6f22\"}".getBytes(StandardCharsets.UTF_8);
        send(exchange, 400, "application/json", bytes);
        assertResponse(exchange, 400, "application/json", bytes);
    }

    @Test
    void preservesAnEmptyResponseAndItsLength() throws Throwable {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        send(exchange, 200, "application/json", new byte[0]);
        assertResponse(exchange, 200, "application/json", new byte[0]);
    }

    @Test
    void headerFailureClosesOnceAndKeepsTheOriginalException() {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        IOException failure = new IOException("Synthetic response header failure");
        exchange.headerFailure = failure;
        assertSame(failure, assertThrows(IOException.class,
                () -> send(exchange, 200, "application/octet-stream", new byte[]{1, 2, 3})));
        assertEquals(1, exchange.closeCalls);
        assertEquals(1, exchange.headerCalls);
        assertEquals(0, exchange.responseBodyCalls);
        assertEquals(0, exchange.written.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void bodyWriteFailureClosesOnceAndKeepsTheWrittenPrefix(int prefixLength) {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        IOException failure = new IOException("Synthetic response body failure");
        byte[] bytes = {1, 2, 3, 4, 5, 6};
        exchange.writeFailure = failure;
        exchange.failAfter = prefixLength;
        assertSame(failure, assertThrows(IOException.class,
                () -> send(exchange, 200, "application/octet-stream", bytes)));
        assertEquals(1, exchange.closeCalls);
        assertEquals(1, exchange.headerCalls);
        assertEquals(1, exchange.responseBodyCalls);
        assertEquals(bytes.length, exchange.responseLength);
        assertArrayEquals(Arrays.copyOf(bytes, prefixLength), exchange.written.toByteArray());
    }

    @Test
    void responseStreamAccessFailureClosesOnceWithoutReplacingTheFailure() {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        RuntimeException failure = new IllegalStateException("Synthetic response stream failure");
        exchange.responseAccessFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> send(exchange, 200, "application/json", new byte[]{1})));
        assertEquals(1, exchange.closeCalls);
        assertEquals(0, exchange.written.size());
    }

    @Test
    void anExchangeCloseFailureRemainsVisibleAfterASuccessfulWrite() {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        RuntimeException failure = new IllegalStateException("Synthetic exchange close failure");
        exchange.closeFailure = failure;
        byte[] bytes = {7, 8, 9};
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> send(exchange, 200, "application/octet-stream", bytes)));
        assertEquals(1, exchange.closeCalls);
        assertArrayEquals(bytes, exchange.written.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"headers", "body"})
    void responseIoFailureKeepsACloseFailureSuppressed(String point) {
        FakeHttpExchange exchange = new FakeHttpExchange("");
        IOException primary = new IOException("Synthetic primary response failure");
        RuntimeException cleanup = new IllegalStateException("Synthetic exchange cleanup failure");
        if (point.equals("headers")) {
            exchange.headerFailure = primary;
        } else {
            exchange.writeFailure = primary;
            exchange.failAfter = 2;
        }
        exchange.closeFailure = cleanup;
        IOException actual = assertThrows(IOException.class,
                () -> send(exchange, 200, "application/octet-stream", new byte[]{1, 2, 3, 4}));
        assertSame(primary, actual);
        assertArrayEquals(new Throwable[]{cleanup}, actual.getSuppressed());
        assertEquals(1, exchange.closeCalls);
    }

    private static IllegalArgumentException assertBadBody(String text) {
        FakeHttpExchange exchange = new FakeHttpExchange(text);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> body(exchange));
        assertEquals(BODY_ERROR, failure.getMessage());
        assertFalse(failure.getMessage().contains("synthetic-private-marker"));
        assertExchangeHasNoResponse(exchange);
        return failure;
    }

    private static void assertExchangeHasNoResponse(FakeHttpExchange exchange) {
        assertEquals(0, exchange.closeCalls);
        assertEquals(0, exchange.headerCalls);
        assertEquals(0, exchange.responseBodyCalls);
        assertEquals(0, exchange.written.size());
        assertTrue(exchange.responseHeaders.isEmpty());
    }

    private static void assertResponse(FakeHttpExchange exchange, int status, String type, byte[] bytes) {
        assertEquals(status, exchange.responseCode);
        assertEquals(bytes.length, exchange.responseLength);
        assertEquals(List.of(type), exchange.responseHeaders.get("Content-Type"));
        assertArrayEquals(bytes, exchange.written.toByteArray());
        assertEquals(1, exchange.headerCalls);
        assertEquals(1, exchange.responseBodyCalls);
        assertEquals(1, exchange.closeCalls);
    }

    private static JsonNode body(HttpExchange exchange) throws Throwable {
        return (JsonNode) invoke("body", new Class<?>[]{HttpExchange.class}, exchange);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws Throwable {
        invoke("send", new Class<?>[]{HttpExchange.class, int.class, String.class, byte[].class},
                exchange, status, type, bytes);
    }

    private static Object invoke(String name, Class<?>[] parameters, Object... arguments) throws Throwable {
        Method method = RetrievalServer.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static final class FakeHttpExchange extends HttpExchange {
        private final InputStream input;
        final Headers responseHeaders = new Headers();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        IOException headerFailure;
        IOException writeFailure;
        RuntimeException responseAccessFailure;
        RuntimeException closeFailure;
        int failAfter;
        int closeCalls;
        int headerCalls;
        int responseBodyCalls;
        int responseCode = -1;
        long responseLength = -1;

        private final OutputStream output = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                if (writeFailure != null && written.size() >= failAfter) throw writeFailure;
                written.write(value);
            }
        };

        FakeHttpExchange(String text) {
            this(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        }

        FakeHttpExchange(InputStream input) {
            this.input = input;
        }

        @Override
        public Headers getRequestHeaders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getRequestMethod() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) throw closeFailure;
        }

        @Override
        public InputStream getRequestBody() {
            return input;
        }

        @Override
        public OutputStream getResponseBody() {
            responseBodyCalls++;
            if (responseAccessFailure != null) throw responseAccessFailure;
            return output;
        }

        @Override
        public void sendResponseHeaders(int status, long length) throws IOException {
            headerCalls++;
            responseCode = status;
            responseLength = length;
            if (headerFailure != null) throw headerFailure;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getProtocol() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getAttribute(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(String name, Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpPrincipal getPrincipal() {
            throw new UnsupportedOperationException();
        }
    }
}
