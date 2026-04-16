/// PostgreSQL I/O for key rotation.
///
/// Operates on document_chunks.encrypted_content (BYTEA).
/// Uses runtime sqlx queries (no query! macro) so no live DB is required at compile time.
use anyhow::Result;
use sqlx::{PgPool, Postgres, Transaction};
use uuid::Uuid;

/// One row from document_chunks that needs re-encryption.
#[derive(sqlx::FromRow)]
pub struct Chunk {
    pub id: Uuid,
    pub encrypted_content: Vec<u8>,
}

/// Count rows with non-null encrypted_content (the rotation target set).
pub async fn count_pending(pool: &PgPool) -> Result<i64> {
    let row: (i64,) = sqlx::query_as(
        "SELECT COUNT(*) FROM document_chunks WHERE encrypted_content IS NOT NULL",
    )
    .fetch_one(pool)
    .await?;
    Ok(row.0)
}

/// Fetch a page of chunks ordered by id (stable, restartable pagination).
pub async fn fetch_batch(pool: &PgPool, offset: i64, limit: i64) -> Result<Vec<Chunk>> {
    let rows = sqlx::query_as::<_, Chunk>(
        "SELECT id, encrypted_content
         FROM document_chunks
         WHERE encrypted_content IS NOT NULL
         ORDER BY id
         LIMIT $1 OFFSET $2",
    )
    .bind(limit)
    .bind(offset)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// Begin a transaction — the caller commits or rolls back.
pub async fn begin(pool: &PgPool) -> Result<Transaction<'_, Postgres>> {
    Ok(pool.begin().await?)
}

/// Write one re-encrypted row inside an open transaction.
pub async fn update_content(
    tx: &mut Transaction<'_, Postgres>,
    id: Uuid,
    new_content: &[u8],
) -> Result<()> {
    sqlx::query("UPDATE document_chunks SET encrypted_content = $1 WHERE id = $2")
        .bind(new_content)
        .bind(id)
        .execute(&mut **tx)
        .await?;
    Ok(())
}
