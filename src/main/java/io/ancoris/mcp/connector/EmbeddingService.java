package io.ancoris.mcp.connector;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring AI's EmbeddingModel.
 * Decouples the tool layer from the Spring AI API surface so the model
 * can be mocked in tests without touching Spring AI's generic interface.
 */
@Component
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embeds {@code text} using the configured Ollama model (nomic-embed-text).
     *
     * @return float[] of length 768, or null if the model is unavailable.
     */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }
}
