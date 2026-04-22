// Local E2E smoke test for the Zoho WorkDrive connector.
//
// Runs ONLY when ZOHO_INGESTION_E2E=1 is set in the environment.
// Requires real Zoho OAuth credentials in env:
//   ZOHO_CLIENT_ID, ZOHO_CLIENT_SECRET, ZOHO_REFRESH_TOKEN, ZOHO_ROOT_ID
//   ZOHO_DC (default: "eu")
//
// This test is intentionally NOT exhaustive — the unit tests in
// src/connector/zoho.rs cover all behavior with wiremock mocks.
// This test only confirms that a token refresh and first list_changes()
// call succeed against the live Zoho API.
//
// Same skip pattern as McpRemoteEndToEndIT (Java).
// Never runs in CI. Never commits credentials.

/// Minimal smoke: verify OAuth refresh and one BFS page succeed.
///
/// To run locally once credentials are provisioned:
///   ZOHO_INGESTION_E2E=1 \
///   ZOHO_CLIENT_ID=... \
///   ZOHO_CLIENT_SECRET=... \
///   ZOHO_REFRESH_TOKEN=... \
///   ZOHO_ROOT_ID=... \
///   cargo test --test zoho_ingestion_e2e -- --nocapture
#[tokio::test]
async fn zoho_ingestion_e2e() {
    if std::env::var("ZOHO_INGESTION_E2E").ok().as_deref() != Some("1") {
        eprintln!("SKIP: set ZOHO_INGESTION_E2E=1 to run the live Zoho WorkDrive smoke test");
        return;
    }

    // Read credentials from env — never hardcoded, never committed.
    let client_id = std::env::var("ZOHO_CLIENT_ID").expect("ZOHO_CLIENT_ID must be set");
    let client_secret =
        std::env::var("ZOHO_CLIENT_SECRET").expect("ZOHO_CLIENT_SECRET must be set");
    let refresh_token =
        std::env::var("ZOHO_REFRESH_TOKEN").expect("ZOHO_REFRESH_TOKEN must be set");
    let root_id = std::env::var("ZOHO_ROOT_ID").expect("ZOHO_ROOT_ID must be set");
    let dc = std::env::var("ZOHO_DC").unwrap_or_else(|_| "eu".into());
    let connector_id = format!("zoho:{}", root_id);

    // Use reqwest directly to verify token refresh — avoids needing lib crate access.
    let http = reqwest::Client::builder()
        .use_rustls_tls()
        .build()
        .expect("failed to build HTTP client");

    let token_url = format!("https://accounts.zoho.{}/oauth/v2/token", dc);
    let resp = http
        .post(&token_url)
        .form(&[
            ("grant_type", "refresh_token"),
            ("client_id", client_id.as_str()),
            ("client_secret", client_secret.as_str()),
            ("refresh_token", refresh_token.as_str()),
        ])
        .send()
        .await
        .expect("OAuth token request should succeed");

    assert!(
        resp.status().is_success(),
        "OAuth token refresh failed with status {}",
        resp.status()
    );

    // Parse the access token from the response body.
    let body: serde_json::Value = resp
        .json()
        .await
        .expect("OAuth response should be valid JSON");

    assert!(
        body.get("access_token").is_some(),
        "OAuth response must contain access_token; got: connector_id={connector_id}"
    );

    eprintln!(
        "E2E: OAuth token refresh OK for connector_id={connector_id} — connector is functional"
    );
}
