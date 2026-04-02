package io.ancoris.mcp.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * SEC-010: Enforces TLS on all backend connections at startup.
 * Disabled via mcp.security.enforce-tls=false in dev/test profiles.
 */
@Configuration
@ConditionalOnProperty(name = "mcp.security.enforce-tls", havingValue = "true")
public class StartupValidationConfig {

    private final String dbUrl;
    private final String minioEndpoint;

    public StartupValidationConfig(
            @Value("${spring.datasource.url}") String dbUrl,
            @Value("${minio.endpoint}") String minioEndpoint) {
        this.dbUrl = dbUrl;
        this.minioEndpoint = minioEndpoint;
    }

    @PostConstruct
    public void validateTlsConfiguration() {
        if (!dbUrl.contains("sslmode=require")) {
            throw new IllegalStateException(
                "DB_URL must contain 'sslmode=require' in production. "
                + "Add ?sslmode=require to the JDBC URL, or set "
                + "mcp.security.enforce-tls=false to suppress this check in non-prod environments.");
        }
        if (!minioEndpoint.startsWith("https://")) {
            throw new IllegalStateException(
                "MINIO_ENDPOINT must use HTTPS in production. "
                + "Update the MINIO_ENDPOINT value to start with https://, or set "
                + "mcp.security.enforce-tls=false to suppress this check in non-prod environments.");
        }
    }
}
