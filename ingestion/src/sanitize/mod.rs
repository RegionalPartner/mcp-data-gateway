// PII sanitization — public API.
//
// Goal: give the caller a single function `sanitize(text, ctx) -> Cow<str>` that
// returns the text unchanged (zero allocation, zero copy) when the `sanitize`
// feature is off, or a fully tokenized version when on.
//
// Pipeline when the feature is ON:
//   1. regex_scan::scan(text)     → deterministic hits (NIR, IBAN, E.164, email, UUID)
//   2. ner::scan(text)            → model-driven hits (PERSON, ORG, LOC) — MISC skipped
//   3. merge + de-overlap greedy-left-longest
//   4. vault::tokenize_span(...)  → mint stable, per-(workspace,kind) tokens
//   5. single-pass rebuild of the string with tokens substituted in
//
// Public types (`SanitizeCtx`, `EntityKind`, `Span`) are visible regardless of
// feature flags so callers can depend on a stable surface.  When the feature
// is off, `SanitizeCtx` is a marker struct and `sanitize` degenerates to
// `Cow::Borrowed(text)`.
//
// Security rules (repo-wide):
//   - NEVER log raw PII or vault ciphertext.
//   - No `Debug` derive on types carrying secret material — use the manual
//     `<redacted>` impl pattern from `connector::zoho::ZohoCredentials`.
//
// NOTE: the bin target (`main.rs`) does not yet call `sanitize` on downloaded
// content — wiring it into the chunking pipeline is follow-up work deferred to
// a separate PR.  `#![allow(dead_code)]` silences the dead-code warnings from
// `cargo clippy -D warnings` until that wiring lands.
#![allow(dead_code)]

use std::borrow::Cow;

#[cfg(feature = "sanitize-regex")]
pub mod regex_scan;

#[cfg(feature = "sanitize-ner")]
pub mod ner;

#[cfg(feature = "sanitize-vault")]
pub mod vault;

// ---------------------------------------------------------------------------
// Public types (visible regardless of feature flags)
// ---------------------------------------------------------------------------

/// Kinds of entity this module knows how to recognize and tokenize.
///
/// Kept behind a stable, non-exhaustive enum so that callers compiled against
/// a version WITHOUT a particular feature can still pattern-match safely on
/// the subset they need.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum EntityKind {
    /// French social security number (Numéro d'Inscription au Répertoire).
    Nir,
    /// International Bank Account Number (FR or any EU country).
    Iban,
    /// International E.164 phone number.
    Phone,
    /// Email address (RFC-5321 simplified).
    Email,
    /// Canonical UUID (8-4-4-4-12 hex).
    Uuid,
    /// Personal name (detected by NER).
    Person,
    /// Organisation name (detected by NER).
    Org,
    /// Location (detected by NER).
    Loc,
}

impl EntityKind {
    /// Stable string label used as a column value in `pii_token_map.entity_kind`
    /// and as a prefix in the minted token (e.g. `PII_EMAIL_42`).
    pub const fn as_label(self) -> &'static str {
        match self {
            EntityKind::Nir => "NIR",
            EntityKind::Iban => "IBAN",
            EntityKind::Phone => "PHONE",
            EntityKind::Email => "EMAIL",
            EntityKind::Uuid => "UUID",
            EntityKind::Person => "PERSON",
            EntityKind::Org => "ORG",
            EntityKind::Loc => "LOC",
        }
    }
}

/// A single detected hit — a byte-offset range into the original `&str` and
/// the kind of entity detected.  Byte offsets must always fall on UTF-8 char
/// boundaries (the scanners only produce hits on boundaries).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Span {
    pub start: usize,
    pub end: usize,
    pub kind: EntityKind,
}

impl Span {
    pub fn len(&self) -> usize {
        self.end.saturating_sub(self.start)
    }

    pub fn is_empty(&self) -> bool {
        self.start >= self.end
    }
}

/// Context for a sanitize() call.
///
/// Even when the feature is off we keep the struct around so the caller side
/// doesn't need `#[cfg]` guards.  When features are compiled in, the workspace
/// id and vault handle let us mint per-workspace tokens.
#[derive(Clone)]
pub struct SanitizeCtx {
    /// Workspace owning the sanitized text — drives per-tenant HMAC key
    /// derivation and counter namespacing.
    #[cfg_attr(not(feature = "sanitize-vault"), allow(dead_code))]
    pub workspace_id: uuid::Uuid,

    /// Optional handle to the vault backend.  When present, detected spans are
    /// tokenized against the DB; when absent, spans are substituted with a
    /// deterministic placeholder string (`[PII:KIND]`).
    #[cfg(feature = "sanitize-vault")]
    pub vault: Option<std::sync::Arc<vault::PiiVault>>,
}

