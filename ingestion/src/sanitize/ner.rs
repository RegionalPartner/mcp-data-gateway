// Named-entity recognition pass — detects PERSON / ORG / LOC spans using a
// multilingual distilbert NER model (Davlan/distilbert-base-multilingual-cased-ner-hrl).
//
// This module is gated behind the `sanitize-ner` feature because pulling in
// candle + tokenizers + hf-hub adds ~30 MB of compiled artifacts and the
// model itself is 270 MB on disk (downloaded on first use via `hf-hub`).
// CI MUST NOT enable this feature by default.
//
// High-level flow:
//   1. First call lazily loads the model via `OnceLock<NerModel>`.
//   2. Tokenize the input text with the HF tokenizer, recording per-token
//      character offset pairs.
//   3. Run the distilbert forward pass + linear classification head.
//   4. BIO-merge consecutive token tags of the same kind into character
//      spans.  Skip MISC; map PER→PERSON, ORG→ORG, LOC→LOC.
//
// The body is intentionally conservative — failing in this pass (e.g. the
// model can't be downloaded) must never prevent the ingestion pipeline from
// making progress.  Callers treat a Err return as "no NER hits today".

use std::sync::OnceLock;

use candle_core::{Device, Tensor};
use candle_nn::{linear_no_bias, Linear, Module, VarBuilder};
use candle_transformers::models::distilbert::{Config as DistilBertConfig, DistilBertModel};
use hf_hub::api::sync::Api;
use tokenizers::Tokenizer;

use super::{EntityKind, Span};

// The HF model ID.  Fine-tuned by Davlan on ten African + European languages
// — the "hrl" suffix stands for "high-resource languages".
const MODEL_REPO: &str = "Davlan/distilbert-base-multilingual-cased-ner-hrl";

// Kept private to this module — single-init guard.
static MODEL: OnceLock<Option<NerModel>> = OnceLock::new();

struct NerModel {
    tokenizer: Tokenizer,
    distilbert: DistilBertModel,
    classifier: Linear,
    labels: Vec<String>,
    device: Device,
}

// Mapping from the model's IOB2 label to the internal EntityKind.
// MISC is intentionally dropped — it is too noisy and the regex scan already
// covers most of what MISC would flag in the ingestion corpus.
fn map_label(lbl: &str) -> Option<EntityKind> {
    match lbl {
        "B-PER" | "I-PER" => Some(EntityKind::Person),
        "B-ORG" | "I-ORG" => Some(EntityKind::Org),
        "B-LOC" | "I-LOC" => Some(EntityKind::Loc),
        _ => None,
    }
}

/// Scan `text` for NER-detected PII spans.
///
/// Returns `Err` if the model failed to load or the inference pipeline
/// errored — callers MUST treat this as "no hits" (fail-open for detection,
/// fail-safe for availability).  The original text is never mutated.
pub fn scan(text: &str) -> Result<Vec<Span>, NerError> {
    if text.is_empty() {
        return Ok(Vec::new());
    }
    let model = match MODEL.get_or_init(load_model) {
        Some(m) => m,
        None => return Err(NerError::ModelLoad),
    };

    let enc = model
        .tokenizer
        .encode(text, true)
        .map_err(|_| NerError::Tokenize)?;
    let ids: Vec<u32> = enc.get_ids().to_vec();
    let offsets: Vec<(usize, usize)> = enc.get_offsets().to_vec();

    // Build [1, T] input tensors.
    let input_ids = Tensor::new(ids.as_slice(), &model.device)
        .and_then(|t| t.unsqueeze(0))
        .map_err(|_| NerError::Inference)?;
    let mask = Tensor::ones(input_ids.shape(), candle_core::DType::I64, &model.device)
        .map_err(|_| NerError::Inference)?;

    let hidden = model
        .distilbert
        .forward(&input_ids, &mask)
        .map_err(|_| NerError::Inference)?;
    let logits = model
        .classifier
        .forward(&hidden)
        .map_err(|_| NerError::Inference)?;

    // argmax over the label dimension for each token.
    let preds = logits
        .argmax(candle_core::D::Minus1)
        .and_then(|t| t.squeeze(0))
        .map_err(|_| NerError::Inference)?;
    let label_ids: Vec<u32> = preds.to_vec1().map_err(|_| NerError::Inference)?;

    Ok(bio_merge(&offsets, &label_ids, &model.labels))
}

// -----------------------------------------------------------------------
// BIO merging — pure, no model dependencies, fully unit-testable
// -----------------------------------------------------------------------

