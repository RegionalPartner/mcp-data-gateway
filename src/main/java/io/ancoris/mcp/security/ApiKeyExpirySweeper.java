package io.ancoris.mcp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D5 fallback-path sweeper: reconciles {@code api_keys.status} with {@code revoked}
 * and {@code expires_at}.
 *
 * <p>We cannot use a PostgreSQL GENERATED STORED column because {@code now()} is
 * not immutable (PostgreSQL rejects such expressions at ALTER TABLE time).
 * Instead {@code status} is a plain VARCHAR kept in sync by this hourly sweep
 * plus the primary-path revoked/expires_at filters in
 * {@link ApiKeyService#authenticate(String)}.
 *
 * <p>The sweep is eventually consistent — a freshly expired key might be tagged
 * {@code ACTIVE} in the status column for up to one hour — but authentication
 * never trusts {@code status} alone: the primary boolean/timestamp checks run
 * first, and the status filter is a redundant defence-in-depth layer.
 *
 * <p>Cron pattern {@code 0 13 * * * *} runs at minute 13 of every hour. Minute 13
 * is chosen to offset from both :37 (RateLimitStatePruner) and :00 (top-of-hour
 * traffic spikes that touch session/rate-limit windows).
 */
@Component
public class ApiKeyExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyExpirySweeper.class);

    private final JdbcTemplate jdbc;

    public ApiKeyExpirySweeper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 13 * * * *")
    public void sweep() {
        int updated = jdbc.update(
                """
                UPDATE api_keys
                SET status = CASE
                    WHEN revoked THEN 'REVOKED'
                    WHEN expires_at IS NOT NULL AND expires_at < now() THEN 'EXPIRED'
                    ELSE 'ACTIVE'
                END
                WHERE status <> (CASE
                    WHEN revoked THEN 'REVOKED'
                    WHEN expires_at IS NOT NULL AND expires_at < now() THEN 'EXPIRED'
                    ELSE 'ACTIVE'
                END)
                """);
        if (updated > 0) {
            log.info("[api-key-sweeper] reconciled status on {} api_keys rows", updated);
        }
    }
}
