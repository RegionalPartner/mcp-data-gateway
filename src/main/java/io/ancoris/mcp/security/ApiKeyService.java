package io.ancoris.mcp.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.ancoris.mcp.model.ApiKey;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class ApiKeyService {

    private final ApiKeyRepository repository;
    private final HmacApiKeyHasher hasher;
    /**
     * SEC-003: cache the full key list for 60 s to avoid O(n × HMAC) DB hits per request.
     * A single-entry cache keyed on "all" is sufficient; invalidated on key changes.
     */
    private final Cache<String, List<ApiKey>> keyCache;

    public ApiKeyService(ApiKeyRepository repository, HmacApiKeyHasher hasher) {
        this.repository = repository;
        this.hasher = hasher;
        this.keyCache = Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(1)
                .build();
    }

    public Optional<ApiKey> authenticate(String rawKey) {
        List<ApiKey> keys = keyCache.get("all", k -> repository.findAll());
        return keys.stream()
                // SEC-001: reject revoked or expired keys
                .filter(key -> !key.isRevoked())
                .filter(key -> key.getExpiresAt() == null || key.getExpiresAt().isAfter(Instant.now()))
                // D5: defence-in-depth — reject on the generated status column too.
                // revoked/expiresAt above are the primary path; status is a stored projection
                // that lets an operator invalidate a key by updating either column without
                // relying on clock-comparison correctness here.
                .filter(key -> !"REVOKED".equals(key.getStatus()) && !"EXPIRED".equals(key.getStatus()))
                .filter(key -> hasher.matches(rawKey, key.getKeyHash()))
                .findFirst();
    }

    /**
     * Resolves a stored HMAC key hash (from a Bearer JWT claim) back to an active ApiKey.
     * Used by ApiKeyFilter when authenticating via OAuth 2.0 Bearer tokens.
     */
    public Optional<ApiKey> authenticateByHash(String keyHash) {
        List<ApiKey> keys = keyCache.get("all", k -> repository.findAll());
        return keys.stream()
                // SEC-001: reject revoked or expired keys
                .filter(key -> !key.isRevoked())
                .filter(key -> key.getExpiresAt() == null || key.getExpiresAt().isAfter(Instant.now()))
                // D5: defence-in-depth — reject on the generated status column too.
                .filter(key -> !"REVOKED".equals(key.getStatus()) && !"EXPIRED".equals(key.getStatus()))
                // Constant-time comparison to avoid hash oracle timing attacks (SEC-HMAC)
                .filter(key -> MessageDigest.isEqual(
                        keyHash.getBytes(StandardCharsets.UTF_8),
                        key.getKeyHash().getBytes(StandardCharsets.UTF_8)))
                .findFirst();
    }

    /** Force cache reload on the next request — call after any key mutation. */
    public void invalidateCache() {
        keyCache.invalidateAll();
    }
}