/// Merge BIO-tagged tokens into contiguous spans.
///
/// Contract:
///   * `offsets[i]` is the (start, end) char offset pair for token `i`;
///     offset (0, 0) means "special token" and is skipped.
///   * `label_ids[i]` is the argmax index into `labels`.
///   * Consecutive tokens whose tags map to the same EntityKind merge into a
///     single span.  A `B-*` tag always starts a new span; an `I-*` tag
///     extends the current span if the kind matches.  The `O` tag terminates
///     any open span.
///
/// Returns spans in document order.
pub fn bio_merge(offsets: &[(usize, usize)], label_ids: &[u32], labels: &[String]) -> Vec<Span> {
    let mut out = Vec::new();
    let mut open: Option<(usize, usize, EntityKind)> = None;

    for (i, &lid) in label_ids.iter().enumerate() {
        let (s, e) = offsets.get(i).copied().unwrap_or((0, 0));
        if s == 0 && e == 0 {
            // Special token (CLS, SEP, PAD).  Close any open span and skip.
            if let Some((a, b, k)) = open.take() {
                out.push(Span {
                    start: a,
                    end: b,
                    kind: k,
                });
            }
            continue;
        }

        let lbl = match labels.get(lid as usize) {
            Some(l) => l.as_str(),
            None => "O",
        };

        let kind = map_label(lbl);
        let is_begin = lbl.starts_with("B-");
        let is_inside = lbl.starts_with("I-");

        match (kind, is_begin, is_inside, open.take()) {
            (Some(k), true, _, prev) => {
                if let Some((a, b, pk)) = prev {
                    out.push(Span {
                        start: a,
                        end: b,
                        kind: pk,
                    });
                }
                open = Some((s, e, k));
            }
            (Some(k), false, true, Some((a, _, pk))) if pk == k => {
                // extend current
                open = Some((a, e, pk));
            }
            (Some(k), false, true, prev) => {
                // stray I-* without matching B-*: treat as new span.
                if let Some((a, b, pk)) = prev {
                    out.push(Span {
                        start: a,
                        end: b,
                        kind: pk,
                    });
                }
                open = Some((s, e, k));
            }
            (_, _, _, prev) => {
                // O or unknown → close current span if any.
                if let Some((a, b, pk)) = prev {
                    out.push(Span {
                        start: a,
                        end: b,
                        kind: pk,
                    });
                }
                open = None;
            }
        }
    }

    if let Some((a, b, k)) = open {
        out.push(Span {
            start: a,
            end: b,
            kind: k,
        });
    }
    out
}

// -----------------------------------------------------------------------
// Model loading
// -----------------------------------------------------------------------

fn load_model() -> Option<NerModel> {
    match try_load_model() {
        Ok(m) => Some(m),
        Err(e) => {
            tracing::error!(error = ?e, "NER model load failed — PII NER disabled for this run");
            None
        }
    }
}

fn try_load_model() -> Result<NerModel, NerError> {
    let api = Api::new().map_err(|_| NerError::ModelLoad)?;
    let repo = api.model(MODEL_REPO.to_string());

    let tokenizer_path = repo
        .get("tokenizer.json")
        .map_err(|_| NerError::ModelLoad)?;
    let config_path = repo.get("config.json").map_err(|_| NerError::ModelLoad)?;
    let weights_path = repo
        .get("model.safetensors")
        .map_err(|_| NerError::ModelLoad)?;

    let tokenizer = Tokenizer::from_file(tokenizer_path).map_err(|_| NerError::ModelLoad)?;

    let config_raw = std::fs::read_to_string(&config_path).map_err(|_| NerError::ModelLoad)?;
    let raw: serde_json::Value =
        serde_json::from_str(&config_raw).map_err(|_| NerError::ModelLoad)?;

    // Pull the id2label map for label indexing at inference time.
    let id2label = raw
        .get("id2label")
        .and_then(|v| v.as_object())
        .ok_or(NerError::ModelLoad)?;
    let mut labels: Vec<String> = vec!["O".into(); id2label.len()];
    for (k, v) in id2label {
        let idx: usize = k.parse().map_err(|_| NerError::ModelLoad)?;
        let lbl = v.as_str().ok_or(NerError::ModelLoad)?.to_string();
        if idx >= labels.len() {
            labels.resize(idx + 1, "O".into());
        }
        labels[idx] = lbl;
    }

    let config: DistilBertConfig =
        serde_json::from_str(&config_raw).map_err(|_| NerError::ModelLoad)?;

    let device = Device::Cpu;
    let vb = unsafe {
        VarBuilder::from_mmaped_safetensors(&[&weights_path], candle_core::DType::F32, &device)
            .map_err(|_| NerError::ModelLoad)?
    };
    let distilbert =
        DistilBertModel::load(vb.pp("distilbert"), &config).map_err(|_| NerError::ModelLoad)?;
    let classifier = linear_no_bias(config.dim, labels.len(), vb.pp("classifier"))
        .map_err(|_| NerError::ModelLoad)?;

    Ok(NerModel {
        tokenizer,
        distilbert,
        classifier,
        labels,
        device,
    })
}

