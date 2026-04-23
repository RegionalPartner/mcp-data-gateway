package io.ancoris.mcp.model;

/**
 * D5: helper constructor for producing a single "denial" {@link DataFragment}
 * that a search tool returns when the caller was rejected (QueryGuard) or
 * throttled (ChunkBudgetEnforcer).
 *
 * <p>Kept here rather than inlined so the bracketed markers
 * ({@code [QUERY_REJECTED]}, {@code [BUDGET_EXCEEDED retryAfter=Xs]}) have one
 * canonical spelling, used by both DocumentSearchTool and SemanticSearchTool.
 * Downstream LLM clients are expected to detect these prefixes.
 */
public final class DenialFragment {

    public static final String DENIAL_DOC_NAME = "__denial__";
    public static final String DENIAL_CLASSIFICATION = "PUBLIC";

    private DenialFragment() {
        // Not instantiable — utility class.
    }

    public static DataFragment queryRejected(String reason) {
        return new DataFragment(
                "denial",
                DENIAL_DOC_NAME,
                DENIAL_CLASSIFICATION,
                "[QUERY_REJECTED] " + reason,
                0);
    }

    public static DataFragment budgetExceeded(long retryAfterSeconds) {
        return new DataFragment(
                "denial",
                DENIAL_DOC_NAME,
                DENIAL_CLASSIFICATION,
                "[BUDGET_EXCEEDED retryAfter=" + retryAfterSeconds + "s]",
                0);
    }
}
