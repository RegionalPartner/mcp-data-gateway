package io.ancoris.mcp.connector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbContentStoreTest {

    private static final String TEST_KEY =
            "0101010101010101010101010101010101010101010101010101010101010101";

    @Mock
    private JdbcTemplate jdbc;

    private ContentEncryptor encryptor;
    private DbContentStore store;

    @BeforeEach
    void setUp() {
        encryptor = new ContentEncryptor(TEST_KEY);
        store = new DbContentStore(jdbc, encryptor);
    }

    // -----------------------------------------------------------------------
    // Normal case: encrypted_content present → decrypted text returned
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_encryptedContentPresent_decryptedTextReturned() {
        UUID id = UUID.randomUUID();
        String original = "Le rapport annuel 2024 présente les résultats.";
        byte[] encrypted = encryptor.encrypt(original);

        when(jdbc.queryForObject(any(String.class), eq(byte[].class), eq(id)))
                .thenReturn(encrypted);

        String result = store.fetchChunk(id);

        assertThat(result).isEqualTo(original);
    }

    // -----------------------------------------------------------------------
    // null encrypted_content (row found, column is NULL) → empty string
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_nullEncryptedContent_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(any(String.class), eq(byte[].class), eq(id)))
                .thenReturn(null);

        String result = store.fetchChunk(id);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Text > 500 chars → truncated to 500 chars
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_longText_truncatedTo500() {
        UUID id = UUID.randomUUID();
        String longText = "x".repeat(600);
        byte[] encrypted = encryptor.encrypt(longText);

        when(jdbc.queryForObject(any(String.class), eq(byte[].class), eq(id)))
                .thenReturn(encrypted);

        String result = store.fetchChunk(id);

        assertThat(result).hasSize(500);
    }

    // -----------------------------------------------------------------------
    // Text exactly 500 chars → returned unchanged
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_exactly500Chars_unchanged() {
        UUID id = UUID.randomUUID();
        String exactText = "a".repeat(500);
        byte[] encrypted = encryptor.encrypt(exactText);

        when(jdbc.queryForObject(any(String.class), eq(byte[].class), eq(id)))
                .thenReturn(encrypted);

        String result = store.fetchChunk(id);

        assertThat(result).hasSize(500);
    }

    // -----------------------------------------------------------------------
    // Tampered bytes (SecurityException) → empty string, not propagated
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_tamperedBytes_returnsEmptyNotException() {
        UUID id = UUID.randomUUID();
        byte[] encrypted = encryptor.encrypt("text");
        encrypted[15] ^= 0xFF; // corrupt GCM tag region

        when(jdbc.queryForObject(any(String.class), eq(byte[].class), eq(id)))
                .thenReturn(encrypted);

        String result = store.fetchChunk(id);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // JdbcTemplate throws → empty string, not propagated
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_jdbcException_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(any(String.class), eq(byte[].class), eq(id)))
                .thenThrow(new RuntimeException("DB unavailable"));

        String result = store.fetchChunk(id);

        assertThat(result).isEmpty();
    }
}
