package io.ancoris.mcp.oauth;

import io.ancoris.mcp.model.AccessRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates HS256-signed JWTs for the OAuth 2.0 Bearer token flow.
 *
 * <p>JWT format: standard three-part (header.payload.signature), all Base64url-encoded.
 * Payload claims: {@code sub} (keyHash), {@code role}, {@code iat}, {@code exp}, {@code jti}.
 *
 * <p>Signing: HmacSHA256 keyed with {@code mcp.oauth.jwt-secret}. The secret is never
 * logged or included in responses.
 */
@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final long TOKEN_TTL_SECONDS = 3600L;
    private static final String HEADER_B64 =
            Base64.getUrlEncoder().withoutPadding()
                  .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;

    public JwtTokenService(@Value("${mcp.oauth.jwt-secret}") String jwtSecret) {
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Derives a deterministic client_secret for a given client_id using HMAC-SHA256.
     *
     * <p>This is stateless — no storage required. The secret survives pod restarts because
     * it is derived from the stable {@code mcp.oauth.jwt-secret}. Confidential OAuth clients
     * (e.g. Mistral Le Chat) receive this value at DCR time and present it back at the token
     * endpoint; we re-derive and compare rather than looking up a stored value.
     */
    public String deriveClientSecret(String clientId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacSha256(("client-secret:" + clientId)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    /** Issues a 1-hour JWT for the given key hash and role. */
    public String issue(String keyHash, AccessRole role) {
        long now = Instant.now().getEpochSecond();
        String payloadJson = "{\"sub\":\"" + keyHash + "\","
                + "\"role\":\"" + role.name() + "\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + TOKEN_TTL_SECONDS) + ","
                + "\"jti\":\"" + UUID.randomUUID() + "\"}";
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signing = HEADER_B64 + "." + payload;
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmacSha256(signing.getBytes(StandardCharsets.UTF_8)));
        return signing + "." + sig;
    }

    /**
     * Validates a JWT. Returns claims if the signature is correct and the token has not expired.
     * Returns empty on any validation failure (bad format, wrong signature, expired).
     */
    public Optional<JwtClaims> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            byte[] expectedSig = hmacSha256(
                    (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] actualSig = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                return Optional.empty();
            }
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long exp = extractLong(payloadJson, "exp");
            if (Instant.now().getEpochSecond() > exp) {
                return Optional.empty();
            }
            String keyHash = extractString(payloadJson, "sub");
            AccessRole role = AccessRole.valueOf(extractString(payloadJson, "role"));
            return Optional.of(new JwtClaims(keyHash, role));
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Extracts a quoted string field from a flat, unescaped JSON object.
     * Only safe because we generate the payload ourselves (no nested objects, no escaping).
     */
    private String extractString(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle) + needle.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    /** Extracts a numeric field from a flat JSON object. */
    private long extractLong(String json, String field) {
        String needle = "\"" + field + "\":";
        int start = json.indexOf(needle) + needle.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    public record JwtClaims(String keyHash, AccessRole role) {}
}
