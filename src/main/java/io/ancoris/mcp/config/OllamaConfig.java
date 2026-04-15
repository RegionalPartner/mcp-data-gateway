package io.ancoris.mcp.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual configuration for the Ollama embedding model.
 *
 * We use spring-ai-ollama (core) instead of spring-ai-starter-model-ollama (starter)
 * to avoid spring-ai-retry-autoconfigure auto-wiring.  Configuring the bean manually
 * gives us full control over the OllamaApi lifecycle without relying on Spring Boot
 * auto-configuration, which varies significantly across spring-ai releases.
 *
 * RAG-001: nomic-embed-text produces 768-dim vectors stored in document_chunks.embedding.
 */
@Configuration
public class OllamaConfig {

    @Bean
    public EmbeddingModel ollamaEmbeddingModel(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${spring.ai.ollama.embedding.model:nomic-embed-text}") String model,
            ObservationRegistry observationRegistry) {

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();

        OllamaEmbeddingOptions options = new OllamaEmbeddingOptions.Builder()
                .model(model)
                .build();

        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }
}
