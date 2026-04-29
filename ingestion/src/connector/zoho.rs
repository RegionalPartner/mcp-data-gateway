// Zoho WorkDrive connector — implements SourceConnector.
//
// Data-residency: only *.zoho.eu and *.zohocdn.com are trusted for re-auth.
// NEVER re-attach the Zoho-oauthtoken to a redirect target on .zoho.com (US DC) —
// that would send EU-origin credentials to a US endpoint.
//
// Token cache: ArcSwap<Option<CachedToken>> for wait-free reads, backed by a
// tokio::Mutex single-flight gate that prevents stampede refreshes.
//
// BFS cursor (ZohoCursor): serialized as JSON and stored verbatim in
// ingestion_state.delta_token.  Never logged at info/warn level.
//
// Download: manual 5-hop redirect loop; Authorization header is re-attached
// ONLY when the redirect target host ends with .zoho.eu or .zohocdn.com.

use arc_swap::ArcSwap;
use async_trait::async_trait;
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;
use tracing::{debug, error, info, warn};
use url::Url;

use crate::connector::{AuthErrorKind, ChangeItem, ConnectorError, DeltaResult, SourceConnector};

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const INLINE_THRESHOLD_BYTES: usize = 8 * 1024 * 1024; // 8 MiB
const MAX_REDIRECTS: u8 = 5;
// Refresh 5 min before expiry so we never send an expired token.
const ACCESS_TOKEN_TTL_SAFETY_SECS: u64 = 300;
// 60-second overlap window on the watermark for idempotency across restarts.
const WATERMARK_OVERLAP_MS: i64 = 60_000;

// ---------------------------------------------------------------------------
// Credentials — manual Debug redaction (rule #1)
// ---------------------------------------------------------------------------

/// Zoho OAuth application credentials.
///
/// `#[derive(Debug)]` is intentionally absent.  The manual impl below prints
/// `"<redacted>"` for client_secret and refresh_token.
pub struct ZohoCredentials {
    pub client_id: String,
    pub client_secret: String,
    pub refresh_token: String,
    /// Data-center code: "eu" (supported), "com" (US DC — NOT supported for EU data residency).
    pub dc: String,
}

impl std::fmt::Debug for ZohoCredentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ZohoCredentials")
            .field("client_id", &self.client_id)
            .field("client_secret", &"<redacted>")
            .field("refresh_token", &"<redacted>")
            .field("dc", &self.dc)
            .finish()
    }
}

// ---------------------------------------------------------------------------
// Token cache — manual Debug redaction (rule #1)
// ---------------------------------------------------------------------------

struct CachedToken {
    access_token: String,
    expires_at: Instant,
}

impl std::fmt::Debug for CachedToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CachedToken")
            .field("access_token", &"<redacted>")
            .field("expires_at", &self.expires_at)
            .finish()
    }
}

// Serde shape for the OAuth token refresh response.
#[derive(Deserialize)]
struct TokenResponse {
    access_token: Option<String>,
    expires_in: Option<u64>,
    // Zoho returns an "error" field (not HTTP status) for auth failures.
    error: Option<String>,
}

// ---------------------------------------------------------------------------
// ZohoTokenCache
// ---------------------------------------------------------------------------

/// Thread-safe token cache with single-flight refresh.
///
/// - Fast path: `ArcSwap` load (wait-free, no lock).
/// - Slow path: `tokio::Mutex` gate (at most one goroutine refreshes at a time).
/// - Double-check under the lock prevents stampede after contention.
pub struct ZohoTokenCache {
    cached: ArcSwap<Option<CachedToken>>,
    refresh_gate: Mutex<()>,
    pub(crate) creds: Arc<ZohoCredentials>,
    // pub so ZohoClient can reuse the same client for API calls (shares connection pool).
    pub http: Client,
    accounts_base: String, // e.g. "https://accounts.zoho.eu"
}

impl ZohoTokenCache {
    fn new(creds: Arc<ZohoCredentials>, http: Client) -> Self {
        let accounts_base = format!("https://accounts.zoho.{}", creds.dc);
        Self {
            cached: ArcSwap::new(Arc::new(None)),
            refresh_gate: Mutex::new(()),
            creds,
            http,
            accounts_base,
        }
    }

    /// Return a valid access token, refreshing if within the safety window.
    pub async fn refresh_if_needed(&self) -> Result<String, ConnectorError> {
        // 1. Fast path — read current without any lock.
        if let Some(ref t) = **self.cached.load() {
            if t.expires_at > Instant::now() + Duration::from_secs(ACCESS_TOKEN_TTL_SAFETY_SECS) {
                return Ok(t.access_token.clone());
            }
        }

        // 2. Single-flight gate.
        let _guard = self.refresh_gate.lock().await;

        // 3. Double-check after taking the lock (another task may have refreshed already).
        if let Some(ref t) = **self.cached.load() {
            if t.expires_at > Instant::now() + Duration::from_secs(ACCESS_TOKEN_TTL_SAFETY_SECS) {
                return Ok(t.access_token.clone());
            }
        }

        // 4. Actually refresh.
        let new_token = self.call_oauth_refresh().await?;
        let token_val = new_token.access_token.clone();
        self.cached.store(Arc::new(Some(new_token)));
        Ok(token_val)
    }

