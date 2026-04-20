/// TEI embedding client — activated in Phase 3 for --reembed support.
///
/// Used by the key-rotation loop when --reembed is set: after re-encrypting each chunk
/// the plaintext is re-embedded via TEI and the embedding column updated in the same
/// transaction.  Requires the embedding column from V7__add_vector_embeddings.sql.
///
/// TEI API: POST /v1/embeddings  (OpenAI-compatible)
/// Model:   nomic-ai/nomic-embed-text-v1.5  (768-dim)
use anyhow::Result;
use reqwest::Client;
use serde::{Deserialize, Serialize};

#[derive(Serialize)]
struct EmbedRequest<'a> {
    model: &'a str,
    input: &'a str,
}

#[derive(Deserialize)]
struct EmbedResponse {
    data: Vec<EmbedData>,
}

#[derive(Deserialize)]
struct EmbedData {
    embedding: Vec<f32>,
}

pub struct EmbedClient {
    client: Client,
    base_url: String,
}

impl EmbedClient {
    pub fn new(base_url: &str) -> Self {
        Self {
            client: Client::new(),
            base_url: base_url.trim_end_matches('/').to_string(),
        }
    }

    /// Embed a single text string, returning a 768-dim f32 vector.
    pub async fn embed(&self, text: &str) -> Result<Vec<f32>> {
        let url = format!("{}/v1/embeddings", self.base_url);
        let resp: EmbedResponse = self
            .client
            .post(&url)
            .json(&EmbedRequest {
                model: "nomic-embed-text-v1.5",
                input: text,
            })
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        resp.data
            .into_iter()
            .next()
            .map(|d| d.embedding)
            .ok_or_else(|| anyhow::anyhow!("TEI returned empty data array"))
    }
}
