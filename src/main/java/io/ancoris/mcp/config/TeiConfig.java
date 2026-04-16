package io.ancoris.mcp.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * Embedding model configuration — HuggingFace Text Embeddings Inference (TEI).
 *
 * TEI exposes an OpenAI-compatible REST API so we reuse spring-ai-openai.
 * nomic-embed-text-v1.5 produces 768-dim vectors (same as v1), stored in
 * document_chunks.embedding (RAG-001).
 *
 * TEI benchmarks at ~20 ms P50 vs ~99 ms for Ollama on the same model (5× improvement).
 * No API key is required; TEI accepts any non-empty string.
 */
@Configuration
public class TeiConfig {

    @Bean
    public EmbeddingModel teiEmbeddingModel(
            @Value("${spring.ai.openai.base-url:http://tei:8080}") String baseUrl,
            ObservationRegistry observationRegistry) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey("none")
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model("nomic-embed-text-v1.5")
                .build();

        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.EMBED,
                options,
                RetryTemplate.defaultInstance(),
                observationRegistry);
    }
}
