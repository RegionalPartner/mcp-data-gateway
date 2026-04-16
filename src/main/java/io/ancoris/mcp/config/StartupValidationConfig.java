package io.ancoris.mcp.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SEC-010: Enforces TLS on all backend connections at startup.
 * Disabled via mcp.security.enforce-tls=false in dev/test profiles.
 */
@Component
@ConditionalOnProperty(name = "mcp.security.enforce-tls", havingValue = "true")
public class StartupValidationConfig {

    private final String dbUrl;

    public StartupValidationConfig(@Value("${spring.datasource.url}") String dbUrl) {
        this.dbUrl = dbUrl;
    }

    @PostConstruct
    public void validateTlsConfiguration() {
        if (!dbUrl.contains("sslmode=require")) {
            throw new IllegalStateException(
                "DB_URL must contain 'sslmode=require' in production. "
                + "Add ?sslmode=require to the JDBC URL, or set "
                + "mcp.security.enforce-tls=false to suppress this check in non-prod environments.");
        }
    }
}
