/// Microsoft Graph drive delta client.
///
/// Auth: OAuth 2.0 client credentials (app-only, no user interaction).
/// Token is cached in memory and refreshed when within 60 s of expiry.
///
/// Delta query: GET /drives/{drive_id}/root/delta
///   - First call (no cursor): full crawl of the drive.
///   - Subsequent calls: pass saved @odata.deltaLink URL; Graph returns only changes.
///   - Follows @odata.nextLink pagination until @odata.deltaLink signals end of cycle.
///
/// Deleted items appear in the delta with a "deleted" property.
/// File items carry @microsoft.graph.downloadUrl (pre-signed, no auth needed).
/// Folders are silently skipped.
use anyhow::{anyhow, Context, Result};
use reqwest::Client;
use serde::Deserialize;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tracing::{debug, info};

pub struct GraphClient {
    tenant_id: String,
    client_id: String,
    client_secret: String,
    http: Client,
    token: Mutex<Option<CachedToken>>,
}

struct CachedToken {
    value: String,
    expires_at: Instant,
}

/// Result of one full delta cycle (all pages followed).
pub struct DeltaResult {
    /// Files added or modified since the last cursor.
    pub changed: Vec<DriveItem>,
    /// IDs of items deleted since the last cursor.
    pub deleted: Vec<String>,
    /// The @odata.deltaLink URL — save to ingestion_state for the next cycle.
    pub next_link: String,
}

pub struct DriveItem {
    pub id: String,
    pub name: String,
    /// Pre-signed download URL valid for ~1 hour.  None for items without content.
    pub download_url: Option<String>,
}

// ── Serde shapes ────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: u64,
}

#[derive(Deserialize)]
struct DeltaPage {
    value: Vec<RawItem>,
    #[serde(rename = "@odata.nextLink")]
    next_link: Option<String>,
    #[serde(rename = "@odata.deltaLink")]
    delta_link: Option<String>,
}

#[derive(Deserialize)]
struct RawItem {
    id: String,
    name: Option<String>,
    /// Present for files; absent for folders.
    file: Option<serde_json::Value>,
    /// Present for deleted items: `{"state": "deleted"}`.
    deleted: Option<serde_json::Value>,
    /// Pre-signed CDN URL; only present when `file` is set.
    #[serde(rename = "@microsoft.graph.downloadUrl")]
    download_url: Option<String>,
}

// ── Implementation ──────────────────────────────────────────────────────────

impl GraphClient {
    pub fn new(tenant_id: String, client_id: String, client_secret: String) -> Self {
        Self {
            tenant_id,
            client_id,
            client_secret,
            http: Client::new(),
            token: Mutex::new(None),
        }
    }

    /// Run one full delta cycle for `drive_id`.
    ///
    /// `cursor`: `None` on first run (triggers a full crawl).
    ///            `Some(url)` is the `@odata.deltaLink` from the previous cycle.
    ///
    /// Returns [`DeltaResult`] whose `next_link` must be saved to `ingestion_state`.
    pub async fn delta(&self, drive_id: &str, cursor: Option<&str>) -> Result<DeltaResult> {
        let token = self.access_token().await?;

        let start_url = match cursor {
            Some(url) => url.to_string(),
            None => format!("https://graph.microsoft.com/v1.0/drives/{drive_id}/root/delta"),
        };

        let mut changed = Vec::new();
        let mut deleted = Vec::new();
        let mut url = start_url;

        // Follow @odata.nextLink pages until we receive @odata.deltaLink (end of cycle).
        // `loop { break value }` returns the deltaLink so no intermediate Option is needed.
        let next_link: String = loop {
            debug!("Graph GET {url}");
            let page: DeltaPage = self
                .http
                .get(&url)
                .bearer_auth(&token)
                .send()
                .await
                .context("Graph delta request failed")?
                .error_for_status()
                .context("Graph delta returned error status")?
                .json()
                .await
                .context("failed to decode Graph delta response")?;

            for item in page.value {
                if item.deleted.is_some() {
                    deleted.push(item.id);
                } else if item.file.is_some() {
                    changed.push(DriveItem {
                        id: item.id,
                        name: item.name.unwrap_or_default(),
                        download_url: item.download_url,
                    });
                }
                // Folders (no `file`, no `deleted`) are ignored
            }

            match (page.next_link, page.delta_link) {
                (Some(np), _) => url = np,
                (None, Some(dl)) => break dl,
                (None, None) => {
                    return Err(anyhow!(
                        "Graph delta response has neither @odata.nextLink nor @odata.deltaLink"
                    ))
                }
            }
        };

        info!(
            "Delta complete — {} changed, {} deleted",
            changed.len(),
            deleted.len()
        );

        Ok(DeltaResult {
            changed,
            deleted,
            next_link,
        })
    }

    /// Download file content from a pre-signed `@microsoft.graph.downloadUrl`.
    /// No Authorization header — the URL is self-authenticating.
    pub async fn download(&self, url: &str) -> Result<Vec<u8>> {
        let bytes = self
            .http
            .get(url)
            .send()
            .await
            .context("file download request failed")?
            .error_for_status()
            .context("file download returned error status")?
            .bytes()
            .await
            .context("failed to read file download body")?;
        Ok(bytes.to_vec())
    }

    // ── Token management ───────────────────────────────────────────────────

    /// Return a valid Bearer token, refreshing if within 60 s of expiry.
    async fn access_token(&self) -> Result<String> {
        // Fast path: return cached token if still fresh.
        {
            let guard = self.token.lock().unwrap();
            if let Some(ref cached) = *guard {
                if Instant::now() + Duration::from_secs(60) < cached.expires_at {
                    return Ok(cached.value.clone());
                }
            }
        }

        // Slow path: fetch a new token and update the cache.
        let resp = self.fetch_token().await?;
        let value = resp.access_token.clone();
        {
            let mut guard = self.token.lock().unwrap();
            *guard = Some(CachedToken {
                value: value.clone(),
                expires_at: Instant::now()
                    + Duration::from_secs(resp.expires_in.saturating_sub(60)),
            });
        }
        Ok(value)
    }

    async fn fetch_token(&self) -> Result<TokenResponse> {
        let url = format!(
            "https://login.microsoftonline.com/{}/oauth2/v2.0/token",
            self.tenant_id
        );
        let resp: TokenResponse = self
            .http
            .post(&url)
            .form(&[
                ("grant_type", "client_credentials"),
                ("client_id", &self.client_id),
                ("client_secret", &self.client_secret),
                ("scope", "https://graph.microsoft.com/.default"),
            ])
            .send()
            .await
            .context("token request failed")?
            .error_for_status()
            .context("token request returned error status")?
            .json()
            .await
            .context("failed to decode token response")?;

        info!(
            "Graph OAuth token refreshed (expires_in={}s)",
            resp.expires_in
        );
        Ok(resp)
    }
}
