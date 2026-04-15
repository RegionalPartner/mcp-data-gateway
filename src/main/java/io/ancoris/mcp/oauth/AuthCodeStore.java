package io.ancoris.mcp.oauth;

import io.ancoris.mcp.model.AccessRole;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for short-lived OAuth 2.0 authorization codes.
 *
 * <p>Codes expire after 60 seconds (RFC 6749 §4.1.2 recommends short lifetimes).
 * Expired entries are purged lazily on every {@link #issue} call.
 * Each code can be consumed exactly once — {@link #consume} removes the entry atomically.
 */
@Component
public class AuthCodeStore {

    private static final long CODE_TTL_SECONDS = 60L;
    private final Map<String, AuthCodeEntry> store = new ConcurrentHashMap<>();

    /** Issues a single-use authorization code for the given API key and PKCE parameters. */
    public String issue(String keyHash, AccessRole role,
                        String redirectUri, String codeChallenge, String codeChallengeMethod) {
        purgeExpired();
        String code = UUID.randomUUID().toString().replace("-", "");
        store.put(code, new AuthCodeEntry(keyHash, role, redirectUri,
                codeChallenge, codeChallengeMethod,
                Instant.now().plusSeconds(CODE_TTL_SECONDS)));
        return code;
    }

    /**
     * Atomically removes and returns the entry if the code exists and has not expired.
     * Returns empty on any failure (unknown code, already used, expired).
     */
    public Optional<AuthCodeEntry> consume(String code) {
        AuthCodeEntry entry = store.remove(code);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    public record AuthCodeEntry(
            String keyHash,
            AccessRole role,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            Instant expiresAt) {}
}
