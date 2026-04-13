package io.ancoris.mcp.oauth;

import io.ancoris.mcp.model.AccessRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        service = new JwtTokenService("test-jwt-secret-32-chars-minimum-00");
    }

    @Test
    void issue_and_validate_roundtrip() {
        String token = service.issue("abc123hash", AccessRole.ADMIN);
        Optional<JwtTokenService.JwtClaims> claims = service.validate(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().keyHash()).isEqualTo("abc123hash");
        assertThat(claims.get().role()).isEqualTo(AccessRole.ADMIN);
    }

    @Test
    void validate_readOnlyRole_preservedRoundtrip() {
        String token = service.issue("hashXYZ", AccessRole.READ_ONLY);
        Optional<JwtTokenService.JwtClaims> claims = service.validate(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().role()).isEqualTo(AccessRole.READ_ONLY);
    }

    @Test
    void validate_tamperedSignature_returnsEmpty() {
        String token = service.issue("somehash", AccessRole.ADMIN);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsignature";

        assertThat(service.validate(tampered)).isEmpty();
    }

    @Test
    void validate_tamperedPayload_returnsEmpty() {
        String[] parts = service.issue("somehash", AccessRole.ADMIN).split("\\.");
        // Replace payload with a different base64url string
        String badToken = parts[0] + ".aGVsbG8=" + "." + parts[2];

        assertThat(service.validate(badToken)).isEmpty();
    }

    @Test
    void validate_null_returnsEmpty() {
        assertThat(service.validate(null)).isEmpty();
    }

    @Test
    void validate_blank_returnsEmpty() {
        assertThat(service.validate("  ")).isEmpty();
    }

    @Test
    void validate_malformedToken_returnsEmpty() {
        assertThat(service.validate("not.a.valid.jwt.here")).isEmpty();
        assertThat(service.validate("onlyonepart")).isEmpty();
    }

    @Test
    void validate_wrongSecret_returnsEmpty() {
        JwtTokenService other = new JwtTokenService("completely-different-secret-000000");
        String token = other.issue("somehash", AccessRole.ADMIN);

        assertThat(service.validate(token)).isEmpty();
    }
}
