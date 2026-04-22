package io.ancoris.mcp.security;

/**
 * D5: thrown by {@link QueryGuard#validate(String)} when an input fails the reject tier
 * (chat-role markers, model special tokens, oversized base64 blobs, control characters
 * or length &gt; {@link QueryGuard#MAX_QUERY_LENGTH}).
 *
 * <p>Search tools catch this and convert it into a {@code [QUERY_REJECTED] ...}
 * bracketed error marker so the caller (LLM agent) sees a protocol-friendly reason
 * without the query ever hitting the embedding endpoint.
 *
 * <p>Unchecked so tool method signatures don't need to declare it; MCP framework
 * also gracefully serialises it as a JSON-RPC error if uncaught.
 */
public class ToolInputRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public ToolInputRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
