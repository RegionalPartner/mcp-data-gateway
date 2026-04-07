package io.ancoris.mcp.connector;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * AES-256-GCM content encryption for document chunks (SEC-ENC).
 *
 * Wire format: [12B IV][ciphertext + 16B GCM tag]
 * Key source:  mcp.content.key (${MCP_CONTENT_KEY}) — exactly 64 hex chars (32 bytes).
 */
@Component
public class ContentEncryptor {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int HEX_KEY_LENGTH = 64;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public ContentEncryptor(@Value("${mcp.content.key}") String hexKey) {
        if (hexKey == null || hexKey.length() != HEX_KEY_LENGTH) {
            throw new IllegalStateException(
                    "MCP_CONTENT_KEY must be exactly 64 hex characters (32 bytes). "
                    + "Generate with: openssl rand -hex 32");
        }
        byte[] keyBytes = HexFormat.of().parseHex(hexKey);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts plaintext with a fresh random IV.
     *
     * @return [12B IV][ciphertext + 16B GCM tag]
     */
    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[IV_LENGTH_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertext, 0, result, IV_LENGTH_BYTES, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts bytes produced by {@link #encrypt(String)}.
     *
     * @throws SecurityException if the GCM authentication tag is invalid (tampered data)
     */
    public String decrypt(byte[] encryptedBytes) {
        try {
            byte[] iv = Arrays.copyOfRange(encryptedBytes, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(encryptedBytes, IV_LENGTH_BYTES, encryptedBytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new SecurityException("Content authentication failed — data may be tampered", e);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
