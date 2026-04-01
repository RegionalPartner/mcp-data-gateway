package io.ancoris.mcp.model;

/**
 * The only data structure the LLM ever receives from document searches.
 * fragmentText is capped at 500 chars — raw documents are never exposed.
 */
public record DataFragment(
        String sourceId,
        String docName,
        String classification,
        String fragmentText,
        int chunkIndex
) {}
