// MCP Data Gateway — SharePoint ingestion daemon (Phase 3).
//
// Pipeline per poll cycle (per connector):
//   1. Load delta cursor from ingestion_state (None → full crawl on first run).
//   2. Call connector.list_changes(cursor) → changed + deleted ChangeItems.
//   3. Deleted items: remove all document_chunks rows for that source_item_id.
//   4. Changed files: download → extract text → chunk (512-token / 50-token overlap)
//      → AES-256-GCM encrypt → batch embed via TEI (32/batch) → upsert chunks.
//   5. Save new cursor to ingestion_state.
//
// Wire format: [12B IV][ciphertext + 16B GCM tag] — byte-compatible with
// ContentEncryptor.java and tools/key-rotation.
//
// Supported file types: .txt, .md, .rst (UTF-8 direct).
// Unsupported (.pdf, .docx): logged and skipped; add extraction crates in a later phase.
use anyhow::{Context, Result};
use clap::Parser;
use sqlx::postgres::PgPoolOptions;
use tracing::{error, info, warn};

mod chunker;
mod connector;
mod crypto;
mod db;
mod embed;
mod state;

use connector::zoho::{ZohoClient, ZohoCredentials};
use connector::SourceConnector;

#[derive(Parser, Debug)]
#[command(
    name = "ingestion",
    about = "SharePoint → PostgreSQL ingestion daemon for MCP Data Gateway"
)]
struct Args {
    /// AES-256-GCM content key — 64 hex chars (32 bytes).
    /// Must match the MCP_CONTENT_KEY used by the gateway and key-rotation.
    #[arg(long, env = "MCP_CONTENT_KEY")]
    content_key: String,

    /// PostgreSQL connection URL.
    #[arg(long, env = "DATABASE_URL")]
    database_url: String,

    /// TEI embedding service base URL.
    #[arg(long, env = "TEI_BASE_URL", default_value = "http://tei:8080")]
    tei_base_url: String,

    /// Azure AD tenant ID (from the Azure portal).
    #[arg(long, env = "AZURE_TENANT_ID")]
    tenant_id: String,

    /// Azure AD application (client) ID.
    #[arg(long, env = "AZURE_CLIENT_ID")]
    client_id: String,

    /// Azure AD application client secret.
    #[arg(long, env = "AZURE_CLIENT_SECRET")]
    client_secret: String,

    /// SharePoint drive ID (connector_id for the microsoft_graph connector).
    /// Obtain with: GET https://graph.microsoft.com/v1.0/sites/{site-id}/drives
    #[arg(long, env = "SHAREPOINT_DRIVE_ID")]
    drive_id: String,

    /// Classification applied to all ingested document_chunks rows.
    #[arg(long, env = "CLASSIFICATION", default_value = "INTERNAL")]
    classification: String,

    /// Seconds to wait between poll cycles.
    #[arg(long, env = "POLL_INTERVAL_SECS", default_value = "300")]
    poll_interval_secs: u64,

    /// Number of chunk texts per TEI embedding request (max ~128 for TEI cpu-latest).
    #[arg(long, default_value = "32")]
    embed_batch_size: usize,