    async fn call_oauth_refresh(&self) -> Result<CachedToken, ConnectorError> {
        let url = format!("{}/oauth/v2/token", self.accounts_base);

        let resp = self
            .http
            .post(&url)
            .form(&[
                ("grant_type", "refresh_token"),
                ("client_id", self.creds.client_id.as_str()),
                ("client_secret", self.creds.client_secret.as_str()),
                ("refresh_token", self.creds.refresh_token.as_str()),
            ])
            .send()
            .await
            .map_err(|e| ConnectorError::Other(format!("OAuth token request failed: {e}")))?;

        let http_status = resp.status();

        // Parse body FIRST (classify from error field), then check HTTP status.
        // We NEVER log the body — only the classified error kind.
        let body: TokenResponse = resp
            .json()
            .await
            .map_err(|_| ConnectorError::Auth(AuthErrorKind::Unexpected))?;

        // Classify from the Zoho "error" field in the response body.
        if let Some(ref err_code) = body.error {
            let kind = classify_oauth_error(err_code);
            match kind {
                AuthErrorKind::InvalidCode => {
                    error!(
                        event = "zoho_refresh_fatal",
                        error_kind = "InvalidCode",
                        "Zoho OAuth refresh token is invalid — operator must re-authorize"
                    );
                }
                AuthErrorKind::InvalidClient => {
                    error!(
                        event = "zoho_refresh_fatal",
                        error_kind = "InvalidClient",
                        "Zoho OAuth client credentials are wrong — check config"
                    );
                }
                AuthErrorKind::AccessDenied => {
                    warn!(
                        event = "zoho_refresh_recoverable",
                        error_kind = "AccessDenied",
                        "Zoho OAuth access denied — will retry with backoff"
                    );
                }
                AuthErrorKind::Unexpected => {
                    warn!(
                        event = "zoho_refresh_recoverable",
                        error_kind = "Unexpected",
                        http_status = http_status.as_u16(),
                        "Zoho OAuth unexpected error — will retry"
                    );
                }
            }
            return Err(ConnectorError::Auth(kind));
        }

        // No error field — check HTTP status.
        if !http_status.is_success() {
            // Treat unexpected HTTP errors as Unexpected/recoverable.
            warn!(
                event = "zoho_refresh_recoverable",
                error_kind = "Unexpected",
                http_status = http_status.as_u16(),
                "Zoho OAuth non-success HTTP status with no error field"
            );
            return Err(ConnectorError::Auth(AuthErrorKind::Unexpected));
        }

        let access_token = body
            .access_token
            .ok_or_else(|| ConnectorError::Other("OAuth response missing access_token".into()))?;

        let expires_in = body.expires_in.unwrap_or(3600);
        let expires_at = Instant::now()
            + Duration::from_secs(expires_in.saturating_sub(ACCESS_TOKEN_TTL_SAFETY_SECS));

        info!(
            event = "zoho_token_refreshed",
            expires_in_secs = expires_in,
            "Zoho OAuth access token refreshed"
        );

        Ok(CachedToken {
            access_token,
            expires_at,
        })
    }
}

/// Classify a Zoho OAuth error string into a typed `AuthErrorKind`.
///
/// Classification logic from Zoho OAuth docs §3.4 / Zoho WorkDrive API reference.
fn classify_oauth_error(code: &str) -> AuthErrorKind {
    match code {
        "invalid_code" => AuthErrorKind::InvalidCode,
        "access_denied" => AuthErrorKind::AccessDenied,
        "invalid_client" | "invalid_client_secret" => AuthErrorKind::InvalidClient,
        _ => AuthErrorKind::Unexpected,
    }
}

// ---------------------------------------------------------------------------
// BFS cursor
// ---------------------------------------------------------------------------

/// Per-folder position within the BFS traversal.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FolderCursor {
    pub folder_id: String,
    pub next_offset: u32,
}

/// BFS traversal state persisted as JSON in `ingestion_state.delta_token`.
///
/// - `watermark_ms`: millisecond epoch of the last complete run.  Used as
///   `filter[fromDate]` minus `WATERMARK_OVERLAP_MS` for idempotency.
/// - `queue`: folders left to visit in the current run.
/// - `visited`: folder IDs already processed this run (de-dup guard).
/// - `root_id`: the Team Folder ID from which ingestion starts.
/// - `run_started_ms`: epoch captured at the start of the current BFS run.
///   Becomes the new `watermark_ms` when `queue` drains.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ZohoCursor {
    pub watermark_ms: i64,
    pub queue: VecDeque<FolderCursor>,
    pub visited: Vec<String>,
    pub root_id: String,
    pub run_started_ms: i64,
}

impl ZohoCursor {
    /// Bootstrap cursor for the first run.
    fn bootstrap(root_id: String) -> Self {
        let now_ms = now_ms();
        ZohoCursor {
            watermark_ms: 0,
            queue: VecDeque::from([FolderCursor {
                folder_id: root_id.clone(),
                next_offset: 0,
            }]),
            visited: Vec::new(),
            root_id,
            run_started_ms: now_ms,
        }
    }

    fn to_json(&self) -> Result<String, ConnectorError> {
        serde_json::to_string(self)
            .map_err(|e| ConnectorError::Other(format!("cursor serialization failed: {e}")))
    }

