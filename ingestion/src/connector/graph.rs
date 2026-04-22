// Microsoft Graph drive delta connector.
//
// Auth: OAuth 2.0 client credentials (app-only, no user interaction).
// Token is cached in memory and refreshed when within 60 s of expiry.
//
// Delta query: GET /drives/{drive_id}/root/delta
//   - First call (no cursor): full crawl of the drive.
//   - Subsequent calls: pass saved @odata.deltaLink URL; Graph returns only changes.
//   - Follows @odata.nextLink pagination until @odata.deltaLink signals end of cycle.
//
// Deleted items appear in the delta with a "deleted" property.
// File items carry @microsoft.graph.downloadUrl (pre-signed, no auth needed).
// Folders are silently skipped.
//
// SourceConnector mapping:
//   id()            → the drive_id string passed at construction
//   kind()          → "microsoft_graph"
//   list_changes()  → wraps the full delta loop; cursor is the opaque deltaLink URL
//   download()      → uses the @microsoft.graph.downloadUrl from ChangeItem.raw
use anyhow::Context;
use async_trait::async_trait;
use reqwest::Client;
use serde::Deserialize;
use serde_json::json;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tracing::{debug, info};

use super::{AuthErrorKind, ChangeItem, ConnectorError, DeltaResult, SourceConnector};

pub struct GraphClient {
    /// The SharePoint drive ID — persisted in ingestion_state.connector_id.
    drive_id: String,
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

// Serde shapes for Graph API responses.

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
    // Present for files; absent for folders.
    file: Option<serde_json::Value>,
    // Present for deleted items: {"state": "deleted"}.
    deleted: Option<serde_json::Value>,
    // Pre-signed CDN URL; only present when `file` is set.
    #[serde(rename = "@microsoft.graph.downloadUrl")]
    download_url: Option<String>,
}

impl GraphClient {
    pub fn new(
        drive_id: String,
        tenant_id: String,
        client_id: String,
        client_secret: String,
    ) -> Self {
        Self {
            drive_id,
            tenant_id,
            client_id,
            client_secret,
            http: Client::new(),
            token: Mutex::new(None),
        }
    }

    // Return a valid Bearer token, refreshing if within 60 s of expiry.
    async fn access_token(&self) -> Result<String, ConnectorError> {
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
        let resp = self
            .fetch_token()
            .await
            .map_err(|e| ConnectorError::Other(format!("token fetch failed: {e:#}")))?;
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

    async fn fetch_token(&self) -> anyhow::Result<TokenResponse> {
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

    // Run one full delta cycle, following all @odata.nextLink pages.
    // Returns (changes, deleted_ids, final_delta_link).
    async fn run_delta_cycle(
        &self,
        cursor: Option<&str>,
    ) -> Result<(Vec<ChangeItem>, Vec<String>, String), ConnectorError> {
        let token = self.access_token().await?;

        let start_url = match cursor {
            Some(url) => url.to_string(),
            None => format!(
                "https://graph.microsoft.com/v1.0/drives/{}/root/delta",
                self.drive_id
            ),
        };

        let mut changes: Vec<ChangeItem> = Vec::new();
        let mut deleted_ids: Vec<String> = Vec::new();
        let mut url = start_url;

        // Follow @odata.nextLink pages until we receive @odata.deltaLink (end of cycle).
        let next_link: String = loop {
            debug!("Graph GET {url}");
            let resp = self
                .http
                .get(&url)
                .bearer_auth(&token)
                .send()
                .await
                .map_err(|e| ConnectorError::Other(format!("Graph delta request failed: {e}")))?;

            let status = resp.status().as_u16();
            if status == 401 || status == 403 {
                return Err(ConnectorError::Auth(AuthErrorKind::Unexpected));
            }
            if status == 429 {
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
            if !resp.status().is_success() {
                return Err(ConnectorError::Http { status });
            }

            let page: DeltaPage = resp
                .json()
                .await
                .map_err(|e| ConnectorError::Other(format!("failed to decode delta page: {e}")))?;

            for item in page.value {
                if item.deleted.is_some() {
                    // Represent deletions as ChangeItems with deleted=true so callers
                    // can use a single code path.
                    deleted_ids.push(item.id.clone());
                    changes.push(ChangeItem {
                        remote_id: item.id,
                        parent_id: None,
                        name: item.name.unwrap_or_default(),
                        is_folder: false,
                        modified_at: None,
                        deleted: true,
                        raw: json!({}),
                    });
                } else if item.file.is_some() {
                    let raw = json!({
                        "@microsoft.graph.downloadUrl": item.download_url,
                    });
                    changes.push(ChangeItem {
                        remote_id: item.id,
                        parent_id: None,
                        name: item.name.unwrap_or_default(),
                        is_folder: false,
                        modified_at: None,
                        deleted: false,
                        raw,
                    });
                }
                // Folders (no `file`, no `deleted`) are ignored.
            }

            match (page.next_link, page.delta_link) {
                (Some(np), _) => url = np,
                (None, Some(dl)) => break dl,
                (None, None) => {
                    return Err(ConnectorError::Other(
                        "Graph delta response has neither @odata.nextLink nor @odata.deltaLink"
                            .into(),
                    ));
                }
            }
        };

        info!(
            "Graph delta complete — {} changed/deleted items",
            changes.len()
        );

        Ok((changes, deleted_ids, next_link))
    }
}

#[async_trait]
impl SourceConnector for GraphClient {
    fn id(&self) -> &str {
        &self.drive_id
    }

    fn kind(&self) -> &str {
        "microsoft_graph"
    }

    // Fetch the next batch of changes.
    //
    // The cursor is the opaque @odata.deltaLink URL saved from the previous cycle.
    // None triggers a full crawl (first run).  This implementation always drains all
    // @odata.nextLink pages in one call, so more is always false on success.
    async fn list_changes(&self, cursor: Option<&str>) -> Result<DeltaResult, ConnectorError> {
        let (changes, _deleted_ids, delta_link) = self.run_delta_cycle(cursor).await?;
        Ok(DeltaResult {
            next_cursor: Some(delta_link),
            changes,
            more: false,
        })
    }

    // Download the bytes of a file item.
    //
    // The pre-signed @microsoft.graph.downloadUrl is read from item.raw; no
    // Authorization header is needed (the URL is self-authenticating).
    async fn download(&self, item: &ChangeItem) -> Result<Vec<u8>, ConnectorError> {
        let url = item
            .raw
            .get("@microsoft.graph.downloadUrl")
            .and_then(|v| v.as_str())
            .ok_or_else(|| {
                ConnectorError::Other(format!(
                    "ChangeItem {} has no @microsoft.graph.downloadUrl in raw",
                    item.remote_id
                ))
            })?;

        let resp = self
            .http
            .get(url)
            .send()
            .await
            .map_err(|e| ConnectorError::Other(format!("file download request failed: {e}")))?;

        let status = resp.status().as_u16();
        if !resp.status().is_success() {
            return Err(ConnectorError::Http { status });
        }

        let bytes = resp
            .bytes()
            .await
            .map_err(|e| ConnectorError::Other(format!("failed to read download body: {e}")))?;
        Ok(bytes.to_vec())
    }
}
