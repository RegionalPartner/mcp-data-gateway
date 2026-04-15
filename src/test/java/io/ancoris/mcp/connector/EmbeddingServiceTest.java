package io.ancoris.mcp.connector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService(embeddingModel);
    }

    // -----------------------------------------------------------------------
    // embed() delegates to EmbeddingModel and returns its result
    // -----------------------------------------------------------------------

    @Test
    void embed_delegatesToModel_andReturnsVector() {
        float[] expected = new float[768];
        expected[0] = 0.42f;
        when(embeddingModel.embed("hello world")).thenReturn(expected);

        float[] result = embeddingService.embed("hello world");

        verify(embeddingModel).embed("hello world");
        assertThat(result).isSameAs(expected);
    }

    // -----------------------------------------------------------------------
    // embed() propagates null when model returns null (handled upstream)
    // -----------------------------------------------------------------------

    @Test
    void embed_modelReturnsNull_propagatesNull() {
        when(embeddingModel.embed("query")).thenReturn(null);

        float[] result = embeddingService.embed("query");

        assertThat(result).isNull();
    }
}