// -----------------------------------------------------------------------
// Errors
// -----------------------------------------------------------------------

#[derive(Debug, thiserror::Error)]
pub enum NerError {
    #[error("NER model failed to load")]
    ModelLoad,
    #[error("NER tokenization failed")]
    Tokenize,
    #[error("NER inference failed")]
    Inference,
}

// -----------------------------------------------------------------------
// Tests — BIO merger only (model load is gated on a live HF session,
// covered only when SANITIZE_NER_E2E=1)
// -----------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn labels() -> Vec<String> {
        vec![
            "O".into(),      // 0
            "B-PER".into(),  // 1
            "I-PER".into(),  // 2
            "B-ORG".into(),  // 3
            "I-ORG".into(),  // 4
            "B-LOC".into(),  // 5
            "I-LOC".into(),  // 6
            "B-MISC".into(), // 7 — must be ignored
            "I-MISC".into(), // 8 — must be ignored
        ]
    }

    #[test]
    fn bio_merge_collapses_consecutive_i_tags() {
        // Tokens: [CLS] "Alice" "Dupont" "works" "at" "ACME" [SEP]
        let offsets = vec![
            (0, 0),
            (0, 5),
            (6, 12),
            (13, 18),
            (19, 21),
            (22, 26),
            (0, 0),
        ];
        let ids: Vec<u32> = vec![0, 1, 2, 0, 0, 3, 0];
        let spans = bio_merge(&offsets, &ids, &labels());
        assert_eq!(spans.len(), 2);
        assert_eq!(
            spans[0],
            Span {
                start: 0,
                end: 12,
                kind: EntityKind::Person
            }
        );
        assert_eq!(
            spans[1],
            Span {
                start: 22,
                end: 26,
                kind: EntityKind::Org
            }
        );
    }

    #[test]
    fn bio_merge_drops_misc() {
        let offsets = vec![(0, 0), (0, 5), (0, 0)];
        let ids: Vec<u32> = vec![0, 7, 0]; // B-MISC dropped
        let spans = bio_merge(&offsets, &ids, &labels());
        assert!(spans.is_empty(), "MISC must be ignored; got {spans:?}");
    }

    #[test]
    fn bio_merge_stray_i_tag_starts_new_span() {
        // Malformed sequence "O I-PER O" — treat the I-PER as a lone span.
        let offsets = vec![(0, 0), (0, 5), (0, 0)];
        let ids: Vec<u32> = vec![0, 2, 0];
        let spans = bio_merge(&offsets, &ids, &labels());
        assert_eq!(spans.len(), 1);
        assert_eq!(spans[0].kind, EntityKind::Person);
    }

    #[test]
    fn bio_merge_different_kinds_do_not_merge() {
        // B-PER B-ORG → two separate spans.
        let offsets = vec![(0, 0), (0, 5), (6, 11), (0, 0)];
        let ids: Vec<u32> = vec![0, 1, 3, 0];
        let spans = bio_merge(&offsets, &ids, &labels());
        assert_eq!(spans.len(), 2);
        assert_eq!(spans[0].kind, EntityKind::Person);
        assert_eq!(spans[1].kind, EntityKind::Org);
    }

    // Live model test — opt-in only (requires ~270 MB download + network).
    // Enable locally with:  SANITIZE_NER_E2E=1 cargo test --features sanitize --ignored
    #[test]
    #[ignore]
    fn ner_live_scan_detects_person() {
        if std::env::var("SANITIZE_NER_E2E").is_err() {
            eprintln!("SANITIZE_NER_E2E not set — skipping live model test");
            return;
        }
        let text = "Marie Curie worked at the University of Paris.";
        let spans = scan(text).expect("live NER scan");
        assert!(
            spans.iter().any(|s| s.kind == EntityKind::Person),
            "expected at least one PERSON span; got {spans:?}"
        );
    }
}
