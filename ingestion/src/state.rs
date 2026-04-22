// Delta cursor persistence for incremental sync.
//
// The ingestion daemon saves the opaque cursor returned by each SourceConnector
// after a successful poll cycle.  On the next cycle it passes this cursor back
// to list_changes() as the starting point, receiving only changes since the
// last run.
//
// Schema: ingestion_state.connector_id (PK) + delta_token + last_synced.
// Column renamed drive_id → connector_id in V14__rls_workspace_restrictive.sql.
use anyhow::Result;
use sqlx::PgPool;

// Load the saved delta cursor for `connector_id`.
//
// Returns None on the first run (no row yet), which triggers a full crawl.
pub async fn load_cursor(pool: &PgPool, connector_id: &str) -> Result<Option<String>> {
    let row: Option<(String,)> =
        sqlx::query_as("SELECT delta_token FROM ingestion_state WHERE connector_id = $1")
            .bind(connector_id)
            .fetch_optional(pool)
            .await?;
    Ok(row.map(|(token,)| token))
}

// Persist the delta cursor for `connector_id` (upsert).
//
// `delta_token` is the opaque cursor string returned by SourceConnector.list_changes().
pub async fn save_cursor(pool: &PgPool, connector_id: &str, delta_token: &str) -> Result<()> {
    sqlx::query(
        "INSERT INTO ingestion_state (connector_id, delta_token, last_synced)
         VALUES ($1, $2, now())
         ON CONFLICT (connector_id) DO UPDATE
           SET delta_token = EXCLUDED.delta_token,
               last_synced = now()",
    )
    .bind(connector_id)
    .bind(delta_token)
    .execute(pool)
    .await?;
    Ok(())
}
