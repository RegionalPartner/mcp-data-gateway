package io.ancoris.mcp.oauth;

import io.ancoris.mcp.model.AccessRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCodeStoreTest {

    private AuthCodeStore store;

    @BeforeEach
    void setUp() {
        store = new AuthCodeStore();
    }

    @Test
    void issue_and_consume_roundtrip() {
        String code = store.issue("hash1", AccessRole.ADMIN,
                "https://example.com/cb", "challenge", "S256");
        Optional<AuthCodeStore.AuthCodeEntry> entry = store.consume(code);

        assertThat(entry).isPresent();
        assertThat(entry.get().keyHash()).isEqualTo("hash1");
        assertThat(entry.get().role()).isEqualTo(AccessRole.ADMIN);
        assertThat(entry.get().redirectUri()).isEqualTo("https://example.com/cb");
        assertThat(entry.get().codeChallenge()).isEqualTo("challenge");
    }

    @Test
    void consume_unknownCode_returnsEmpty() {
        assertThat(store.consume("nonexistent-code")).isEmpty();
    }

    @Test
    void consume_sameCodeTwice_secondCallReturnsEmpty() {
        String code = store.issue("hash2", AccessRole.READ_ONLY,
                "https://example.com/cb", "c2", "S256");
        store.consume(code);
        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void issue_returnsDifferentCodesEachTime() {
        String code1 = store.issue("h", AccessRole.READ_ONLY, "https://x.com", "c", "S256");
        String code2 = store.issue("h", AccessRole.READ_ONLY, "https://x.com", "c", "S256");
        assertThat(code1).isNotEqualTo(code2);
    }
}
