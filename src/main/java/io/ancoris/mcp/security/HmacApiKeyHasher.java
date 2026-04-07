package io.ancoris.mcp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * SEC-HMAC: API key hashing using HMAC-SHA256 with a server-side pepper.
 *
 * <p>API keys are high-entropy random strings (20+ chars), not user-chosen passwords.
 * BCrypt's cost factor compensates for low-entropy inputs — it adds only latency here.
 * HMAC-SHA256 with a 32-byte+ out-of-band pepper achieves equivalent protection
 * in microseconds. See NIST FIPS 198-1.
 *
 * <p>Constant-time comparison via {@link MessageDigest#isEqual} prevents timing attacks
 * that could distinguish a hash mismatch from a match by CPU time.
 */
@Component
public final class HmacApiKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_PEPPER_LENGTH = 32;

    private final byte[] pepper;

    public HmacApiKeyHasher(@Value("${mcp.hmac.pepper}") String pepperStr) {
        if (pepperStr == null || pepperStr.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException(
                    "mcp.hmac.pepper (MCP_HMAC_PEPPER) must be at least "
                            + MIN_PEPPER_LENGTH + " characters");
        }
        this.pepper = pepperStr.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the lowercase hex HMAC-SHA256 of {@code rawKey} combined with the pepper.
     * Output is always exactly 64 hex characters (32 bytes).
     */
    public String hash(String rawKey) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(pepper, ALGORITHM));
            byte[] digest = mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    /**
     * Constant-time equality check between the HMAC of {@code rawKey} and {@code storedHash}.
     * Returns {@code false} immediately if {@code storedHash} is null or not 64 characters,
     * without performing a hash computation (safe to short-circuit on length).
     */
    public boolean matches(String rawKey, String storedHash) {
        if (storedHash == null || storedHash.length() != 64) {
            return false;
        }
        String computed = hash(rawKey);
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
