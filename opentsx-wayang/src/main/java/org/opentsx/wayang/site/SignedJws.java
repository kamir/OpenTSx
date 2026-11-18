package org.opentsx.wayang.site;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

public final class SignedJws {
    private static final String HEADER = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"EdDSA\"}".getBytes(StandardCharsets.US_ASCII));
    public static final int MAX_COMPACT_BYTES = 176 * 1024;

    private SignedJws() { }

    public static String sign(byte[] payload, PrivateKey key) {
        SiteJson.parse(payload, SiteJson.MAX_BYTES);
        String input = HEADER + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("an Ed25519 signing key is required", error);
        }
    }

    public static byte[] verify(String compact, PublicKey key) {
        if (compact == null || compact.length() > MAX_COMPACT_BYTES) throw refused();
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals(HEADER)) throw refused();
        try {
            byte[] payload = decode(parts[1]);
            byte[] signed = decode(parts[2]);
            if (payload.length > SiteJson.MAX_BYTES || signed.length != 64) throw refused();
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(signed)) throw refused();
            return payload;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw refused();
        }
    }

    private static byte[] decode(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw refused();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw refused();
        return decoded;
    }

    private static PlanRefusal refused() { return new PlanRefusal("plan.signature", "plan signature is invalid"); }
}
