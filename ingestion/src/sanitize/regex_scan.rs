// Deterministic regex-based PII detection.
//
// One `RegexSet` compiled once behind a `Lazy`.  Every match candidate is
// validated (Luhn-97 for FR NIR, mod-97 for IBAN, E.164 length for phones)
// before emitting a `Span`.  This keeps the false-positive rate low enough
// that the downstream NER pass only has to handle PER/ORG/LOC.
//
// Emits hits in the order found.  The caller is responsible for sorting and
// de-overlapping (see `super::merge_and_dedup`).

use once_cell::sync::Lazy;
use regex::Regex;

use super::{EntityKind, Span};

// ---------------------------------------------------------------------------
// Patterns
// ---------------------------------------------------------------------------
//
// The capture groups are used as-is by the validators.  Each pattern is as
// loose as the format allows so the validator (Luhn / mod-97 / E.164 length)
// can reject invalid hits.

// FR social security number (Numéro d'Inscription au Répertoire):
//   S YYMM [DEP] CCC XXX [KK]
//   where S = 1|2|3, YY = year, MM = month 01–12 (+ 20,30,40 for variants),
//   DEP = 2 digits or "2A"/"2B" (Corsica), CCC = commune, XXX = order, KK = Luhn.
// We do a loose match and let `is_valid_nir` enforce the modulo-97 rule.
// Match on word boundary so "X9180473..." doesn't swallow adjacent digits.
static RE_FR_NIR: Lazy<Regex> = Lazy::new(|| {
    // spaces are tolerated between groups; pattern is lax on purpose.
    Regex::new(
        r"(?x)
        \b
        (?P<nir>[123]
          \s?\d{2}         # YY
          \s?\d{2}         # MM  (01..12, 20,30,40,41,42,50)
          \s?(?:2A|2B|\d{2}) # DEP
          \s?\d{3}         # commune
          \s?\d{3}         # order
          \s?\d{2})        # Luhn key
        \b",
    )
    .expect("FR NIR regex invariant")
});

// IBAN: 2 letters + 2 digits + up to 30 alphanum (BBAN).  EU IBANs range
// from 15 (NO, length 15) to 34 chars total.  FR is always 27.  Use a
// lookbehind-free word boundary and let `is_valid_iban` enforce mod-97.
static RE_IBAN: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b").expect("IBAN regex invariant"));

// E.164: '+' + 1..15 digits.  Digits only, no separators (callers generally
// sanitize whitespace before storing — we still see raw strings in practice
// but per spec the leading '+' and all digits must be contiguous).
static RE_E164: Lazy<Regex> =
    Lazy::new(|| Regex::new(r"\+[1-9]\d{6,14}\b").expect("E.164 regex invariant"));

// RFC-5321-ish email — not strict, on-purpose loose so `mailto:` prefixes
// aren't needed.  De-overlap happens upstream.
static RE_EMAIL: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}").expect("email regex invariant")
});

// Canonical UUID (8-4-4-4-12 hex, any case).
static RE_UUID: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b")
        .expect("UUID regex invariant")
});

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/// Scan `text` for deterministic PII hits.
///
/// Returns an iterator over `Span` hits in document order.  Overlaps are
/// possible; the caller handles de-overlap.
pub fn scan(text: &str) -> impl Iterator<Item = Span> + '_ {
    let mut hits: Vec<Span> = Vec::new();

    // NIR — validated with Luhn-97.
    for m in RE_FR_NIR.captures_iter(text) {
        if let Some(cap) = m.name("nir") {
            let candidate = cap.as_str();
            if is_valid_nir(candidate) {
                hits.push(Span {
                    start: cap.start(),
                    end: cap.end(),
                    kind: EntityKind::Nir,
                });
            }
        }
    }

    // IBAN — validated with mod-97.
    for m in RE_IBAN.find_iter(text) {
        if is_valid_iban(m.as_str()) {
            hits.push(Span {
                start: m.start(),
                end: m.end(),
                kind: EntityKind::Iban,
            });
        }
    }

    // Phone (E.164).
    for m in RE_E164.find_iter(text) {
        hits.push(Span {
            start: m.start(),
            end: m.end(),
            kind: EntityKind::Phone,
        });
    }

    // Email.
    for m in RE_EMAIL.find_iter(text) {
        hits.push(Span {
            start: m.start(),
            end: m.end(),
            kind: EntityKind::Email,
        });
    }

    // UUID.
    for m in RE_UUID.find_iter(text) {
        hits.push(Span {
            start: m.start(),
            end: m.end(),
            kind: EntityKind::Uuid,
        });
    }

    hits.into_iter()
}

// ---------------------------------------------------------------------------
// Validators
// ---------------------------------------------------------------------------

