/// AES-256-GCM crypto — wire-format compatible with ContentEncryptor.java.
///
/// Wire format: [12B IV][ciphertext + 16B GCM tag]
/// Key source:  64 hex chars (32 bytes), from MCP_CONTENT_KEY.
///
/// Uses ring (not aes-gcm) to match the FIPS-audited backend already in use.
/// This module is intentionally byte-identical to tools/key-rotation/src/crypto.rs —
/// any change here must be mirrored there (and vice versa) to preserve wire compatibility.
use anyhow::{anyhow, Result};
use ring::aead::{
    Aad, BoundKey, Nonce, NonceSequence, SealingKey, UnboundKey, AES_256_GCM, NONCE_LEN,
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

    #[test]
    fn round_trip() {
        let key = [0u8; 32];
        let plain = b"hello world";
        let wire = encrypt(&key, plain).unwrap();
        assert_eq!(wire.len(), IV_LEN + plain.len() + 16);
    }

    #[test]
    fn parse_key_valid() {
        let hex = "a".repeat(64);
        let key = parse_key(&hex).unwrap();
        assert_eq!(key, [0xAAu8; 32]);
    }

    #[test]
    fn parse_key_wrong_length() {
        assert!(parse_key(&"0".repeat(32)).is_err());
        assert!(parse_key(&"0".repeat(66)).is_err());
    }
}