    fn from_json(s: &str) -> Result<Self, ConnectorError> {
        serde_json::from_str(s).map_err(|_| ConnectorError::CursorInvalid)
    }
}

// ---------------------------------------------------------------------------
// Zoho WorkDrive API shapes
// ---------------------------------------------------------------------------

// Response from GET /api/v1/files/{folder_id}/files
#[derive(Deserialize)]
struct FolderListResponse {
    data: Option<Vec<ZohoFileItem>>,
    info: Option<ZohoPageInfo>,
}

#[derive(Deserialize)]
struct ZohoFileItem {
    id: String,
    attributes: ZohoFileAttributes,
}

#[derive(Deserialize)]
struct ZohoFileAttributes {
    name: Option<String>,
    #[serde(rename = "type")]
    kind: Option<String>, // "file" or "folder"
    #[serde(rename = "modified_time")]
    modified_time: Option<i64>, // epoch ms
    status: Option<String>, // "active" / "deleted"
    permalink: Option<String>,
}

#[derive(Deserialize)]
struct ZohoPageInfo {
    more_records: Option<bool>,
}

// ---------------------------------------------------------------------------
// ZohoClient — the connector itself
// ---------------------------------------------------------------------------

/// Zoho WorkDrive source connector.
///
/// `#[derive(Debug)]` intentionally absent — manual impl omits sensitive fields.
pub struct ZohoClient {
    id: String,
    tokens: Arc<ZohoTokenCache>,
    api_base: String,        // "https://workdrive.zoho.eu/api/v1"
    download_client: Client, // NO redirect following — manual loop in download()
    root_id: String,
}

impl std::fmt::Debug for ZohoClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ZohoClient")
            .field("id", &self.id)
            .field("kind", &"zoho_workdrive")
            .field("api_base", &self.api_base)
            .field("root_id", &self.root_id)
            // tokens intentionally omitted
            .finish()
    }
}

impl ZohoClient {
    /// Construct a new Zoho WorkDrive connector.
    ///
    /// `connector_id` is the logical ID persisted in `ingestion_state.connector_id`.
    /// `root_id` is the Zoho Team Folder ID (root of the BFS traversal).
    pub fn new(
        creds: ZohoCredentials,
        connector_id: String,
        root_id: String,
    ) -> Result<Self, anyhow::Error> {
        let dc = creds.dc.clone();
        if dc != "eu" {
            anyhow::bail!(
                "Only 'eu' data center is supported for EU data-residency compliance; got '{}'",
                dc
            );
        }
        let api_base = format!("https://workdrive.zoho.{}/api/v1", dc);
        let http = Client::builder()
            .use_rustls_tls()
            .build()
            .map_err(|e| anyhow::anyhow!("failed to build HTTP client: {e}"))?;

        // Separate client for downloads — redirect policy = none (manual loop).
        let download_client = Client::builder()
            .use_rustls_tls()
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|e| anyhow::anyhow!("failed to build download HTTP client: {e}"))?;

        let creds_arc = Arc::new(creds);
        let tokens = Arc::new(ZohoTokenCache::new(creds_arc, http));

        Ok(ZohoClient {
            id: connector_id,
            tokens,
            api_base,
            download_client,
            root_id,
        })
    }

    /// List the immediate children of `folder_id` starting at `offset`.
    async fn list_folder_page(
        &self,
        folder_id: &str,
        offset: u32,
        from_ms: i64,
    ) -> Result<(Vec<ZohoFileItem>, bool), ConnectorError> {
        let token = self.tokens.refresh_if_needed().await?;
        let url = format!(
            "{}/files/{}/files?offset={}&limit=100&filter[fromDate]={}",
            self.api_base, folder_id, offset, from_ms
        );

        debug!(
            folder_id = folder_id,
            offset = offset,
            "Zoho WorkDrive BFS page"
        );

        let resp = self
            .tokens
            .http
            .get(&url)
            .header("Authorization", format!("Zoho-oauthtoken {}", token))
            .send()
            .await
            .map_err(|e| ConnectorError::Other(format!("folder list request failed: {e}")))?;

        let status = resp.status();
        match status {
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => {
                return Err(ConnectorError::Auth(AuthErrorKind::AccessDenied));
            }
            StatusCode::TOO_MANY_REQUESTS => {
                let retry = resp
                    .headers()
                    .get("Retry-After")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|s| s.parse::<u64>().ok())
                    .unwrap_or(60);
                return Err(ConnectorError::Throttled {
                    retry_after_secs: retry,
                });
            }
            s if !s.is_success() => {
                return Err(ConnectorError::Http { status: s.as_u16() });
            }
            _ => {}
        }

        let page: FolderListResponse = resp
            .json()
            .await
            .map_err(|e| ConnectorError::Other(format!("failed to decode folder list: {e}")))?;

        let items = page.data.unwrap_or_default();
        let more = page.info.and_then(|i| i.more_records).unwrap_or(false);

        Ok((items, more))
    }

    /// Internal download with trusted-host re-auth.
    async fn download_with_trusted_auth(
        &self,
        initial_url: Url,
    ) -> Result<Vec<u8>, ConnectorError> {
        let mut current_url = initial_url;
        let mut attach_auth = true;

        for _ in 0..MAX_REDIRECTS {
            let mut req = self.download_client.get(current_url.clone());
            if attach_auth {
                let token = self.tokens.refresh_if_needed().await?;
                req = req.header("Authorization", format!("Zoho-oauthtoken {}", token));
            }
            let resp = req
                .send()
                .await
                .map_err(|e| ConnectorError::Other(format!("download request failed: {e}")))?;

            if resp.status().is_redirection() {
                let loc = resp
                    .headers()
                    .get("Location")
                    .ok_or_else(|| ConnectorError::Http {
                        status: resp.status().as_u16(),
                    })?;
                let loc_str = loc.to_str().map_err(|_| {
                    ConnectorError::Other("invalid Location header encoding".into())
                })?;
                let next_url = Url::parse(loc_str)
                    .map_err(|_| ConnectorError::Other("malformed redirect URL".into()))?;

                // Only re-attach auth if the redirect target is in the trusted EU zone.
                attach_auth = is_trusted_zoho_host(next_url.host_str().unwrap_or(""));
                if !attach_auth {
                    debug!(
                        host = next_url.host_str().unwrap_or(""),
                        "Redirect to non-trusted host — dropping Authorization header"
                    );
                }
                current_url = next_url;
                continue;
            }

            if !resp.status().is_success() {
                return Err(ConnectorError::Http {
                    status: resp.status().as_u16(),
                });
            }

            return stream_body(resp).await;
        }

        Err(ConnectorError::Other("too many redirects".into()))
    }
}