/// Validate a FR NIR (Numéro d'Inscription au Répertoire) checksum.
///
/// Algorithm:
///   1. Strip spaces from input.
///   2. For Corsica, replace "2A" → "19" and "2B" → "18" in the département
///      group (positions 5..7 in the compacted 15-char NIR).
///   3. The last 2 digits are the "clé"; the first 13 form N.
///   4. Expected clé = 97 - (N mod 97).
pub fn is_valid_nir(raw: &str) -> bool {
    let compact: String = raw.chars().filter(|c| !c.is_whitespace()).collect();
    if compact.len() != 15 {
        return false;
    }
    // We need to normalize the département (positions 5..7 in 0-indexed).
    let mut normalized = String::with_capacity(15);
    normalized.push_str(&compact[0..5]); // S + YY + MM
    let dep = &compact[5..7];
    match dep {
        "2A" => normalized.push_str("19"),
        "2B" => normalized.push_str("18"),
        _ => {
            if !dep.chars().all(|c| c.is_ascii_digit()) {
                return false;
            }
            normalized.push_str(dep);
        }
    }
    normalized.push_str(&compact[7..]); // commune + order + key
    if normalized.chars().any(|c| !c.is_ascii_digit()) {
        return false;
    }
    // 13-digit N and 2-digit key.
    let (n_str, key_str) = normalized.split_at(13);
    let n: u64 = match n_str.parse() {
        Ok(v) => v,
        Err(_) => return false,
    };
    let key: u64 = match key_str.parse() {
        Ok(v) => v,
        Err(_) => return false,
    };
    let expected = 97u64 - (n % 97u64);
    expected == key
}

