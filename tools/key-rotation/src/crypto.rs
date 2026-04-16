/// AES-256-GCM crypto — wire-format compatible with ContentEncryptor.java.
///
/// Wire format: [12B IV][ciphertext + 16B GCM tag]
/// Key source:  64 hex chars (32 bytes), from MCP_CONTENT_KEY / --old-key / --new-key
///
/// Uses ring (not aes-gcm) to match the FIPS-audited backend already in use.
use anyhow::{anyhow, Result};
use ring::aead::{
    Aad, BoundKey, Nonce, NonceSequence, OpeningKey, SealingKey, UnboundKey, AES_256_GCM,
    NONCE_LEN,
};
use ring::error::Unspecified;
use ring::rand::{SecureRandom, SystemRandom};

pub const IV_LEN: usize = NONCE_LEN; // 12 bytes — matches Java's IV_LENGTH_BYTES

/// Parse a 64-char hex string into a 32-byte key.
/// Mirrors ContentEncryptor.java: HexFormat.of().parseHex(hexKey)
pub fn parse_key(hex_str: &str) -> Result<[u8; 32]> {
    let bytes = hex::decode(hex_str.trim())?;
    bytes
        .try_into()
        .map_err(|_| anyhow!("key must be exactly 64 hex chars (32 bytes); got {} bytes", hex_str.len() / 2))
}

/// AES-256-GCM encrypt with a fresh random IV.
///
/// Output wire format: [12B IV][ciphertext + 16B GCM tag]
/// Matches Java: System.arraycopy(iv, 0, result, 0, IV_LENGTH_BYTES) then ciphertext.
pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>> {
    let rng = SystemRandom::new();
    let mut iv = [0u8; IV_LEN];
    rng.fill(&mut iv).map_err(|_| anyhow!("RNG failed"))?;

    let unbound = UnboundKey::new(&AES_256_GCM, key).map_err(|_| anyhow!("invalid key length"))?;
    let mut sealing = SealingKey::new(unbound, OneShot::new(iv));

    let mut buf = plaintext.to_vec();
    sealing
        .seal_in_place_append_tag(Aad::empty(), &mut buf)
        .map_err(|_| anyhow!("seal failed"))?;

    // [12B IV] ++ [ciphertext + 16B tag]
    let mut out = Vec::with_capacity(IV_LEN + buf.len());
    out.extend_from_slice(&iv);
    out.extend_from_slice(&buf);
    Ok(out)
}

/// AES-256-GCM decrypt.
///
/// Expects wire format: [12B IV][ciphertext + 16B GCM tag]
/// Returns Err if the key is wrong OR the ciphertext is tampered.
pub fn decrypt(key: &[u8; 32], wire: &[u8]) -> Result<Vec<u8>> {
    const MIN_LEN: usize = IV_LEN + 16; // IV + GCM tag (no plaintext)
    if wire.len() < MIN_LEN {
        return Err(anyhow!(
            "wire data too short: {} bytes (minimum {})",
            wire.len(),
            MIN_LEN
        ));
    }

    let iv: [u8; IV_LEN] = wire[..IV_LEN].try_into().unwrap();
    let unbound = UnboundKey::new(&AES_256_GCM, key).map_err(|_| anyhow!("invalid key length"))?;
    let mut opening = OpeningKey::new(unbound, OneShot::new(iv));

    let mut buf = wire[IV_LEN..].to_vec();
    let plain = opening
        .open_in_place(Aad::empty(), &mut buf)
        .map_err(|_| anyhow!("decryption failed — wrong key or tampered data"))?;

    Ok(plain.to_vec())
}

// ── NonceSequence that fires exactly once ──────────────────────────────────

struct OneShot {
    used: bool,
    iv: [u8; IV_LEN],
}

impl OneShot {
    fn new(iv: [u8; IV_LEN]) -> Self {
        Self { used: false, iv }
    }
}

impl NonceSequence for OneShot {
    fn advance(&mut self) -> Result<Nonce, Unspecified> {
        if self.used {
            return Err(Unspecified); // called twice — programming error
        }
        self.used = true;
        Ok(Nonce::assume_unique_for_key(self.iv))
    }
}

// ── Tests ──────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// Encrypt → decrypt round trip with zero test key.
    #[test]
    fn round_trip() {
        let key = [0u8; 32];
        let plain = b"hello world";
        let wire = encrypt(&key, plain).unwrap();
        // Wire must be: 12 (IV) + 11 (plaintext) + 16 (GCM tag) = 39 bytes
        assert_eq!(wire.len(), IV_LEN + plain.len() + 16);
        let got = decrypt(&key, &wire).unwrap();
        assert_eq!(got, plain);
    }

    /// Wrong key must fail authentication.
    #[test]
    fn wrong_key_fails() {
        let key_a = [0xAAu8; 32];
        let key_b = [0xBBu8; 32];
        let wire = encrypt(&key_a, b"secret").unwrap();
        assert!(decrypt(&key_b, &wire).is_err());
    }

    /// Flipping any bit in the ciphertext must fail GCM tag check.
    #[test]
    fn tampered_ciphertext_fails() {
        let key = [0u8; 32];
        let mut wire = encrypt(&key, b"secret data").unwrap();
        wire[IV_LEN + 2] ^= 0xFF; // flip bits in ciphertext area
        assert!(decrypt(&key, &wire).is_err());
    }

    /// Short data must return an error, not panic.
    #[test]
    fn too_short_fails() {
        let key = [0u8; 32];
        assert!(decrypt(&key, &[0u8; 10]).is_err());
    }

    /// parse_key happy path.
    #[test]
    fn parse_key_valid() {
        let hex = "a".repeat(64);
        let key = parse_key(&hex).unwrap();
        assert_eq!(key, [0xAAu8; 32]);
    }

    /// parse_key wrong length.
    #[test]
    fn parse_key_wrong_length() {
        assert!(parse_key(&"0".repeat(32)).is_err());
        assert!(parse_key(&"0".repeat(66)).is_err());
    }

    // TODO(Phase 3 cross-language test):
    // Add a test with a ciphertext produced by ContentEncryptor.java using a
    // known key and known IV, to prove byte-level wire-format compatibility.
    // Run: MCP_CONTENT_KEY=<64-hex> ./gradlew test -Dtest=ContentEncryptorCrossLangIT
    // Copy the base64-encoded wire bytes here and assert decrypt matches the plaintext.
}
