/// PostgreSQL I/O for the ingestion daemon.
///
/// `upsert_chunks`: atomically replaces all chunks for one SharePoint item.
///   DELETE existing chunks → INSERT new chunks (all in one transaction).
///   The `embedding` column uses the pgvector text-input format `[f1,...,fn]::vector`
///   so no extra driver type registration is required.
///
/// `delete_chunks`: removes all chunks for a deleted SharePoint item.
///   Called before upsert, and directly when Graph reports a deletion.
use anyhow::Result;
use sqlx::PgPool;
use tracing::debug;
use uuid::Uuid;

/// Remove all document_chunks rows for a given SharePoint item ID.
/// Safe to call when no chunks exist (zero-row delete is a no-op).
pub async fn delete_chunks(pool: &PgPool, source_item_id: &str) -> Result<()> {
    sqlx::query("DELETE FROM document_chunks WHERE source_item_id = $1")
        .bind(source_item_id)
        .execute(pool)
        .await?;
    Ok(())
}

/// Atomically replace all chunks for one document.
///
/// # Panics (debug)
/// Panics if `encrypted_chunks` and `embeddings` have different lengths.
pub async fn upsert_chunks(
    pool: &PgPool,
    source_item_id: &str,
    doc_name: &str,
    classification: &str,
    encrypted_chunks: &[Vec<u8>],
    embeddings: &[Vec<f32>],
) -> Result<()> {
    debug_assert_eq!(
        encrypted_chunks.len(),
        embeddings.len(),
        "encrypted_chunks and embeddings must have the same length"
    );

    let mut tx = pool.begin().await?;

    // Remove stale chunks from previous ingestion of this item.
    sqlx::query("DELETE FROM document_chunks WHERE source_item_id = $1")
        .bind(source_item_id)
        .execute(&mut *tx)
        .await?;

    for (i, (enc, emb)) in encrypted_chunks.iter().zip(embeddings.iter()).enumerate() {
        let id = Uuid::new_v4();
        // minio_key is nullable since V6; set to NULL for ingestion-daemon-written rows.
        // embedding uses the pgvector literal syntax: '[f1,f2,...,fn]'::vector
        sqlx::query(
            "INSERT INTO document_chunks
             (id, doc_name, classification, chunk_index, encrypted_content, embedding, source_item_id)
             VALUES ($1, $2, $3, $4, $5, $6::vector, $7)",
        )
        .bind(id)
        .bind(doc_name)
        .bind(classification)
        .bind(i as i32)
        .bind(enc)
        .bind(format_vector(emb))
        .bind(source_item_id)
        .execute(&mut *tx)
        .await?;
    }

    tx.commit().await?;
    debug!(
        "Upserted {} chunk(s) for source_item_id={source_item_id} ({doc_name})",
        encrypted_chunks.len()
    );
    Ok(())
}

/// Format a float slice as a pgvector literal: [f1,f2,...,fn]
fn format_vector(v: &[f32]) -> String {
    let inner: Vec<String> = v.iter().map(|f| f.to_string()).collect();
    format!("[{}]", inner.join(","))
}
