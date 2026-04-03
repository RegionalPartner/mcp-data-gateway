package io.ancoris.mcp.integration;

import io.ancoris.mcp.model.ApiKey;
import io.ancoris.mcp.security.ApiKeyRepository;
import io.ancoris.mcp.security.HmacApiKeyHasher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class TestSecurityHelper {

    private final HmacApiKeyHasher hasher;

    public TestSecurityHelper(HmacApiKeyHasher hasher) {
        this.hasher = hasher;
    }

    /**
     * Finds the ApiKey whose HMAC-SHA256 hash matches rawKey and sets it as the
     * current authentication on the SecurityContextHolder.
     */
    public void authenticateAs(String rawKey, ApiKeyRepository apiKeyRepository) {
        ApiKey matched = apiKeyRepository.findAll().stream()
                .filter(key -> hasher.matches(rawKey, key.getKeyHash()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No API key found matching raw key: " + rawKey));

        var authority = new SimpleGrantedAuthority("ROLE_" + matched.getRole().name());
        var auth = new UsernamePasswordAuthenticationToken(matched, null, List.of(authority));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Clears the current authentication from the SecurityContextHolder.
     */
    public void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }
}