    /// Run exactly one poll cycle then exit (useful for smoke-testing).
    #[arg(long, default_value = "false")]
    once: bool,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive(tracing::Level::INFO.into()),
        )
        .init();

    let args = Args::parse();

    let content_key = crypto::parse_key(&args.content_key)
        .context("MCP_CONTENT_KEY is not a valid 64-char hex string")?;

    if !["PUBLIC", "INTERNAL", "CONFIDENTIAL"].contains(&args.classification.as_str()) {
        anyhow::bail!("CLASSIFICATION must be one of: PUBLIC, INTERNAL, CONFIDENTIAL");
    }

    let pool = PgPoolOptions::new()
        .max_connections(8)
        .connect(&args.database_url)
        .await
        .context("failed to connect to PostgreSQL")?;

    // Build the connector list from env/config.
    //
    // microsoft_graph: always built when SHAREPOINT_DRIVE_ID is set (mandatory arg).
    // zoho_workdrive:  built only when ZOHO_CLIENT_ID / ZOHO_CLIENT_SECRET /
    //                  ZOHO_REFRESH_TOKEN / ZOHO_ROOT_ID are ALL present in env.
    //                  Missing vars → connector is skipped with a warning, never a panic.
    let mut connectors: Vec<Box<dyn SourceConnector>> =
        vec![Box::new(connector::graph::GraphClient::new(
            args.drive_id.clone(),
            args.tenant_id.clone(),
            args.client_id.clone(),
            args.client_secret.clone(),
        ))];

    // Zoho WorkDrive connector (optional — requires env vars at runtime).
    match build_zoho_connector() {
        Ok(Some(zoho)) => {
            info!("Zoho WorkDrive connector configured");
            connectors.push(Box::new(zoho));
        }
        Ok(None) => {
            info!("Zoho WorkDrive connector skipped — ZOHO_* env vars not set");
        }
        Err(e) => {
            warn!("Zoho WorkDrive connector failed to initialize — skipping: {e}");
        }
    }

    let embed = embed::EmbedClient::new(&args.tei_base_url, args.embed_batch_size);
    let chunker = chunker::Chunker::new();

    info!(
        "Ingestion daemon started — {} connector(s), connector_id={}",
        connectors.len(),
        args.drive_id
    );

    loop {
        for connector in &connectors {
            match run_cycle(
                &pool,
                connector.as_ref(),
                &embed,
                &chunker,
                &args,
                &content_key,
            )
            .await
            {
                Ok(n) => info!(
                    "Cycle complete — connector_id={} {} document(s) processed",
                    connector.id(),
                    n
                ),
                Err(e) => error!("Cycle failed for connector_id={}: {e:#}", connector.id()),
            }
        }
        if args.once {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_secs(args.poll_interval_secs)).await;
    }

    Ok(())
}

async fn run_cycle(
    pool: &sqlx::PgPool,
    connector: &dyn SourceConnector,
    embed: &embed::EmbedClient,
    chunker: &chunker::Chunker,
    args: &Args,
    content_key: &[u8; 32],
) -> Result<usize> {
    let connector_id = connector.id();

    let cursor = state::load_cursor(pool, connector_id)
        .await
        .context("failed to load delta cursor")?;

    let delta = connector
        .list_changes(cursor.as_deref())
        .await
        .map_err(|e| anyhow::anyhow!("connector list_changes failed: {e}"))?;

    let mut processed = 0usize;

    for item in &delta.changes {
        if item.deleted {
            info!(
                "Removing chunks for deleted item remote_id={}",
                item.remote_id
            );
            if let Err(e) = db::delete_chunks(pool, &item.remote_id).await {
                error!(
                    "Failed to delete chunks for remote_id={}: {e:#}",
                    item.remote_id
                );
            }
            continue;
        }

        info!("Ingesting remote_id={} name={}", item.remote_id, item.name);

        let content = match connector.download(item).await {
            Ok(c) => c,
            Err(e) => {
                error!("Download failed for remote_id={}: {e}", item.remote_id);
                continue;
            }
        };

        let text = extract_text(&content, &item.name);
        if text.is_empty() {
            continue;
        }

        let chunks = match chunker.chunk(&text) {
            Ok(c) if !c.is_empty() => c,
            Ok(_) => continue,
            Err(e) => {
                error!("Chunking failed for remote_id={}: {e:#}", item.remote_id);
                continue;
            }
        };

        let encrypted: Vec<Vec<u8>> = match chunks
            .iter()
            .map(|c| crypto::encrypt(content_key, c.as_bytes()))
            .collect()
        {
            Ok(v) => v,
            Err(e) => {
                error!("Encryption failed for remote_id={}: {e:#}", item.remote_id);
                continue;
            }
        };

        let embeddings = match embed.embed_batch(&chunks).await {
            Ok(e) => e,
            Err(e) => {
                error!("Embedding failed for remote_id={}: {e:#}", item.remote_id);
                continue;
            }
        };

        if let Err(e) = db::upsert_chunks(
            pool,
            &item.remote_id,
            &item.name,
            &args.classification,
            &encrypted,
            &embeddings,
        )
        .await
        {
            error!(
                "upsert_chunks failed for remote_id={}: {e:#}",
                item.remote_id
            );
            continue;
        }

        processed += 1;
    }

    // Persist new delta cursor (even if zero documents processed — cursor still advances).
    if let Some(ref cursor_str) = delta.next_cursor {
        state::save_cursor(pool, connector_id, cursor_str)
            .await
            .context("failed to save delta cursor")?;
    }

    Ok(processed)
}