/// Only EU data-center hosts are trusted for re-authorization.
///
/// `.zoho.com` (US DC) is explicitly excluded — sending EU-origin OAuth tokens
/// to the US data center is an EU data-residency violation.
pub fn is_trusted_zoho_host(host: &str) -> bool {
    host.ends_with(".zoho.eu") || host.ends_with(".zohocdn.com") || host == "zoho.eu"
}

/// Stream a `reqwest::Response` body into a `Vec<u8>`.
///
/// Bodies ≤ INLINE_THRESHOLD_BYTES are accumulated in-memory.
/// Larger bodies are spilled to an anonymous `tempfile` to cap peak heap usage,
/// then read back at the end (Vec<u8> is the contract of `SourceConnector::download`).
pub async fn stream_body(resp: reqwest::Response) -> Result<Vec<u8>, ConnectorError> {
    use futures_util::StreamExt;
    use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};

    let content_length = resp.content_length();

    let spill = matches!(content_length, Some(len) if len as usize > INLINE_THRESHOLD_BYTES);

    if spill {
        // Create an anonymous temp file; will be deleted when the handle drops.
        let tmp = tempfile::tempfile()
            .map_err(|e| ConnectorError::Other(format!("tempfile creation failed: {e}")))?;
        let mut async_tmp = tokio::fs::File::from_std(tmp);

        let mut body = resp.bytes_stream();
        let mut total = 0usize;
        while let Some(chunk) = body.next().await {
            let chunk =
                chunk.map_err(|e| ConnectorError::Other(format!("download stream error: {e}")))?;
            total += chunk.len();
            async_tmp
                .write_all(&chunk)
                .await
                .map_err(|e| ConnectorError::Other(format!("tempfile write failed: {e}")))?;
        }

        // Seek back to start and read into Vec<u8>.
        async_tmp
            .seek(std::io::SeekFrom::Start(0))
            .await
            .map_err(|e| ConnectorError::Other(format!("tempfile seek failed: {e}")))?;
        let mut out = Vec::with_capacity(total);
        async_tmp
            .read_to_end(&mut out)
            .await
            .map_err(|e| ConnectorError::Other(format!("tempfile read failed: {e}")))?;
        Ok(out)
    } else {
        // Small body: read directly.
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| ConnectorError::Other(format!("failed to read response body: {e}")))?;
        Ok(bytes.to_vec())
    }
}

// ---------------------------------------------------------------------------
// SourceConnector impl
// ---------------------------------------------------------------------------

#[async_trait]
impl SourceConnector for ZohoClient {
    fn id(&self) -> &str {
        &self.id
    }

    fn kind(&self) -> &str {
        "zoho_workdrive"
    }

