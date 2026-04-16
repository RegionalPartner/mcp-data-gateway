/// Delta cursor persistence for incremental SharePoint sync.
///
/// The ingestion daemon saves the @odata.deltaLink URL returned by Graph after
/// each successful poll cycle.  On the next cycle it passes this URL directly
/// as the starting point, receiving only changes since the last run.
///
/// Schema: ingestion_state.drive_id (PK) + delta_token (full deltaLink URL) + last_synced.
/// Created by V10__ingestion_state.sql.
use anyhow::Result;
use sqlx::PgPool;

/// Load the saved delta cursor for `drive_id`.
///
/// Returns `None` on the first run (no row yet), which triggers a full crawl.
pub async fn load_cursor(pool: &PgPool, drive_id: &str) -> Result<Option<String>> {
    let row: Option<(String,)> = sqlx::query_as(
        "SELECT delta_token FROM ingestion_state WHERE drive_id = $1",
    )
    .bind(drive_id)
    .fetch_optional(pool)
    .await?;
    Ok(row.map(|(token,)| token))
}

/// Persist the delta cursor for `drive_id` (upsert).
///
/// `delta_token` is the full @odata.deltaLink URL from the last Graph delta cycle.
pub async fn save_cursor(pool: &PgPool, drive_id: &str, delta_token: &str) -> Result<()> {
    sqlx::query(
        "INSERT INTO ingestion_state (drive_id, delta_token, last_synced)
         VALUES ($1, $2, now())
         ON CONFLICT (drive_id) DO UPDATE
           SET delta_token = EXCLUDED.delta_token,
               last_synced = now()",
    )
    .bind(drive_id)
    .bind(delta_token)
    .execute(pool)
    .await?;
    Ok(())
}