/// Validate an IBAN checksum (ISO-13616, mod-97 == 1).
///
/// Algorithm:
///   1. Move the first 4 characters (country + check digits) to the end.
///   2. Replace letters A..Z with 10..35 (A=10, B=11, ...).
///   3. Compute the resulting big integer modulo 97; must equal 1.
pub fn is_valid_iban(raw: &str) -> bool {
    // Strip whitespace that shouldn't be in our regex hit but just in case.
    let compact: String = raw.chars().filter(|c| !c.is_whitespace()).collect();
    if compact.len() < 15 || compact.len() > 34 {
        return false;
    }
    if !compact
        .chars()
        .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
    {
        return false;
    }
    // 1. rotate first 4 chars to the end
    let (head, tail) = compact.split_at(4);
    let rotated: String = format!("{tail}{head}");
    // 2. letter-to-digit substitution
    let mut numeric = String::with_capacity(rotated.len() * 2);
    for c in rotated.chars() {
        if c.is_ascii_digit() {
            numeric.push(c);
        } else if c.is_ascii_uppercase() {
            let v = (c as u8 - b'A') + 10;
            numeric.push_str(&v.to_string());
        } else {
            return false;
        }
    }
    // 3. mod-97 via streaming digit consumption (avoid big-int dep).
    let mut rem: u64 = 0;
    for d in numeric.chars() {
        rem = (rem * 10 + (d as u64 - b'0' as u64)) % 97;
    }
    rem == 1
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // ---- Luhn-97 (NIR) ----------------------------------------------------

    #[test]
    fn nir_valid_metropolitan_example() {
        // A known-good synthetic NIR.  Sex=1, year=85, month=12, dép=75 (Paris),
        // commune=108, order=111, expected key computed below.
        //   N = 1 85 12 75 108 111 = 1851275108111
        //   key = 97 - (1851275108111 % 97)
        let n: u64 = 1_851_275_108_111;
        let key = 97 - (n % 97);
        let nir = format!("1 85 12 75 108 111 {:02}", key);
        assert!(is_valid_nir(&nir), "expected valid NIR {nir:?}");

        let hits: Vec<_> = scan(&format!("ref: {nir} tail")).collect();
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].kind, EntityKind::Nir);
    }

    #[test]
    fn nir_invalid_luhn_is_not_flagged() {
        // Same shape, but wrong key: NIR should fail validation and NOT show up
        // in the scan output.
        let bad = "1 85 12 75 108 111 00";
        assert!(!is_valid_nir(bad));
        let hits: Vec<_> = scan(&format!("junk {bad} junk")).collect();
        assert!(
            !hits.iter().any(|h| h.kind == EntityKind::Nir),
            "invalid NIR must not produce a hit; got {hits:?}"
        );
    }

    #[test]
    fn nir_corsica_2a_maps_to_19() {
        // Sex=2, year=90, month=05, dép=2A → substituted as 19.
        //   N = 2 90 05 19 042 123 = 2900519042123
        //   key = 97 - (N % 97)
        let n: u64 = 2_900_519_042_123;
        let key = 97 - (n % 97);
        let nir = format!("2 90 05 2A 042 123 {:02}", key);
        assert!(
            is_valid_nir(&nir),
            "Corsica 2A NIR should validate after substitution; got {nir}"
        );
    }

    #[test]
    fn nir_corsica_2b_maps_to_18() {
        let n: u64 = 2_900_518_042_123;
        let key = 97 - (n % 97);
        let nir = format!("2 90 05 2B 042 123 {:02}", key);
        assert!(is_valid_nir(&nir));
    }

    #[test]
    fn nir_short_or_long_rejected() {
        assert!(!is_valid_nir("123"));
        assert!(!is_valid_nir("1 85 12 75 108 111 00 99 99"));
    }

    // ---- IBAN mod-97 -----------------------------------------------------

    #[test]
    fn iban_valid_fr() {
        // Well-known public test vector from ISO-13616.
        let fr = "FR1420041010050500013M02606";
        assert!(is_valid_iban(fr), "FR test vector must validate");

        let hits: Vec<_> = scan(&format!("IBAN: {fr}.")).collect();
        assert!(hits.iter().any(|h| h.kind == EntityKind::Iban));
    }

    #[test]
    fn iban_valid_de() {
        // DE89370400440532013000 — ISO example.
        assert!(is_valid_iban("DE89370400440532013000"));
    }

    #[test]
    fn iban_invalid_checksum_rejected() {
        let bad = "FR0000041010050500013M02606"; // wrong check digits
        assert!(!is_valid_iban(bad));
        let hits: Vec<_> = scan(&format!("IBAN: {bad}")).collect();
        assert!(!hits.iter().any(|h| h.kind == EntityKind::Iban));
    }

    #[test]
    fn iban_too_short_rejected() {
        assert!(!is_valid_iban("FR1420"));
    }

    // ---- E.164 phone -----------------------------------------------------

    #[test]
    fn e164_happy_paths() {
        for phone in [
            "+33612345678",  // FR mobile
            "+14155552671",  // US
            "+442071838750", // UK
        ] {
            let hits: Vec<_> = scan(phone).collect();
            assert!(
                hits.iter().any(|h| h.kind == EntityKind::Phone),
                "expected phone match for {phone}: {hits:?}"
            );
        }
    }

    #[test]
    fn e164_rejects_non_plus() {
        let hits: Vec<_> = scan("0612345678").collect(); // no + prefix
        assert!(!hits.iter().any(|h| h.kind == EntityKind::Phone));
    }

    #[test]
    fn e164_rejects_zero_start() {
        // E.164 forbids leading 0 after the country-code prefix.
        let hits: Vec<_> = scan("+0123456789").collect();
        assert!(!hits.iter().any(|h| h.kind == EntityKind::Phone));
    }

    // ---- Email -----------------------------------------------------------

    #[test]
    fn email_happy_path() {
        let hits: Vec<_> = scan("Contact: alice.doe+test@example.co.uk.").collect();
        let em = hits.iter().find(|h| h.kind == EntityKind::Email);
        assert!(em.is_some());
    }

    #[test]
    fn email_multiple() {
        let hits: Vec<_> = scan("a@b.co and c@d.io").collect();
        let emails: Vec<_> = hits
            .iter()
            .filter(|h| h.kind == EntityKind::Email)
            .collect();
        assert_eq!(emails.len(), 2);
    }

    // ---- UUID ------------------------------------------------------------

    #[test]
    fn uuid_canonical_is_matched() {
        let hits: Vec<_> = scan("id=550e8400-e29b-41d4-a716-446655440000 end").collect();
        assert!(hits.iter().any(|h| h.kind == EntityKind::Uuid));
    }

    #[test]
    fn uuid_plain_hex_is_not_matched() {
        let hits: Vec<_> = scan("550e8400e29b41d4a716446655440000").collect();
        assert!(!hits.iter().any(|h| h.kind == EntityKind::Uuid));
    }

    // ---- Overlap handling at the de-overlap layer -----------------------

    // Simple sanity: an email that contains a UUID-looking string shouldn't
    // get swallowed as a UUID.  Since our regexes run independently and
    // upstream de-overlap keeps the left-longest hit, the email wins.
    #[test]
    fn email_beats_uuid_when_longer_left() {
        use super::super::merge_and_dedup;
        let text = "550e8400-e29b-41d4-a716-446655440000@example.com";
        let hits: Vec<_> = scan(text).collect();
        // both should pre-dedup
        assert!(hits.iter().any(|h| h.kind == EntityKind::Email));
        assert!(hits.iter().any(|h| h.kind == EntityKind::Uuid));
        let merged = merge_and_dedup(hits);
        // Email starts at 0 and is longer than the UUID; it wins.
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].kind, EntityKind::Email);
    }

    #[test]
    fn de_overlap_keeps_leftmost_on_partial_overlap() {
        use super::super::merge_and_dedup;
        // Two non-email hits that actually overlap.
        let hits = vec![
            Span {
                start: 0,
                end: 20,
                kind: EntityKind::Iban,
            },
            Span {
                start: 15,
                end: 30,
                kind: EntityKind::Uuid,
            },
        ];
        let merged = merge_and_dedup(hits);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].kind, EntityKind::Iban);
    }
}