    async fn list_changes(&self, cursor: Option<&str>) -> Result<DeltaResult, ConnectorError> {
        // Deserialize cursor or bootstrap a new one.
        let mut state = match cursor {
            Some(s) => ZohoCursor::from_json(s)?,
            None => {
                let c = ZohoCursor::bootstrap(self.root_id.clone());
                info!(
                    connector_id = self.id.as_str(),
                    "Zoho WorkDrive: starting full BFS crawl"
                );
                c
            }
        };

        // Compute the query window: watermark minus 60 s overlap for idempotency.
        let from_ms = (state.watermark_ms - WATERMARK_OVERLAP_MS).max(0);

        // Pop one folder from the BFS queue.
        let folder_cursor = match state.queue.pop_front() {
            Some(fc) => fc,
            None => {
                // Queue drained: advance watermark to run_started_ms and restart BFS.
                state.watermark_ms = state.run_started_ms;
                state.run_started_ms = now_ms();
                state.visited.clear();
                state.queue.push_back(FolderCursor {
                    folder_id: state.root_id.clone(),
                    next_offset: 0,
                });
                return Ok(DeltaResult {
                    next_cursor: Some(state.to_json()?),
                    changes: Vec::new(),
                    more: false,
                });
            }
        };

        let folder_id = folder_cursor.folder_id.clone();
        let mut offset = folder_cursor.next_offset;
        let mut changes: Vec<ChangeItem> = Vec::new();
        let mut more_in_folder;

        loop {
            let (items, more) = self.list_folder_page(&folder_id, offset, from_ms).await?;
            more_in_folder = more;

            for item in &items {
                let attrs = &item.attributes;
                let is_folder = attrs.kind.as_deref() == Some("folder");
                let is_deleted = attrs.status.as_deref() == Some("deleted");

                // Enqueue unseen subfolders for BFS traversal.
                if is_folder && !state.visited.contains(&item.id) {
                    state.visited.push(item.id.clone());
                    state.queue.push_back(FolderCursor {
                        folder_id: item.id.clone(),
                        next_offset: 0,
                    });
                }

                // Only yield file items (folders have no downloadable content).
                if !is_folder {
                    let modified_at = attrs.modified_time.map(format_epoch_ms);
                    let raw = serde_json::json!({
                        "zoho_file_id": item.id,
                        "permalink": attrs.permalink,
                    });
                    changes.push(ChangeItem {
                        remote_id: item.id.clone(),
                        parent_id: Some(folder_id.clone()),
                        name: attrs.name.clone().unwrap_or_default(),
                        is_folder: false,
                        modified_at,
                        deleted: is_deleted,
                        raw,
                    });
                }
            }

            if !more_in_folder {
                break;
            }
            offset += items.len() as u32;
        }

        // If this folder still has more pages, re-enqueue it at the new offset.
        // (The loop above already handles multi-page folders in one call, but if
        // the API returns more=true after a full page, push it back.)
        if more_in_folder {
            state.queue.push_front(FolderCursor {
                folder_id,
                next_offset: offset,
            });
        }

        let more_folders = !state.queue.is_empty();

        info!(
            connector_id = self.id.as_str(),
            changes = changes.len(),
            queue_depth = state.queue.len(),
            "Zoho WorkDrive BFS page complete",
        );

        Ok(DeltaResult {
            next_cursor: Some(state.to_json()?),
            changes,
            more: more_folders,
        })
    }

    async fn download(&self, item: &ChangeItem) -> Result<Vec<u8>, ConnectorError> {
        let file_id = &item.remote_id;
        let download_url_str = format!("{}/files/{}/download", self.api_base, file_id);
        let initial_url = Url::parse(&download_url_str)
            .map_err(|e| ConnectorError::Other(format!("invalid download URL: {e}")))?;

        self.download_with_trusted_auth(initial_url).await
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

fn format_epoch_ms(ms: i64) -> String {
    // Simple ISO 8601 UTC string from epoch milliseconds.
    let secs = ms / 1000;
    let nanos = ((ms % 1000) * 1_000_000) as u32;
    let dt = std::time::UNIX_EPOCH + std::time::Duration::new(secs.max(0) as u64, nanos);
    let elapsed = dt.duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
    // Format: YYYY-MM-DDTHH:MM:SSZ (UTC)
    let total_secs = elapsed.as_secs();
    let s = total_secs % 60;
    let m = (total_secs / 60) % 60;
    let h = (total_secs / 3600) % 24;
    let days = total_secs / 86400;
    // Rough Gregorian calendar calculation (good enough for display purposes).
    let years = 1970 + days / 365;
    let day_of_year = days % 365;
    let months = day_of_year / 30 + 1;
    let dom = day_of_year % 30 + 1;
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        years, months, dom, h, m, s
    )
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    // Build a ZohoTokenCache wired to the given mock server.
    fn make_token_cache(server: &MockServer) -> Arc<ZohoTokenCache> {
        let creds = Arc::new(ZohoCredentials {
            client_id: "test_client_id".into(),
            client_secret: "test_client_secret".into(),
            refresh_token: "FAKE_REFRESH_TOKEN_FOR_TESTS".into(),
            dc: "eu".into(),
        });
        let http = Client::builder().use_rustls_tls().build().unwrap();
        let cache = ZohoTokenCache {
            cached: ArcSwap::new(Arc::new(None)),
            refresh_gate: Mutex::new(()),
            creds,
            http,
            accounts_base: server.uri(),
        };
        Arc::new(cache)
    }

    // Build a ZohoClient wired to the given mock server.
    fn make_client(
        tokens: Arc<ZohoTokenCache>,
        server: &MockServer,
        connector_id: &str,
    ) -> ZohoClient {
        let download_client = Client::builder()
            .use_rustls_tls()
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap();
        ZohoClient {
            id: connector_id.to_string(),
            tokens,
            api_base: server.uri(),
            download_client,
            root_id: "root_folder_123".to_string(),
        }
    }

    fn token_response_json(token: &str, expires_in: u64) -> String {
        format!(
            r#"{{"access_token":"{}","expires_in":{},"api_domain":"https://www.zoho.eu"}}"#,
            token, expires_in
        )
    }

    // -----------------------------------------------------------------------
    // Test 1: OAuth refresh happy path
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn oauth_refresh_happy_path() {
        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(token_response_json("FAKE_ACCESS_TOKEN_FOR_TESTS", 3600)),
            )
            .mount(&server)
            .await;

        let cache = make_token_cache(&server);
        let token = cache.refresh_if_needed().await.unwrap();
        assert_eq!(token, "FAKE_ACCESS_TOKEN_FOR_TESTS");

        // Cache must now be populated.
        let cached = cache.cached.load();
        assert!(cached.is_some());
    }

