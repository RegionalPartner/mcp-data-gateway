package io.ancoris.mcp.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.ancoris.mcp.model.ApiKey;
import org.springframework.stereotype.Service;
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
                .filter(key -> hasher.matches(rawKey, key.getKeyHash()))
                .findFirst();
    }

    /** Force cache reload on the next request — call after any key mutation. */
    public void invalidateCache() {
        keyCache.invalidateAll();
    }
}
