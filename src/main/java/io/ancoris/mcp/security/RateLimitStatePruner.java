package io.ancoris.mcp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D5: daily prune of {@code rate_limit_state} rows older than 24 hours.
 *
 * <p>Cron pattern {@code 0 37 * * * *} runs once per hour at minute 37 — chosen to
 * offset from top-of-hour (minimising contention with the hourly window rollover
 * inside {@link ChunkBudgetEnforcer}) and to avoid "round number" stampedes that
 * other cron jobs tend to hit.
 */
@Component
public class RateLimitStatePruner {

    private static final Logger log = LoggerFactory.getLogger(RateLimitStatePruner.class);

    private final JdbcTemplate jdbc;

    public RateLimitStatePruner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes rate_limit_state rows whose window_start is more than 24h in the past.
     * Cron format: {@code second minute hour day-of-month month day-of-week}.
     */
    @Scheduled(cron = "0 37 * * * *")
    public void pruneStaleWindows() {
        int removed = jdbc.update(
                "DELETE FROM rate_limit_state WHERE window_start < now() - INTERVAL '24 hours'");
        if (removed > 0) {
            log.info("[rate-limit-prune] removed {} stale rate_limit_state rows", removed);
        }
    }
}
