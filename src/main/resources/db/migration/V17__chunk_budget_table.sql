-- V17 (D5): rate_limit_state table backing ChunkBudgetEnforcer.
--
-- Hourly rolling budget on retrieved document chunks, per API key. The app
-- computes the current window_start as date_trunc('hour', now()), UPSERTs on
-- (api_key_id, window_start), and increments chunk_count by the actual number
-- of chunks returned by DocumentSearchTool / SemanticSearchTool.
--
-- The UPSERT runs in a REQUIRES_NEW transaction (ChunkBudgetEnforcer) so the
-- budget commit persists even if the outer tool transaction rolls back — this
-- prevents an attacker from amplifying reads via forced rollback loops.
--
-- Old rows (> 24h) are pruned daily by a @Scheduled(cron = "0 37 * * * *")
-- hook in RateLimitStatePruner. The window_start index supports that prune.

CREATE TABLE IF NOT EXISTS rate_limit_state (
    api_key_id   UUID        NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    chunk_count  BIGINT      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (api_key_id, window_start)
);

CREATE INDEX IF NOT EXISTS rate_limit_state_window_idx
    ON rate_limit_state (window_start);
