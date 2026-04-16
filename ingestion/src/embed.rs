/// TEI embedding client — batch POST to /v1/embeddings (OpenAI-compatible).
///
/// Model: nomic-ai/nomic-embed-text-v1.5 (768 dimensions).
/// Batch size: configurable (default 32), TEI handles up to 128 comfortably.
///
/// Texts are sent in slices of `batch_size`; responses are collected in input order.
/// The `index` field in the response is used to sort results, making the call robust
/// against any future server-side reordering.
use anyhow::{anyhow, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};

pub struct EmbedClient {
    client: Client,
    base_url: String,
    batch_size: usize,
}

#[derive(Serialize)]
struct EmbedRequest<'a> {
    model: &'static str,
    input: &'a [&'a str],
}

#[derive(Deserialize)]
struct EmbedResponse {
    data: Vec<EmbedData>,
}

#[derive(Deserialize)]
struct EmbedData {
    index: usize,
    embedding: Vec<f32>,
}

impl EmbedClient {
    pub fn new(base_url: &str, batch_size: usize) -> Self {
        Self {
            client: Client::new(),
            base_url: base_url.trim_end_matches('/').to_string(),
            batch_size: batch_size.max(1),
        }
    }

    /// Embed `texts` in batches of `self.batch_size`.
    ///
    /// Returns one 768-dim vector per input text, in the same order as `texts`.
    pub async fn embed_batch(&self, texts: &[String]) -> Result<Vec<Vec<f32>>> {
        let mut all: Vec<Vec<f32>> = Vec::with_capacity(texts.len());
        for batch in texts.chunks(self.batch_size) {
            let mut batch_result = self.embed_one_batch(batch).await?;
            all.append(&mut batch_result);
        }
        Ok(all)
    }

    async fn embed_one_batch(&self, texts: &[String]) -> Result<Vec<Vec<f32>>> {
        let refs: Vec<&str> = texts.iter().map(String::as_str).collect();
        let url = format!("{}/v1/embeddings", self.base_url);

        let mut resp: EmbedResponse = self
            .client
            .post(&url)
            .json(&EmbedRequest {
                model: "nomic-embed-text-v1.5",
                input: &refs,
            })
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;

        if resp.data.len() != texts.len() {
            return Err(anyhow!(
                "TEI returned {} embeddings for {} inputs",
                resp.data.len(),
                texts.len()
            ));
        }

        // Sort by index so results match input order even if TEI reorders responses.
        resp.data.sort_by_key(|d| d.index);

        Ok(resp.data.into_iter().map(|d| d.embedding).collect())
    }
}
