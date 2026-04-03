package io.ancoris.mcp.security;

import io.ancoris.mcp.model.AccessRole;
import io.ancoris.mcp.model.ApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository repository;

    @Mock
    private BCryptPasswordEncoder encoder;

    private ApiKeyService service;

    // Raw key used across tests — encoder matching is stubbed
    private static final String RAW_KEY = "test-raw-key";

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(repository, encoder);
    }

    // -----------------------------------------------------------------------
    // Valid, non-revoked, non-expired key — must authenticate
    // -----------------------------------------------------------------------

    @Test
    void authenticate_validKey_returnsKey() {
        ApiKey key = buildKey("hash-ok", AccessRole.READ_ONLY, false, null);
        when(repository.findAll()).thenReturn(List.of(key));
        when(encoder.matches(RAW_KEY, "hash-ok")).thenReturn(true);

        Optional<ApiKey> result = service.authenticate(RAW_KEY);

        assertThat(result).contains(key);
    }

    // -----------------------------------------------------------------------
    // Revoked key — must be rejected even if BCrypt matches
    // -----------------------------------------------------------------------

    @Test
    void authenticate_revokedKey_returnsEmpty() {
        ApiKey key = buildKey("hash-revoked", AccessRole.READ_ONLY, true, null);
        when(repository.findAll()).thenReturn(List.of(key));

        Optional<ApiKey> result = service.authenticate(RAW_KEY);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Expired key (expiresAt in the past) — must be rejected
    // -----------------------------------------------------------------------

    @Test
    void authenticate_expiredKey_returnsEmpty() {
        ApiKey key = buildKey("hash-expired", AccessRole.ADMIN, false, Instant.now().minusSeconds(3600));
        when(repository.findAll()).thenReturn(List.of(key));

        Optional<ApiKey> result = service.authenticate(RAW_KEY);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Key with future expiry — must authenticate
    // -----------------------------------------------------------------------

    @Test
    void authenticate_futureExpiry_returnsKey() {
        ApiKey key = buildKey("hash-future", AccessRole.ADMIN, false, Instant.now().plusSeconds(3600));
        when(repository.findAll()).thenReturn(List.of(key));
        when(encoder.matches(RAW_KEY, "hash-future")).thenReturn(true);

        Optional<ApiKey> result = service.authenticate(RAW_KEY);

        assertThat(result).contains(key);
    }

    // -----------------------------------------------------------------------
    // Cache: second authenticate() call must NOT hit the repository again
    // -----------------------------------------------------------------------

    @Test
    void authenticate_cachesKeyList_noRepeatDbCall() {
        ApiKey key = buildKey("hash-cache", AccessRole.READ_ONLY, false, null);
        when(repository.findAll()).thenReturn(List.of(key));
        when(encoder.matches(RAW_KEY, "hash-cache")).thenReturn(true);

        service.authenticate(RAW_KEY);
        service.authenticate(RAW_KEY);

        verify(repository, times(1)).findAll();
    }

    // -----------------------------------------------------------------------
    // invalidateCache() must force a fresh DB load on the next call
    // -----------------------------------------------------------------------

    @Test
    void invalidateCache_forcesReload() {
        ApiKey key = buildKey("hash-reload", AccessRole.READ_ONLY, false, null);
        when(repository.findAll()).thenReturn(List.of(key));
        when(encoder.matches(RAW_KEY, "hash-reload")).thenReturn(true);

        service.authenticate(RAW_KEY);
        service.invalidateCache();
        service.authenticate(RAW_KEY);

        verify(repository, times(2)).findAll();
    }

    // -----------------------------------------------------------------------
    // Non-matching key — BCrypt check fails, must return empty
    // -----------------------------------------------------------------------

    @Test
    void authenticate_wrongKey_returnsEmpty() {
        ApiKey key = buildKey("hash-wrong", AccessRole.READ_ONLY, false, null);
        when(repository.findAll()).thenReturn(List.of(key));
        when(encoder.matches(RAW_KEY, "hash-wrong")).thenReturn(false);

        Optional<ApiKey> result = service.authenticate(RAW_KEY);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helper: construct an ApiKey without JPA lifecycle via ReflectionTestUtils
    // -----------------------------------------------------------------------

    private static ApiKey buildKey(String keyHash, AccessRole role, boolean revoked, Instant expiresAt) {
        ApiKey key = new ApiKey();
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(key, "keyHash", keyHash);
        ReflectionTestUtils.setField(key, "label", "test-key");
        ReflectionTestUtils.setField(key, "role", role);
        ReflectionTestUtils.setField(key, "revoked", revoked);
        ReflectionTestUtils.setField(key, "expiresAt", expiresAt);
        ReflectionTestUtils.setField(key, "createdAt", Instant.now());
        return key;
    }
}
