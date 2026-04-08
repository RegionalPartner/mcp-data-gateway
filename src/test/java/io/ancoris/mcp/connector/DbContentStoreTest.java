package io.ancoris.mcp.connector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
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

        when(jdbc.queryForMap(any(String.class), eq(id)))
                .thenReturn(Map.of("encrypted_content", encrypted, "text_preview", "preview"));

        assertThat(store.fetchChunk(id)).isEqualTo(original);
    }

    // -----------------------------------------------------------------------
    // null encrypted_content → falls back to text_preview
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_nullEncryptedContent_returnsTextPreview() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("encrypted_content", null);
        row.put("text_preview", "Aperçu du document.");
        when(jdbc.queryForMap(any(String.class), eq(id))).thenReturn(row);

        assertThat(store.fetchChunk(id)).isEqualTo("Aperçu du document.");
    }

    // -----------------------------------------------------------------------
    // null encrypted_content + null text_preview → empty string
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_bothNull_returnsEmpty() {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("encrypted_content", null);
        row.put("text_preview", null);
        when(jdbc.queryForMap(any(String.class), eq(id))).thenReturn(row);

        assertThat(store.fetchChunk(id)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Text > 500 chars → truncated to 500 chars
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_longText_truncatedTo500() {
        UUID id = UUID.randomUUID();
        String longText = "x".repeat(600);
        byte[] encrypted = encryptor.encrypt(longText);

        when(jdbc.queryForMap(any(String.class), eq(id)))
                .thenReturn(Map.of("encrypted_content", encrypted, "text_preview", "preview"));

        assertThat(store.fetchChunk(id)).hasSize(500);
    }

    // -----------------------------------------------------------------------
    // Text exactly 500 chars → returned unchanged
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_exactly500Chars_unchanged() {
        UUID id = UUID.randomUUID();
        String exactText = "a".repeat(500);
        byte[] encrypted = encryptor.encrypt(exactText);

        when(jdbc.queryForMap(any(String.class), eq(id)))
                .thenReturn(Map.of("encrypted_content", encrypted, "text_preview", "preview"));

        assertThat(store.fetchChunk(id)).hasSize(500);
    }

    // -----------------------------------------------------------------------
    // Tampered bytes (SecurityException) → empty string, not propagated
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_tamperedBytes_returnsEmptyNotException() {
        UUID id = UUID.randomUUID();
        byte[] encrypted = encryptor.encrypt("text");
        encrypted[15] ^= 0xFF; // corrupt GCM tag region

        when(jdbc.queryForMap(any(String.class), eq(id)))
                .thenReturn(Map.of("encrypted_content", encrypted, "text_preview", "preview"));

        assertThat(store.fetchChunk(id)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // JdbcTemplate throws → empty string, not propagated
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_jdbcException_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForMap(any(String.class), eq(id)))
                .thenThrow(new RuntimeException("DB unavailable"));

        assertThat(store.fetchChunk(id)).isEmpty();
    }
}
