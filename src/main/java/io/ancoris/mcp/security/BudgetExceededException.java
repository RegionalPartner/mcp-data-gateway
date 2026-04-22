package io.ancoris.mcp.security;

/**
 * D5: thrown by {@link ChunkBudgetEnforcer#commitChunks} when an API key has exceeded
 * its hourly chunk quota (configured via {@code gateway.chunk-budget.hourly-cap}).
 *
 * <p>{@link #getRetryAfterSeconds()} reports the number of seconds until the next
 * hourly window boundary, so tools can surface a {@code [BUDGET_EXCEEDED retryAfter=Xs]}
 * marker in their response.
 */
public class BudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public BudgetExceededException(long retryAfterSeconds) {
        super("Chunk budget exceeded; retry after " + retryAfterSeconds + " seconds");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
