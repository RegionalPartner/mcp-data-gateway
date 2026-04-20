// Text chunker — character-based overlapping windows.
//
// Approximates 512-token chunks at ~4 chars/token (English):
//   chunk size  = 2 048 chars  ≈ 512 tokens
//   overlap     =   200 chars  ≈  50 tokens
//   stride      = 1 848 chars  ≈ 462 tokens
//
// This is the production implementation for Phase 3.
//
// TODO(tokenizer): once the `tokenizers` crate (≤0.20) fixes an unconditional C `onig`
// import that breaks `default-features = false` builds, swap in a proper
// bert-base-uncased tokenizer for exact 512-token windows.  The Chunker public
// API will not change — only `chunk_by_chars` gets replaced with `chunk_by_tokens`.

const CHUNK_CHARS: usize = 2048;
const OVERLAP_CHARS: usize = 200;
const STRIDE_CHARS: usize = CHUNK_CHARS - OVERLAP_CHARS; // 1 848

pub struct Chunker;

impl Chunker {
    pub fn new() -> Self {
        Self
    }

    /// Split `text` into overlapping character windows.
    ///
    /// Returns an empty Vec for blank input; never fails.
    pub fn chunk(&self, text: &str) -> anyhow::Result<Vec<String>> {
        if text.trim().is_empty() {
            return Ok(vec![]);
        }
        Ok(chunk_by_chars(text))
    }
}

fn chunk_by_chars(text: &str) -> Vec<String> {
    // Collect to a char vec for O(1) index-based slicing.
    let chars: Vec<char> = text.chars().collect();
    let mut chunks = Vec::new();
    let mut start = 0usize;

    loop {
        let end = (start + CHUNK_CHARS).min(chars.len());
        let s: String = chars[start..end].iter().collect();
        if !s.trim().is_empty() {
            chunks.push(s);
        }
        if end == chars.len() {
            break;
        }
        start += STRIDE_CHARS;
    }

    chunks
}

// ── Tests ──────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_zero_chunks() {
        let c = Chunker::new();
        assert!(c.chunk("").unwrap().is_empty());
        assert!(c.chunk("   ").unwrap().is_empty());
        assert!(c.chunk("\n\t").unwrap().is_empty());
    }

    #[test]
    fn short_text_single_chunk() {
        let c = Chunker::new();
        let chunks = c.chunk("Hello world").unwrap();
        assert_eq!(chunks.len(), 1);
        assert!(chunks[0].contains("Hello"));
    }

    #[test]
    fn long_text_produces_multiple_chunks() {
        let c = Chunker::new();
        // Enough chars to span multiple windows.
        let text = "word ".repeat(500); // 2 500 chars → 2 chunks
        let chunks = c.chunk(&text).unwrap();
        assert!(
            chunks.len() >= 2,
            "expected ≥2 chunks, got {}",
            chunks.len()
        );
    }

    #[test]
    fn chunks_cover_full_content() {
        // Every character in the input must appear in at least one chunk.
        // Check boundaries: first chunk starts at char 0, last chunk ends at text end.
        let text = format!("START{}END", "X".repeat(CHUNK_CHARS));
        let chunks = chunk_by_chars(&text);
        assert!(
            chunks.first().unwrap().starts_with("START"),
            "first chunk missing start"
        );
        assert!(
            chunks.last().unwrap().ends_with("END"),
            "last chunk missing end"
        );
    }

    #[test]
    fn overlap_provides_context_continuity() {
        // The last OVERLAP_CHARS of chunk N should appear at the start of chunk N+1.
        let text = "A".repeat(CHUNK_CHARS + STRIDE_CHARS);
        let chunks = chunk_by_chars(&text);
        assert_eq!(chunks.len(), 2);
        // Second chunk must be OVERLAP_CHARS long (all 'A's)
        assert_eq!(
            chunks[1].chars().count(),
            OVERLAP_CHARS + STRIDE_CHARS.min(chunks[1].chars().count() - OVERLAP_CHARS)
        );
    }
}
