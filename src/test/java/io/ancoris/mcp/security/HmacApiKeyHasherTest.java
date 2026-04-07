package io.ancoris.mcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacApiKeyHasherTest {

    private static final String VALID_PEPPER = "test-pepper-32-chars-minimum-000";

    private final HmacApiKeyHasher hasher = new HmacApiKeyHasher(VALID_PEPPER);

    // -----------------------------------------------------------------------
    // hash() produces exactly 64 lowercase hex characters
    // -----------------------------------------------------------------------

    @Test
    void hash_producesValid64HexChars() {
        String result = hasher.hash("any-key");

        assertThat(result).hasSize(64);
        assertThat(result).containsPattern("[0-9a-f]{64}");
    }

    // -----------------------------------------------------------------------
    // matches() round-trip: hash then verify
    // -----------------------------------------------------------------------

    @Test
    void matches_correctKey_returnsTrue() {
        String rawKey = "demo-readonly-key-001";
        String stored = hasher.hash(rawKey);

        assertThat(hasher.matches(rawKey, stored)).isTrue();
    }

    // -----------------------------------------------------------------------
    // Different raw key must not match a given stored hash
    // -----------------------------------------------------------------------

    @Test
    void matches_wrongKey_returnsFalse() {
        String stored = hasher.hash("demo-readonly-key-001");

        assertThat(hasher.matches("completely-different-key", stored)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Same plaintext hashed twice produces the same result (no random salt)
    // -----------------------------------------------------------------------

    @Test
    void hash_samePepperSameInput_isDeterministic() {
        String h1 = hasher.hash("stable-key");
        String h2 = hasher.hash("stable-key");

        assertThat(h1).isEqualTo(h2);
    }

    // -----------------------------------------------------------------------
    // Different peppers produce different hashes for the same raw key
    // -----------------------------------------------------------------------

    @Test
    void hash_differentPepper_producesDifferentHashes() {
        HmacApiKeyHasher other = new HmacApiKeyHasher("other-pepper-32-chars-minimum-00");
        String h1 = hasher.hash("same-key");
        String h2 = other.hash("same-key");

        assertThat(h1).isNotEqualTo(h2);
    }

    // -----------------------------------------------------------------------
    // matches() returns false for null stored hash
    // -----------------------------------------------------------------------

    @Test
    void matches_nullStoredHash_returnsFalse() {
        assertThat(hasher.matches("any-key", null)).isFalse();
    }

    // -----------------------------------------------------------------------
    // matches() returns false for stored hash that is not 64 characters
    // -----------------------------------------------------------------------

    @Test
    void matches_wrongLengthHash_returnsFalse() {
        assertThat(hasher.matches("any-key", "tooshort")).isFalse();
        assertThat(hasher.matches("any-key", "a".repeat(65))).isFalse();
    }

    // -----------------------------------------------------------------------
    // Constructor rejects a pepper shorter than 32 characters
    // -----------------------------------------------------------------------

    @Test
    void constructor_pepperTooShort_throwsIllegalState() {
        assertThatThrownBy(() -> new HmacApiKeyHasher("short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    // -----------------------------------------------------------------------
    // Constructor rejects a null pepper
    // -----------------------------------------------------------------------

    @Test
    void constructor_nullPepper_throwsIllegalState() {
        assertThatThrownBy(() -> new HmacApiKeyHasher(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