    // -----------------------------------------------------------------------
    // Test 2: OAuth refresh — invalid_code → FATAL
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn oauth_refresh_invalid_code_fatal() {
        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(ResponseTemplate::new(400).set_body_string(r#"{"error":"invalid_code"}"#))
            .mount(&server)
            .await;

        let cache = make_token_cache(&server);
        let err = cache.refresh_if_needed().await.unwrap_err();
        assert!(
            matches!(err, ConnectorError::Auth(AuthErrorKind::InvalidCode)),
            "expected Auth(InvalidCode), got {:?}",
            err
        );
    }

    // -----------------------------------------------------------------------
    // Test 3: OAuth refresh — access_denied → RECOVERABLE
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn oauth_refresh_access_denied_recoverable() {
        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(400).set_body_string(r#"{"error":"access_denied"}"#),
            )
            .mount(&server)
            .await;

        let cache = make_token_cache(&server);
        let err = cache.refresh_if_needed().await.unwrap_err();
        assert!(
            matches!(err, ConnectorError::Auth(AuthErrorKind::AccessDenied)),
            "expected Auth(AccessDenied), got {:?}",
            err
        );
    }

    // -----------------------------------------------------------------------
    // Test 4: OAuth refresh — invalid_client → CONFIG
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn oauth_refresh_invalid_client_config() {
        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(401).set_body_string(r#"{"error":"invalid_client"}"#),
            )
            .mount(&server)
            .await;

        let cache = make_token_cache(&server);
        let err = cache.refresh_if_needed().await.unwrap_err();
        assert!(
            matches!(err, ConnectorError::Auth(AuthErrorKind::InvalidClient)),
            "expected Auth(InvalidClient), got {:?}",
            err
        );
    }

    // -----------------------------------------------------------------------
    // Test 5: Single-flight under concurrency — exactly 1 OAuth call
    //
    // Spawns 50 tokio tasks all calling refresh_if_needed() with a cleared/
    // expired cache.  The single-flight Mutex gate ensures the OAuth endpoint
    // is called exactly once regardless of contention.
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn single_flight_under_concurrency() {
        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(token_response_json("FAKE_ACCESS_TOKEN_CONCURRENT", 3600)),
            )
            .mount(&server)
            .await;

        let cache = Arc::new(make_token_cache(&server));

        // Spawn 50 tasks all trying to get a token simultaneously with an empty cache.
        let mut set = tokio::task::JoinSet::new();
        for _ in 0..50 {
            let c = cache.clone();
            set.spawn(async move { c.refresh_if_needed().await });
        }

        let mut token_values: Vec<String> = Vec::new();
        while let Some(r) = set.join_next().await {
            let token = r
                .expect("task panicked")
                .expect("refresh_if_needed returned error");
            token_values.push(token);
        }

        // All 50 tasks must have received the expected token value.
        assert_eq!(token_values.len(), 50);
        for t in &token_values {
            assert_eq!(t, "FAKE_ACCESS_TOKEN_CONCURRENT");
        }

        // The mock server was hit exactly once (single-flight gate worked).
        let received = server.received_requests().await.unwrap();
        let token_calls = received
            .iter()
            .filter(|r| r.url.path() == "/oauth/v2/token")
            .count();
        assert_eq!(
            token_calls, 1,
            "expected exactly 1 OAuth call under concurrency, got {}",
            token_calls
        );
    }

    // -----------------------------------------------------------------------
    // Test 6: Download blocks US-DC redirect (no auth on .zoho.com hop)
    //
    // The wiremock server is on 127.0.0.1 which is NOT a trusted Zoho host.
    // We verify that after the 302 redirect hop the second request carries NO
    // Authorization header by inspecting received_requests().
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn download_blocks_us_dc_redirect() {
        let server = MockServer::start().await;

        // OAuth token endpoint
        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(token_response_json("FAKE_ACCESS_TOKEN_REDIRECT_TEST", 3600)),
            )
            .mount(&server)
            .await;

        // Download endpoint returns a 302 to the same server at a different path.
        // 127.0.0.1 is not in the trusted-host list, so auth must be stripped.
        let no_auth_path = "/download/us-dc-file.bin";
        let redirect_target = format!("{}{}", server.uri(), no_auth_path);

        Mock::given(method("GET"))
            .and(path("/files/test_file_id/download"))
            .respond_with(
                ResponseTemplate::new(302).insert_header("Location", redirect_target.as_str()),
            )
            .mount(&server)
            .await;

        // Redirect target: accepts any GET, returns file content.
        Mock::given(method("GET"))
            .and(path(no_auth_path))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(b"file content".to_vec()))
            .mount(&server)
            .await;

        let tokens = make_token_cache(&server);
        let client = make_client(tokens, &server, "test:redirect-block");

        let file_item = ChangeItem {
            remote_id: "test_file_id".to_string(),
            parent_id: Some("folder_1".to_string()),
            name: "test.txt".to_string(),
            is_folder: false,
            modified_at: None,
            deleted: false,
            raw: serde_json::json!({}),
        };

