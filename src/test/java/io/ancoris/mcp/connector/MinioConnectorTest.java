package io.ancoris.mcp.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioConnectorTest {

    @Mock
    private MinioClient minioClient;

    @Mock
    private ObjectMapper objectMapper;

    private MinioConnector connector;

    @BeforeEach
    void setUp() {
        connector = new MinioConnector(minioClient, "test-bucket", objectMapper);
    }

    // -----------------------------------------------------------------------
    // SEC-019: null key must be rejected before any MinIO call
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_nullKey_returnsEmpty() throws Exception {
        String result = connector.fetchChunk(null);
        assertThat(result).isEmpty();
        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
    }

    // -----------------------------------------------------------------------
    // SEC-019: key without "chunks/" prefix must be rejected
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_missingPrefix_returnsEmpty() throws Exception {
        String result = connector.fetchChunk("documents/secret.json");
        assertThat(result).isEmpty();
        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
    }

    // -----------------------------------------------------------------------
    // SEC-019: ".." path traversal must be rejected
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_pathTraversal_returnsEmpty() throws Exception {
        String result = connector.fetchChunk("chunks/../secrets/passwd.json");
        assertThat(result).isEmpty();
        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
    }

    // -----------------------------------------------------------------------
    // SEC-019: bare ".." without prefix also rejected
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_dotDotOnly_returnsEmpty() throws Exception {
        String result = connector.fetchChunk("../chunks/legit.json");
        assertThat(result).isEmpty();
        verify(minioClient, never()).getObject(any(GetObjectArgs.class));
    }

    // -----------------------------------------------------------------------
    // Text longer than 500 chars must be truncated to exactly 500 chars
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchChunk_textExceeds500Chars_isTruncated() throws Exception {
        String longText = "x".repeat(600);
        GetObjectResponse mockResp = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResp);
        when(objectMapper.readValue(any(InputStream.class), eq(Map.class)))
                .thenReturn(Map.of("text", longText));

        String result = connector.fetchChunk("chunks/doc.json");

        assertThat(result).hasSize(500);
        assertThat(result).isEqualTo(longText.substring(0, 500));
    }

    // -----------------------------------------------------------------------
    // Text within 500 chars must be returned unchanged
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchChunk_textUnder500Chars_unchanged() throws Exception {
        String shortText = "Hello, world!";
        GetObjectResponse mockResp = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResp);
        when(objectMapper.readValue(any(InputStream.class), eq(Map.class)))
                .thenReturn(Map.of("text", shortText));

        String result = connector.fetchChunk("chunks/short.json");

        assertThat(result).isEqualTo(shortText);
    }

    // -----------------------------------------------------------------------
    // Exactly 500 chars must not be truncated
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchChunk_textExactly500Chars_unchanged() throws Exception {
        String exactText = "a".repeat(500);
        GetObjectResponse mockResp = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResp);
        when(objectMapper.readValue(any(InputStream.class), eq(Map.class)))
                .thenReturn(Map.of("text", exactText));

        String result = connector.fetchChunk("chunks/exact.json");

        assertThat(result).hasSize(500);
    }

    // -----------------------------------------------------------------------
    // MinIO exception — must return empty string, not propagate
    // -----------------------------------------------------------------------

    @Test
    void fetchChunk_minioException_returnsEmpty() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO unavailable"));

        String result = connector.fetchChunk("chunks/failing.json");

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Missing "text" field in JSON — falls back to empty string
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void fetchChunk_missingTextField_returnsEmpty() throws Exception {
        GetObjectResponse mockResp = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResp);
        when(objectMapper.readValue(any(InputStream.class), eq(Map.class)))
                .thenReturn(Map.of("other_field", "irrelevant"));

        String result = connector.fetchChunk("chunks/no-text.json");

        assertThat(result).isEmpty();
    }
}