impl std::fmt::Debug for SanitizeCtx {
    // Manual impl: never accidentally print vault handle internals.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SanitizeCtx")
            .field("workspace_id", &self.workspace_id)
            .field("vault", &"<redacted>")
            .finish()
    }
}

impl SanitizeCtx {
    /// Construct a sanitize context with no vault — detected spans are
    /// replaced with `[PII:KIND]` placeholders instead of being tokenized.
    pub fn placeholder_only(workspace_id: uuid::Uuid) -> Self {
        Self {
            workspace_id,
            #[cfg(feature = "sanitize-vault")]
            vault: None,
        }
    }
}

// ---------------------------------------------------------------------------
// sanitize(): feature-gated entry point
// ---------------------------------------------------------------------------
//
// Three compile-time shapes:
//
//   (a) No scanner feature → zero-cost `Cow::Borrowed(text)` pass-through.
//       This is the default; LLVM elides the call completely in release.
//
//   (b) `sanitize-regex` is on (possibly alongside `sanitize-vault`) but
//       the umbrella `sanitize` flag is off → regex-only pipeline.
//
//   (c) Umbrella `sanitize` flag is on → regex + NER pipeline.
//
// The three arms are mutually exclusive so there is exactly one definition of
// `sanitize` at any given cfg combination.

// (a) No scanner enabled.
#[cfg(all(not(feature = "sanitize"), not(feature = "sanitize-regex")))]
pub fn sanitize<'a>(text: &'a str, _ctx: &SanitizeCtx) -> Cow<'a, str> {
    Cow::Borrowed(text)
}

// (b) Regex-only pipeline.
#[cfg(all(feature = "sanitize-regex", not(feature = "sanitize")))]
pub fn sanitize<'a>(text: &'a str, ctx: &SanitizeCtx) -> Cow<'a, str> {
    let spans: Vec<Span> = regex_scan::scan(text).collect();
    let merged = merge_and_dedup(spans);
    if merged.is_empty() {
        return Cow::Borrowed(text);
    }
    Cow::Owned(substitute(text, &merged, ctx))
}

// (c) Full pipeline — regex + NER.
#[cfg(feature = "sanitize")]
pub fn sanitize<'a>(text: &'a str, ctx: &SanitizeCtx) -> Cow<'a, str> {
    let mut spans: Vec<Span> = Vec::new();

    #[cfg(feature = "sanitize-regex")]
    spans.extend(regex_scan::scan(text));

    #[cfg(feature = "sanitize-ner")]
    if let Ok(ner_spans) = ner::scan(text) {
        spans.extend(ner_spans);
    }

    let merged = merge_and_dedup(spans);
    if merged.is_empty() {
        return Cow::Borrowed(text);
    }

    Cow::Owned(substitute(text, &merged, ctx))
}

// ---------------------------------------------------------------------------
// merge_and_dedup + substitute — private to this module
// ---------------------------------------------------------------------------

// Greedy-left-longest dedup.
//
// The regex + NER passes can produce overlapping hits (e.g. a UUID that
// happens to be contained inside a longer NER span).  We keep the leftmost
// span; on a tie of start offsets we keep the longest.
#[cfg(any(feature = "sanitize-regex", feature = "sanitize"))]
fn merge_and_dedup(mut spans: Vec<Span>) -> Vec<Span> {
    if spans.is_empty() {
        return spans;
    }
    // Sort by start asc, then by length desc so equal-start wins the longer one.
    spans.sort_by(|a, b| a.start.cmp(&b.start).then_with(|| b.len().cmp(&a.len())));

    let mut out: Vec<Span> = Vec::with_capacity(spans.len());
    let mut cursor = 0usize;
    for s in spans {
        if s.start < cursor {
            continue; // overlapped by previous; skip
        }
        cursor = s.end;
        out.push(s);
    }
    out
}

// Single-pass string rebuild: write the non-span slices verbatim and emit
// one token per span.  When `sanitize-vault` is on we ask the vault for a
// stable token; otherwise we emit `[PII:KIND]`.
#[cfg(any(feature = "sanitize-regex", feature = "sanitize"))]
fn substitute(text: &str, spans: &[Span], ctx: &SanitizeCtx) -> String {
    // Over-reserve: most tokens are shorter than the original span.
    let mut out = String::with_capacity(text.len());
    let mut cursor = 0usize;
    for s in spans {
        // Guard against non-char-boundary spans (should never happen but cheap).
        if s.start > text.len() || s.end > text.len() {
            continue;
        }
        if !text.is_char_boundary(s.start) || !text.is_char_boundary(s.end) {
            continue;
        }
        out.push_str(&text[cursor..s.start]);
        out.push_str(&render_token(&text[s.start..s.end], s.kind, ctx));
        cursor = s.end;
    }
    out.push_str(&text[cursor..]);
    out
}