// Build a Zoho WorkDrive connector from environment variables.
//
// Returns:
//   Ok(Some(client)) — all required vars present and connector initialized.
//   Ok(None)         — none of the ZOHO_* vars are set; skip silently.
//   Err(e)           — partial config or initialization error; log and skip.
fn build_zoho_connector() -> anyhow::Result<Option<ZohoClient>> {
    let client_id = std::env::var("ZOHO_CLIENT_ID").ok();
    let client_secret = std::env::var("ZOHO_CLIENT_SECRET").ok();
    let refresh_token = std::env::var("ZOHO_REFRESH_TOKEN").ok();
    let root_id = std::env::var("ZOHO_ROOT_ID").ok();

    // If none of the vars are set, skip quietly.
    if client_id.is_none() && client_secret.is_none() && refresh_token.is_none() {
        return Ok(None);
    }

    let client_id = client_id
        .ok_or_else(|| anyhow::anyhow!("ZOHO_CLIENT_ID not set — skipping zoho connector"))?;
    let client_secret = client_secret
        .ok_or_else(|| anyhow::anyhow!("ZOHO_CLIENT_SECRET not set — skipping zoho connector"))?;
    let refresh_token = refresh_token
        .ok_or_else(|| anyhow::anyhow!("ZOHO_REFRESH_TOKEN not set — skipping zoho connector"))?;
    let root_id =
        root_id.ok_or_else(|| anyhow::anyhow!("ZOHO_ROOT_ID not set — skipping zoho connector"))?;
    let dc = std::env::var("ZOHO_DC").unwrap_or_else(|_| "eu".into());
    let connector_id =
        std::env::var("ZOHO_CONNECTOR_ID").unwrap_or_else(|_| format!("zoho:{}", root_id));

    let creds = ZohoCredentials {
        client_id,
        client_secret,
        refresh_token,
        dc,
    };
    let client = ZohoClient::new(creds, connector_id, root_id)?;
    Ok(Some(client))
}

// Extract plain text from raw file bytes based on filename extension.
//
// .txt / .md / .rst → UTF-8 direct (lossy decode).
// .pdf / .docx / .doc → skipped with a warning (TODO: add extraction in a future phase).
// Unknown → attempt UTF-8; skip silently if binary.
fn extract_text(content: &[u8], filename: &str) -> String {
    let lower = filename.to_lowercase();
    if lower.ends_with(".txt") || lower.ends_with(".md") || lower.ends_with(".rst") {
        String::from_utf8_lossy(content).into_owned()
    } else if lower.ends_with(".pdf")
        || lower.ends_with(".docx")
        || lower.ends_with(".doc")
        || lower.ends_with(".pptx")
    {
        warn!("Skipping {filename}: binary format extraction not yet implemented");
        String::new()
    } else {
        // Attempt UTF-8 for CSV, HTML, JSON, etc.
        match std::str::from_utf8(content) {
            Ok(s) => s.to_string(),
            Err(_) => String::new(),
        }
    }
}
