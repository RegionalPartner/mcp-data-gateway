package io.ancoris.mcp.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentEncryptorTest {

    // 64 hex chars = 32 bytes (AES-256 key)
    private static final String TEST_KEY =
            "0101010101010101010101010101010101010101010101010101010101010101";

    private final ContentEncryptor encryptor = new ContentEncryptor(TEST_KEY);

    // -----------------------------------------------------------------------
    // Round-trip: decrypt(encrypt(text)) == text
    // -----------------------------------------------------------------------

    @Test
    void roundTrip_decryptedEqualsOriginal() {
        String original = "Le bilan énergétique des datacenters normands.";

        byte[] encrypted = encryptor.encrypt(original);
        String decrypted = encryptor.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(original);
    }

    // -----------------------------------------------------------------------
    // Random IV: two encryptions of the same plaintext produce different bytes
    // -----------------------------------------------------------------------

    @Test
    void encrypt_randomIv_producedDifferentCiphertexts() {
        String plaintext = "same text";

        byte[] first = encryptor.encrypt(plaintext);
        byte[] second = encryptor.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
    }

    // -----------------------------------------------------------------------
    // Tampered bytes → SecurityException (GCM tag mismatch)
    // -----------------------------------------------------------------------

    @Test
    void decrypt_tamperedCiphertext_throwsSecurityException() {
        byte[] encrypted = encryptor.encrypt("sensitive");
        // Flip one byte in the ciphertext portion (after the 12-byte IV)
        encrypted[15] ^= 0xFF;

        assertThatThrownBy(() -> encryptor.decrypt(encrypted))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("authentication failed");
    }

    // -----------------------------------------------------------------------
    // Wrong key → SecurityException (GCM tag mismatch)
    // -----------------------------------------------------------------------

    @Test
    void decrypt_wrongKey_throwsSecurityException() {
        // 64 valid hex chars, different from TEST_KEY (0101...)
        String otherKey = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        ContentEncryptor otherEncryptor = new ContentEncryptor(otherKey);

        byte[] encrypted = encryptor.encrypt("data encrypted with TEST_KEY");

        assertThatThrownBy(() -> otherEncryptor.decrypt(encrypted))
                .isInstanceOf(SecurityException.class);
    }

    // -----------------------------------------------------------------------
    // Short key (< 64 hex chars) → IllegalStateException at construction
    // -----------------------------------------------------------------------

    @Test
    void constructor_shortKey_throwsIllegalStateException() {
        assertThatThrownBy(() -> new ContentEncryptor("deadbeef"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 hex characters");
    }
}
