package io.ancoris.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

class TeiConfigTest {

    @Test
    void teiEmbeddingModel_constructsOpenAiEmbeddingModel_withCustomBaseUrl() {
        TeiConfig config = new TeiConfig();

        EmbeddingModel model =
                config.teiEmbeddingModel("http://test-tei:1234", ObservationRegistry.NOOP);

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }
}
