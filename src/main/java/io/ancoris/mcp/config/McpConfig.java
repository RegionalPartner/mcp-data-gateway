package io.ancoris.mcp.config;

import io.ancoris.mcp.tools.DatabaseQueryTool;
import io.ancoris.mcp.tools.DocumentSearchTool;
import io.ancoris.mcp.tools.SourceListTool;
import io.minio.MinioClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /**
     * Explicitly register all MCP tools with the server.
     *
     * Spring AI's McpServerAnnotationScannerAutoConfiguration is conditional on
     * org.springaicommunity.mcp.annotation.McpTool being on the classpath, which
     * it is not (tools use org.springframework.ai.tool.annotation.Tool instead).
     * Without this bean the ToolCallbackConverterAutoConfiguration sees no tools
     * and tools/list returns an empty array.
     */
    @Bean
    public ToolCallbackProvider mcpTools(DatabaseQueryTool databaseQueryTool,
                                          DocumentSearchTool documentSearchTool,
                                          SourceListTool sourceListTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(databaseQueryTool, documentSearchTool, sourceListTool)
                .build();
    }
}
