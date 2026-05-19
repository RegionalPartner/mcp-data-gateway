package io.ancoris.mcp.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding model configuration — HuggingFace Text Embeddings Inference (TEI).
 *
 * TEI exposes an OpenAI-compatible REST API so we reuse spring-ai-openai.
 * nomic-embed-text-v1.5 produces 768-dim vectors (same as v1), stored in
 * document_chunks.embedding (RAG-001).
 *
 * TEI benchmarks at ~20 ms P50 vs ~99 ms for Ollama on the same model (5× improvement).
 * No API key is required; TEI accepts any non-empty string.
 *
 * Spring AI 2.0.0-M5 replaced the hand-rolled OpenAiApi with the official
 * com.openai.client.OpenAIClient SDK; baseUrl is now wired via
 * OpenAIOkHttpClient.builder().
 */
@Configuration
public class TeiConfig {

    @Bean
    public EmbeddingModel teiEmbeddingModel(
            @Value("${spring.ai.openai.base-url:http://tei:8080}") String baseUrl,
            ObservationRegistry observationRegistry) {

        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey("none")
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model("nomic-embed-text-v1.5")
                .build();

        return new OpenAiEmbeddingModel(
                openAIClient,
                MetadataMode.EMBED,
                options,
                observationRegistry);
    }
}