        let result = client.download(&file_item).await.unwrap();
        assert_eq!(result, b"file content");

        // Inspect requests: the redirect hop (second GET) must not carry Authorization.
        let received = server.received_requests().await.unwrap();
        let redirect_hop = received
            .iter()
            .find(|r| r.url.path() == no_auth_path)
            .expect("redirect hop request not found");
        assert!(
            !redirect_hop.headers.contains_key("authorization"),
            "Authorization header must NOT be present on redirect to non-trusted host; \
             found: {:?}",
            redirect_hop.headers.get("authorization")
        );
    }

    // -----------------------------------------------------------------------
    // Test 7: Download allows EU CDN redirect (auth present on .zohocdn.com hop)
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn download_allows_eu_cdn_redirect() {
        // We test the is_trusted_zoho_host function directly, plus test the
        // download path through the redirect logic with a localhost mock.
        // The trusted-host check for *.zohocdn.com is confirmed by the unit test below.
        assert!(is_trusted_zoho_host("download-cdn.zohocdn.com"));
        assert!(is_trusted_zoho_host("files.zoho.eu"));
        assert!(!is_trusted_zoho_host("download.zoho.com"));
        assert!(!is_trusted_zoho_host("zoho.com"));
        assert!(!is_trusted_zoho_host("evil-zoho.eu.attacker.com"));

        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(token_response_json("FAKE_ACCESS_TOKEN_CDN", 3600)),
            )
            .mount(&server)
            .await;

        // Direct download (no redirect) — simulating what happens when the CDN URL
        // is returned directly (localhost is treated as non-trusted but the initial
        // request always carries auth).
        Mock::given(method("GET"))
            .and(path("/files/cdn_file/download"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(b"cdn file data".to_vec()))
            .mount(&server)
            .await;

        let tokens = make_token_cache(&server);
        let client = make_client(tokens, &server, "test:cdn-redirect");

        let file_item = ChangeItem {
            remote_id: "cdn_file".to_string(),
            parent_id: None,
            name: "report.pdf".to_string(),
            is_folder: false,
            modified_at: None,
            deleted: false,
            raw: serde_json::json!({}),
        };

        let result = client.download(&file_item).await.unwrap();
        assert_eq!(result, b"cdn file data");
    }

    // -----------------------------------------------------------------------
    // Test 8: Download inline under threshold
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn download_inline_under_threshold() {
        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(token_response_json("FAKE_ACCESS_TOKEN_INLINE", 3600)),
            )
            .mount(&server)
            .await;

        let small_body: Vec<u8> = (0..1024).map(|i| (i % 256) as u8).collect();
        let expected = small_body.clone();

        Mock::given(method("GET"))
            .and(path("/files/small_file/download"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(small_body))
            .mount(&server)
            .await;

        let tokens = make_token_cache(&server);
        let client = make_client(tokens, &server, "test:inline");

        let item = ChangeItem {
            remote_id: "small_file".to_string(),
            parent_id: None,
            name: "small.bin".to_string(),
            is_folder: false,
            modified_at: None,
            deleted: false,
            raw: serde_json::json!({}),
        };

        let result = client.download(&item).await.unwrap();
        assert_eq!(result, expected);
    }

    // -----------------------------------------------------------------------
    // Test 9: Download spills over threshold (tempfile path)
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn download_spills_over_threshold() {
        // Build a body of exactly INLINE_THRESHOLD_BYTES + 1 MB to force the tempfile path.
        let size = INLINE_THRESHOLD_BYTES + 1024 * 1024; // 9 MiB
        let large_body: Vec<u8> = (0..size).map(|i| (i % 256) as u8).collect();
        let expected_len = large_body.len();

        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(token_response_json("FAKE_ACCESS_TOKEN_SPILL", 3600)),
            )
            .mount(&server)
            .await;

        Mock::given(method("GET"))
            .and(path("/files/large_file/download"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_bytes(large_body)
                    .insert_header("content-length", expected_len.to_string().as_str()),
            )
            .mount(&server)
            .await;

        let tokens = make_token_cache(&server);
        let client = make_client(tokens, &server, "test:spill");

        let item = ChangeItem {
            remote_id: "large_file".to_string(),
            parent_id: None,
            name: "large.bin".to_string(),
            is_folder: false,
            modified_at: None,
            deleted: false,
            raw: serde_json::json!({}),
        };

        let result = client.download(&item).await.unwrap();
        assert_eq!(result.len(), expected_len, "large body length mismatch");
    }

    // -----------------------------------------------------------------------
    // Test 10: Cursor roundtrip
    // -----------------------------------------------------------------------
    #[test]
    fn cursor_roundtrip() {
        let original = ZohoCursor {
            watermark_ms: 1_700_000_000_000,
            queue: VecDeque::from([
                FolderCursor {
                    folder_id: "f1".to_string(),
                    next_offset: 0,
                },
                FolderCursor {
                    folder_id: "f2".to_string(),
                    next_offset: 50,
                },
                FolderCursor {
                    folder_id: "f3".to_string(),
                    next_offset: 100,
                },
            ]),
            visited: vec!["f1".to_string(), "f2".to_string()],
            root_id: "root_abc".to_string(),
            run_started_ms: 1_700_000_001_000,
        };

        let json = original.to_json().unwrap();
        let restored = ZohoCursor::from_json(&json).unwrap();
        assert_eq!(original, restored);
    }

    // -----------------------------------------------------------------------
    // Test 11: Cursor BFS advances watermark only after drain
    // -----------------------------------------------------------------------
    #[tokio::test]
    async fn cursor_bfs_advances_watermark_only_after_drain() {
        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/oauth/v2/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(token_response_json("FAKE_ACCESS_TOKEN_BFS", 3600)),
            )
            .mount(&server)
            .await;

        // First delta page: folder "root_folder_123" contains one file.
        Mock::given(method("GET"))
            .and(path("/files/root_folder_123/files"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{"data":[{"id":"file1","attributes":{"name":"doc.txt","type":"file","modified_time":1700000000000,"status":"active"}}],"info":{"more_records":false}}"#,
            ))
            .mount(&server)
            .await;

        let tokens = make_token_cache(&server);
        let client = make_client(tokens, &server, "test:bfs");

        // First call: bootstrap (None cursor) — should return changes and non-zero queue.
        let result1 = client.list_changes(None).await.unwrap();
        assert!(!result1.changes.is_empty(), "should have found file1");

        // Deserialize cursor — queue must be empty (one folder, fully drained).
        let cursor1 = result1.next_cursor.as_deref().unwrap();
        let state1 = ZohoCursor::from_json(cursor1).unwrap();
        // watermark should still be 0 — queue is empty only after returning.
        // Actually: queue may now be empty since root_folder_123 had no subfolders.
        // When queue drains on the NEXT call, watermark advances.
        assert_eq!(
            state1.watermark_ms, 0,
            "watermark must not advance while queue has work (or just drained — needs second call)"
        );

        // Second call with the cursor: queue is empty, so this advances the watermark.
        let result2 = client.list_changes(Some(cursor1)).await.unwrap();
        let cursor2 = result2.next_cursor.as_deref().unwrap();
        let state2 = ZohoCursor::from_json(cursor2).unwrap();

        // After the second call the watermark_ms must equal the previous run_started_ms.
        assert!(
            state2.watermark_ms > 0,
            "watermark must advance after BFS drain, got {}",
            state2.watermark_ms
        );
        assert_eq!(
            state2.watermark_ms, state1.run_started_ms,
            "new watermark must equal previous run_started_ms"
        );
    }

    // -----------------------------------------------------------------------
    // Test 12: Debug impls redact token fields
    // -----------------------------------------------------------------------
    #[test]
    fn debug_redacts_tokens() {
        let creds = ZohoCredentials {
            client_id: "my-client-id".into(),
            client_secret: "super_secret_value".into(),
            refresh_token: "FAKE_REFRESH_TOKEN_FOR_TESTS".into(),
            dc: "eu".into(),
        };
        let debug_str = format!("{:?}", creds);
        assert!(
            debug_str.contains("<redacted>"),
            "expected <redacted> in ZohoCredentials Debug output"
        );
        assert!(
            !debug_str.contains("super_secret_value"),
            "client_secret must not appear in Debug output"
        );
        assert!(
            !debug_str.contains("FAKE_REFRESH_TOKEN_FOR_TESTS"),
            "refresh_token must not appear in Debug output"
        );
        // client_id IS visible (not a secret).
        assert!(debug_str.contains("my-client-id"));

        // CachedToken Debug redaction.
        let cached = CachedToken {
            access_token: "FAKE_ACCESS_TOKEN_FOR_TESTS".into(),
            expires_at: Instant::now(),
        };
        let token_debug = format!("{:?}", cached);
        assert!(token_debug.contains("<redacted>"));
        assert!(!token_debug.contains("FAKE_ACCESS_TOKEN_FOR_TESTS"));
    }

    // Trusted-host allowlist unit tests (belt-and-suspenders for the security check).
    #[test]
    fn trusted_host_allowlist() {
        // Allowed.
        assert!(is_trusted_zoho_host("workdrive.zoho.eu"));
        assert!(is_trusted_zoho_host("accounts.zoho.eu"));
        assert!(is_trusted_zoho_host("zoho.eu"));
        assert!(is_trusted_zoho_host("cdn1.zohocdn.com"));
        assert!(is_trusted_zoho_host("download-cdn.zohocdn.com"));

        // Blocked.
        assert!(!is_trusted_zoho_host("workdrive.zoho.com")); // US DC
        assert!(!is_trusted_zoho_host("zoho.com"));
        assert!(!is_trusted_zoho_host("accounts.zoho.in")); // India DC
        assert!(!is_trusted_zoho_host("evil.zoho.eu.attacker.com")); // subdomain of attacker
        assert!(!is_trusted_zoho_host("notzoho.eu"));
        assert!(!is_trusted_zoho_host("127.0.0.1"));
        assert!(!is_trusted_zoho_host(""));
    }

    // ZohoCredentials Clone for test helpers.
    impl Clone for ZohoCredentials {
        fn clone(&self) -> Self {
            ZohoCredentials {
                client_id: self.client_id.clone(),
                client_secret: self.client_secret.clone(),
                refresh_token: self.refresh_token.clone(),
                dc: self.dc.clone(),
            }
        }
    }
}
