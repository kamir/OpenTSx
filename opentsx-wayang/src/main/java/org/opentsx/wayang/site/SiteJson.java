package org.opentsx.wayang.site;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

final class SiteJson {
    static final int MAX_BYTES = 128 * 1024;
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                            .maxStringLength(16 * 1024 * 1024).maxNameLength(256).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private SiteJson() { }

    static JsonNode parse(byte[] bytes, int maximum) {
        if (bytes == null || bytes.length > maximum) throw invalid("document exceeds its byte limit");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode value = JSON.readTree(text);
            if (value == null || !value.isObject()) throw invalid("document must be an object");
            scalars(value);
            return value;
        } catch (java.io.IOException error) {
            throw invalid("document is not strict UTF-8 JSON");
        }
    }

    private static void scalars(JsonNode value) {
        if (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) throw invalid("non-finite JSON number");
        if (value.isTextual()) {
            String text = value.textValue();
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                if (Character.isHighSurrogate(ch)) {
                    if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) throw invalid("invalid Unicode");
                } else if (Character.isLowSurrogate(ch)) throw invalid("invalid Unicode");
            }
        }
        if (value.isContainerNode()) value.elements().forEachRemaining(SiteJson::scalars);
    }

    static byte[] bytes(JsonNode value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (java.io.IOException error) { throw new IllegalStateException("cannot encode site state", error); }
    }

    static byte[] read(Path path, int maximum) throws java.io.IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw invalid("retained state exceeds its byte limit");
            return bytes;
        }
    }

    static void fields(JsonNode value, String... names) {
        if (value == null || !value.isObject() || value.size() != names.length) throw invalid("unknown or missing document fields");
        for (String name : names) if (!value.has(name)) throw invalid("missing field " + name);
    }

    static String text(JsonNode value, String name) {
        JsonNode field = value.get(name);
        if (field == null || !field.isTextual() || field.textValue().isEmpty()) throw invalid("invalid field " + name);
        return field.textValue();
    }

    static String id(JsonNode value, String name) {
        String result = text(value, name);
        if (!result.matches("[a-z0-9][a-z0-9-]{0,62}")) throw invalid("invalid identity " + name);
        return result;
    }

    static long decimal(String value, boolean positive) {
        if (value == null || !value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*")
                || value.length() > 19 || value.length() == 19 && value.compareTo("9223372036854775807") > 0) {
            throw invalid("invalid decimal identity");
        }
        return Long.parseLong(value);
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    static PlanRefusal invalid(String message) { return new PlanRefusal("plan.invalid", message); }
}
