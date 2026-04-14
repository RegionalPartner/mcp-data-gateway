package io.ancoris.mcp.config;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Ensures the MCP server advertises protocol version 2025-11-25, required by
 * Claude Code ≥ 2.1.104.
 *
 * Spring AI 1.1.4 bundles MCP SDK 0.17.x whose
 * WebMvcStreamableServerTransportProvider.protocolVersions() hardcodes the
 * list ending at 2025-06-18.  Without 2025-11-25 in the list the server issues
 * a version-downgrade warning and Claude Code disconnects.
 *
 * WebMvcStreamableServerTransportProvider has a private constructor so CGLIB
 * proxying is not viable.  Instead this BeanPostProcessor patches the
 * McpAsyncServer.protocolVersions field via reflection immediately after the
 * server bean is initialised, before any client connects.
 */
@Component
public class McpProtocolVersionConfig implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolVersionConfig.class);
    private static final String LATEST_PROTOCOL = "2025-11-25";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof McpSyncServer mcpSyncServer)) {
            return bean;
        }
        try {
            Field asyncServerField = McpSyncServer.class.getDeclaredField("asyncServer");
            asyncServerField.setAccessible(true);
            McpAsyncServer asyncServer = (McpAsyncServer) asyncServerField.get(mcpSyncServer);

            Field versionsField = McpAsyncServer.class.getDeclaredField("protocolVersions");
            versionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> versions = (List<String>) versionsField.get(asyncServer);

            if (!versions.contains(LATEST_PROTOCOL)) {
                List<String> extended = new ArrayList<>(versions);
                extended.add(LATEST_PROTOCOL);
                versionsField.set(asyncServer, extended);
                log.info("MCP server protocol versions extended to: {}", extended);
            }
        } catch (ReflectiveOperationException e) {
            log.warn("Could not extend MCP protocol versions — Claude Code may reject the connection: {}", e.getMessage());
        }
        return bean;
    }
}
