// SourceConnector trait — abstracts over Microsoft Graph, Zoho WorkDrive, etc.
//
// Every source connector must implement this trait so the ingestion daemon can
// drive the download/delta pipeline generically.  The GraphClient in graph.rs
// is the first implementation; ZohoClient in zoho.rs is the second.

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use thiserror::Error;

pub mod graph;
pub mod zoho;

/// A single changed or deleted item reported by a source connector's delta feed.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChangeItem {
    /// Stable identifier for this item within the connector's namespace.
    pub remote_id: String,
    /// Identifier of the parent folder, if known.
    pub parent_id: Option<String>,
    /// Display name of the item (filename or folder name).
    pub name: String,
    /// True if this item is a folder (no downloadable content).
    pub is_folder: bool,
    /// RFC 3339 last-modified timestamp, if available.
    pub modified_at: Option<String>,
    /// True if the item has been deleted on the remote side.
    pub deleted: bool,
    /// Raw JSON envelope from the connector — preserved for debugging/auditing.
    pub raw: Value,
}

/// Result of one page/batch of changes from a source connector's delta feed.
#[derive(Debug, Clone)]
pub struct DeltaResult {
    /// Opaque cursor to pass on the next call.  None when the connector has
    /// no concept of a cursor (unlikely in practice, but valid).
    pub next_cursor: Option<String>,
    /// Changes detected since the previous cursor.
    pub changes: Vec<ChangeItem>,
    /// True if there are more pages of changes available right now.
    /// The caller must keep calling list_changes until more == false.
    // NOTE: not read by main.rs yet — the daemon calls list_changes repeatedly
    // until the cursor stops changing.  The field is part of the public trait
    // contract and is set correctly by all connectors.
    #[allow(dead_code)]
    pub more: bool,
}

/// Classified OAuth failure kind — never carries raw server response data.
///
/// Callers use this enum to decide whether to retry, back off, or abort:
/// - `InvalidCode`  → FATAL: the refresh token has been revoked; operator must re-authorize.
/// - `AccessDenied` → RECOVERABLE: transient refusal; back off and retry.
/// - `InvalidClient`→ CONFIG: wrong client_id / client_secret; abort the daemon.
/// - `Unexpected`   → catch-all, treated as RECOVERABLE.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthErrorKind {
    /// The refresh token is no longer valid.  Operator must re-authorize.
    InvalidCode,
    /// Transient access denial.  Backoff and retry.
    AccessDenied,
    /// Wrong client credentials.  Configuration error; abort.
    InvalidClient,
    /// Unclassified auth failure.  Treated as recoverable.
    Unexpected,
}

/// Error type returned by SourceConnector methods.
#[derive(Debug, Error)]
pub enum ConnectorError {
    #[error("http error: status={status}")]
    Http { status: u16 },
    #[error("auth error: {0:?}")]
    Auth(AuthErrorKind),
    #[error("cursor invalid")]
    CursorInvalid,
    #[error("throttled — retry after {retry_after_secs}s")]
    Throttled { retry_after_secs: u64 },
    #[error("other: {0}")]
    Other(String),
}

/// Abstraction over any remote document store.
///
/// Every source connector must be Send + Sync + 'static so it can live in an
/// Arc held across tokio tasks.
#[async_trait]
pub trait SourceConnector: Send + Sync + 'static {
    /// Stable identifier for this connector instance (e.g., a UUID or a drive
    /// ID string).  Persisted in ingestion_state.connector_id.
    fn id(&self) -> &str;

    /// Kind label for this connector (e.g., "microsoft_graph", "zoho_workdrive").
    /// Persisted in ingestion_state.connector_kind.
    // Called by multi-connector dispatch in PR3/D2; allowed here until then.
    #[allow(dead_code)]
    fn kind(&self) -> &str;

    /// Fetch the next batch of changes since the given opaque cursor.
    /// Returns an empty changes vec with more=false when there is nothing new.
    async fn list_changes(&self, cursor: Option<&str>) -> Result<DeltaResult, ConnectorError>;

    /// Download the bytes of a single file referenced by a ChangeItem.
    async fn download(&self, item: &ChangeItem) -> Result<Vec<u8>, ConnectorError>;
}
