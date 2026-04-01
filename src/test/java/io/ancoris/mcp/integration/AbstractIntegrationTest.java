package io.ancoris.mcp.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Singleton containers — started once per JVM, never stopped between test classes.
    // This prevents Spring context cache misses when @Container stops/restarts containers
    // and the cached context points at a dead port.
    static final PostgreSQLContainer<?> POSTGRES;
    static final MinIOContainer MINIO;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("mcpgateway")
                .withUsername("testuser")
                .withPassword("testpass");
        MINIO = new MinIOContainer("minio/minio:latest")
                .withUserName("minioadmin")
                .withPassword("minioadmin");
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Use 127.0.0.1 explicitly: on this host 'localhost' resolves to ::1 (IPv6)
        // but Docker maps container ports to 127.0.0.1 (IPv4) only
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://127.0.0.1:" + POSTGRES.getMappedPort(5432) + "/mcpgateway");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint",
                () -> "http://127.0.0.1:" + MINIO.getMappedPort(9000));
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
        registry.add("minio.bucket", () -> "mcp-test-documents");
    }
}
