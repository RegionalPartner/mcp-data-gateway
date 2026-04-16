/// key-rotation — AES-256-GCM key rotation for document_chunks.encrypted_content.
///
/// Reads rows in batches of --batch-size, decrypts with --old-key, re-encrypts
/// with --new-key, and writes back atomically per batch.
///
/// Restart-safe: rows already re-encrypted with the new key are detected and
/// skipped (decrypt-with-old-key fails → try-new-key succeeds → skip).
///
/// Wire format: [12B IV][ciphertext + 16B GCM tag]  (ContentEncryptor.java-compatible)
use anyhow::{Context, Result};
use clap::Parser;
use indicatif::{ProgressBar, ProgressStyle};
use sqlx::postgres::PgPoolOptions;

mod crypto;
mod db;
mod embed;

#[derive(Parser, Debug)]
#[command(
    name = "key-rotation",
    about = "Re-encrypt document_chunks.encrypted_content with a new AES-256-GCM key"
)]
struct Args {
    /// Old encryption key — 64 hex chars (32 bytes).  Also read from $OLD_KEY.
    #[arg(long, env = "OLD_KEY")]
    old_key: String,

    /// New encryption key — 64 hex chars (32 bytes).  Also read from $NEW_KEY.
    #[arg(long, env = "NEW_KEY")]
    new_key: String,

    /// PostgreSQL connection URL.  Also read from $DATABASE_URL.
    #[arg(long, env = "DATABASE_URL")]
    database_url: String,

    /// Print what would be done without writing any rows.
    #[arg(long, default_value = "false")]
    dry_run: bool,

    /// Re-generate embeddings after re-encryption via TEI (Phase 3+).
    /// Requires V7__add_vector_embeddings.sql applied and TEI_BASE_URL reachable.
    #[arg(long, default_value = "false")]
    reembed: bool,

    /// TEI base URL (used when --reembed is set).  Also read from $TEI_BASE_URL.
    #[arg(long, env = "TEI_BASE_URL", default_value = "http://tei:8080")]
    tei_base_url: String,

    /// Number of rows to process per database transaction.
    #[arg(long, default_value = "1000")]
    batch_size: i64,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    // Validate keys up front — fail fast before touching the DB.
    let old_key = crypto::parse_key(&args.old_key)
        .context("--old-key / $OLD_KEY is not a valid 64-char hex string")?;
    let new_key = crypto::parse_key(&args.new_key)
        .context("--new-key / $NEW_KEY is not a valid 64-char hex string")?;

    if old_key == new_key {
        anyhow::bail!("--old-key and --new-key are identical — rotation would be a no-op");
    }

    let pool = PgPoolOptions::new()
        .max_connections(4)
        .connect(&args.database_url)
        .await
        .context("failed to connect to database")?;

    let total = db::count_pending(&pool).await?;
    println!("Rows with encrypted_content: {total}");

    if args.dry_run {
        println!("Dry-run: no writes performed.");
        return Ok(());
    }

    // --reembed activated in Phase 3: re-generate embeddings after re-encryption.
    // Requires the embedding column added by V7__add_vector_embeddings.sql and
    // TEI serving at TEI_BASE_URL.
    let embed_client: Option<embed::EmbedClient> = if args.reembed {
        Some(embed::EmbedClient::new(&args.tei_base_url))
    } else {
        None
    };

    let pb = ProgressBar::new(total as u64);
    pb.set_style(
        ProgressStyle::default_bar()
            .template("[{elapsed_precise}] {bar:40.cyan/blue} {pos}/{len} rows  eta {eta}")
            .unwrap(),
    );

    let mut offset: i64 = 0;
    let mut rotated: u64 = 0;
    let mut skipped: u64 = 0; // already rotated on a previous run

    loop {
        let batch = db::fetch_batch(&pool, offset, args.batch_size).await?;
        if batch.is_empty() {
            break;
        }
        let batch_len = batch.len() as i64;

        let mut tx = db::begin(&pool).await?;

        for chunk in &batch {
            let plain = match crypto::decrypt(&old_key, &chunk.encrypted_content) {
                Ok(p) => p,
                Err(_) => {
                    // Maybe already rotated on a previous (interrupted) run.
                    match crypto::decrypt(&new_key, &chunk.encrypted_content) {
                        Ok(_) => {
                            // Row is already encrypted with the new key — skip it.
                            skipped += 1;
                            continue;
                        }
                        Err(_) => {
                            anyhow::bail!(
                                "Row {} cannot be decrypted with either key — \
                                 data may be corrupted or a third key is in use",
                                chunk.id
                            );
                        }
                    }
                }
            };

            let new_enc = crypto::encrypt(&new_key, &plain)?;

            if let Some(ref client) = embed_client {
                let text = String::from_utf8_lossy(&plain);
                let embedding = client.embed(&text).await?;
                db::update_content_and_embedding(&mut tx, chunk.id, &new_enc, &embedding).await?;
            } else {
                db::update_content(&mut tx, chunk.id, &new_enc).await?;
            }

            rotated += 1;
        }

        tx.commit().await?;
        pb.inc(batch_len as u64);
        offset += batch_len;
    }

    pb.finish_with_message("done");
    println!("Complete: {rotated} rows rotated, {skipped} already rotated (skipped).");

    Ok(())
}
