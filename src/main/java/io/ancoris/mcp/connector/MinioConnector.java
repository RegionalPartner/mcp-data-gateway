package io.ancoris.mcp.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.Map;

@Component
public class MinioConnector {

    private static final Logger log = LoggerFactory.getLogger(MinioConnector.class);
    private static final int MAX_FRAGMENT_CHARS = 500;
    private static final String ALLOWED_KEY_PREFIX = "chunks/";

    private final MinioClient minioClient;
    private final String bucket;
    private final ObjectMapper objectMapper;

    public MinioConnector(MinioClient minioClient,
                          @Value("${minio.bucket:mcp-documents}") String bucket,
                          ObjectMapper objectMapper) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches a pre-indexed text chunk from MinIO.
     * Returns only the "text" field — never the full object bytes.
     */
    @SuppressWarnings("unchecked")
    public String fetchChunk(String minioKey) {
        // SEC-019: reject keys that are missing the expected prefix or contain path traversal
        if (minioKey == null || !minioKey.startsWith(ALLOWED_KEY_PREFIX) || minioKey.contains("..")) {
            log.warn("Rejected suspicious MinIO key: {}", minioKey);
            return "";
        }
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(minioKey).build())) {
            Map<String, Object> chunk = objectMapper.readValue(stream, Map.class);
            String text = (String) chunk.getOrDefault("text", "");
            return text.length() > MAX_FRAGMENT_CHARS
                    ? text.substring(0, MAX_FRAGMENT_CHARS)
                    : text;
        } catch (Exception e) {
            log.warn("Failed to fetch chunk from MinIO key={}: {}", minioKey, e.getMessage());
            return "";
        }
    }
}
