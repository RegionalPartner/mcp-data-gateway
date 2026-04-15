package io.ancoris.mcp.integration;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Singleton container — started once per JVM, never stopped between test classes.
    // This prevents Spring context cache misses when @Container stops/restarts containers
    // and the cached context points at a dead port.
    // pgvector/pgvector:pg16 instead of postgres:16-alpine — required for V7 migration
    // (CREATE EXTENSION vector) used by the semantic search RAG feature.
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("mcpgateway")
                .withUsername("testuser")
                .withPassword("testpass");
        POSTGRES.start();
    }

    // RAG-001: replace the Ollama-backed EmbeddingModel with a mock so integration
    // tests do not require a running Ollama instance.  EmbeddingInitializer handles
    // a null return from embed() gracefully (skips the chunk silently).
    @MockBean
    protected EmbeddingModel embeddingModel;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Use 127.0.0.1 explicitly: on this host 'localhost' resolves to ::1 (IPv6)
        // but Docker maps container ports to 127.0.0.1 (IPv4) only
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://127.0.0.1:" + POSTGRES.getMappedPort(5432) + "/mcpgateway");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
