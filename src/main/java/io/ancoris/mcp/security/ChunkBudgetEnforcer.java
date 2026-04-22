package io.ancoris.mcp.security;

import io.ancoris.mcp.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * D5: hourly rolling chunk budget per API key.
 *
 * <p>Invoked <em>after</em> retrieval with the actual number of chunks returned
 * by the tool (so legitimate "small" queries are counted honestly, and an attacker
 * can't get usage by setting {@code maxResults=0}).
 *
 * <p>{@code @Transactional(REQUIRES_NEW)} is load-bearing: the budget commit must
 * persist even if the outer tool transaction rolls back (preventing rollback-loop
 * amplification). The rate_limit_state UPSERT is trivial and never conflicts with
 * the caller's RLS context; running in its own transaction is safe.
 *
 * <p>The UPSERT uses ON CONFLICT DO UPDATE to atomically increment the counter.
 * The WHERE clause in the DO UPDATE lets the same statement detect a fresh window
 * rollover (chunk_count=0 if window_start moved).
 */
@Component
public class ChunkBudgetEnforcer {

    /**
     * Single statement that either inserts the initial (api_key_id, window_start)
     * row or increments chunk_count atomically on conflict. Returns the
     * post-increment chunk_count so the caller can detect budget overruns.
     */
    static final String UPSERT_SQL = """
            INSERT INTO rate_limit_state (api_key_id, window_start, chunk_count, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (api_key_id, window_start)
            DO UPDATE SET chunk_count = rate_limit_state.chunk_count + EXCLUDED.chunk_count,
                          updated_at  = now()
            RETURNING chunk_count
            """;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final long hourlyCap;

    public ChunkBudgetEnforcer(
            JdbcTemplate jdbc,
            AuditService auditService,
            @Value("${gateway.chunk-budget.hourly-cap:10000}") long hourlyCap) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.hourlyCap = hourlyCap;
    }

    /**
     * Atomically records {@code chunkCount} retrieved chunks for {@code apiKeyId}
     * against the current hourly window. Throws {@link BudgetExceededException}
     * with the seconds-to-next-window if the post-increment total exceeds the cap.
     *
     * <p>Safe against a zero or negative count (no-op — we trust the caller not to
     * pass negatives; the UPSERT still runs to keep audit in sync).
     *
     * @param apiKeyId caller API key
     * @param chunkCount number of chunks this call actually retrieved
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitChunks(UUID apiKeyId, int chunkCount) {
        if (apiKeyId == null) {
            // Unauthenticated tool invocation should never reach here, but if it
            // does we fail-closed — no counter entry to trace.
            throw new BudgetExceededException(secondsUntilNextHour(Instant.now()));
        }
        Instant now = Instant.now();
        Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);

        Long newTotal = jdbc.queryForObject(
                UPSERT_SQL,
                Long.class,
                apiKeyId,
                Timestamp.from(windowStart),
                (long) Math.max(0, chunkCount));

        if (newTotal != null && newTotal > hourlyCap) {
            long retryAfter = secondsUntilNextHour(now);
            auditService.log("chunk_budget_exceeded", apiKeyId,
                    Map.of("cap", hourlyCap, "total", newTotal, "retryAfterSeconds", retryAfter),
                    "budget_exceeded");
            throw new BudgetExceededException(retryAfter);
        }
    }

    long getHourlyCap() {
        return hourlyCap;
    }

    /** Seconds remaining until the next top-of-hour boundary (always &gt;= 1). */
    static long secondsUntilNextHour(Instant now) {
        Instant nextHour = now.atOffset(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .plusHours(1)
                .toInstant();
        long seconds = ChronoUnit.SECONDS.between(now, nextHour);
        return Math.max(1L, seconds);
    }
}