#[cfg(any(feature = "sanitize-regex", feature = "sanitize"))]
fn render_token(raw: &str, kind: EntityKind, ctx: &SanitizeCtx) -> String {
    let _ = raw; // unused without vault
    let _ = ctx; // unused without vault
    #[cfg(feature = "sanitize-vault")]
    {
        if let Some(ref v) = ctx.vault {
            // Blocking here is intentionally avoided — the public API is async
            // at the connector layer, not here.  The vault tokenize path is
            // only used when the caller has built a `PiiVault` and wants live
            // DB writes.  See `vault::PiiVault::tokenize_blocking` docs.
            if let Ok(tok) = v.tokenize_blocking(ctx.workspace_id, kind, raw) {
                return tok;
            }
            // Fall through to placeholder on vault error — caller sees the
            // placeholder and the vault module has already logged the error.
        }
    }
    format!("[PII:{}]", kind.as_label())
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    // Zero-cost invariant: when no scanner feature is enabled, sanitize() must
    // return Cow::Borrowed with a byte-slice pointer identical to the input.
    #[test]
    #[cfg(all(not(feature = "sanitize"), not(feature = "sanitize-regex")))]
    fn sanitize_returns_borrowed_when_disabled() {
        let input = "user@example.com placed an order";
        let ctx = SanitizeCtx::placeholder_only(Uuid::nil());
        let out = sanitize(input, &ctx);
        match out {
            Cow::Borrowed(b) => {
                assert_eq!(
                    b.as_ptr(),
                    input.as_ptr(),
                    "expected borrow to preserve pointer"
                );
                assert_eq!(b, input);
            }
            Cow::Owned(_) => panic!("expected Cow::Borrowed when feature is off"),
        }
    }

    // When a scanner is enabled but no hits are found, sanitize() should still
    // return Cow::Borrowed to preserve the zero-copy path on clean inputs.
    #[test]
    #[cfg(feature = "sanitize-regex")]
    fn sanitize_returns_borrowed_when_no_hits() {
        let input = "nothing sensitive to see here";
        let ctx = SanitizeCtx::placeholder_only(Uuid::nil());
        let out = sanitize(input, &ctx);
        assert!(
            matches!(out, Cow::Borrowed(_)),
            "expected borrow on clean text"
        );
    }

    #[test]
    fn span_len_and_empty() {
        let s = Span {
            start: 3,
            end: 10,
            kind: EntityKind::Email,
        };
        assert_eq!(s.len(), 7);
        assert!(!s.is_empty());

        let e = Span {
            start: 5,
            end: 5,
            kind: EntityKind::Email,
        };
        assert!(e.is_empty());
    }

    #[test]
    fn entity_kind_label_is_stable() {
        assert_eq!(EntityKind::Email.as_label(), "EMAIL");
        assert_eq!(EntityKind::Nir.as_label(), "NIR");
        assert_eq!(EntityKind::Iban.as_label(), "IBAN");
    }

    // Exercise the dedup helper — enabled only when one of the scanner features
    // is compiled in (otherwise the helper doesn't exist).
    #[test]
    #[cfg(any(feature = "sanitize-regex", feature = "sanitize"))]
    fn merge_and_dedup_greedy_left_longest() {
        use super::merge_and_dedup;
        let spans = vec![
            Span {
                start: 0,
                end: 10,
                kind: EntityKind::Email,
            },
            Span {
                start: 2,
                end: 4,
                kind: EntityKind::Uuid,
            }, // overlapped
            Span {
                start: 12,
                end: 18,
                kind: EntityKind::Iban,
            },
            Span {
                start: 12,
                end: 15,
                kind: EntityKind::Phone,
            }, // tie-on-start loser
        ];
        let out = merge_and_dedup(spans);
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].start, 0);
        assert_eq!(out[0].end, 10);
        assert_eq!(out[1].start, 12);
        assert_eq!(out[1].end, 18);
    }

    #[test]
    #[cfg(feature = "sanitize-regex")]
    fn sanitize_regex_only_substitutes_email() {
        let input = "contact alice@example.com about the order";
        let ctx = SanitizeCtx::placeholder_only(Uuid::nil());
        let out = sanitize(input, &ctx);
        assert!(
            out.contains("[PII:EMAIL]"),
            "expected placeholder; got: {out}"
        );
        assert!(!out.contains("alice@example.com"));
    }

    #[test]
    fn debug_impl_redacts_vault() {
        let ctx = SanitizeCtx::placeholder_only(Uuid::nil());
        let s = format!("{:?}", ctx);
        assert!(
            s.contains("<redacted>"),
            "vault field should be redacted; got: {s}"
        );
    }
}
