-- SEC-HMAC: Migrate api_keys.key_hash from BCrypt VARCHAR(72) to HMAC-SHA256 VARCHAR(64).
--
-- API keys are high-entropy random strings, not passwords. BCrypt's cost factor
-- compensates for low-entropy inputs and adds only latency here (~250 ms/request).
-- HMAC-SHA256 with a server-side pepper (MCP_HMAC_PEPPER, >= 32 chars) achieves
-- equivalent protection in microseconds. NIST FIPS 198-1.
--
-- The Flyway placeholder 'hmacPepper' is injected at migration time via:
--   spring.flyway.placeholders.hmacPepper (mapped to MCP_HMAC_PEPPER in application.yaml)
-- pgcrypto is available from V1 (CREATE EXTENSION IF NOT EXISTS pgcrypto).
--
-- Note: any non-demo keys present before this migration will retain their BCrypt hashes
-- (which will fail HMAC authentication) and must be re-keyed by their owners.

-- Step 1: drop the UNIQUE constraint temporarily (recreated after UPDATE)
ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS api_keys_key_hash_key;

-- Step 2: widen the column from VARCHAR(72) to VARCHAR(64).
-- BCrypt hashes are 60 chars; HMAC-SHA256 hex hashes are 64 chars — both fit.
-- No USING clause needed: existing BCrypt values stay in place for non-demo rows
-- (they will fail authentication and trigger re-keying for production keys).
ALTER TABLE api_keys ALTER COLUMN key_hash TYPE VARCHAR(64);

-- Step 3: overwrite demo key hashes with HMAC-SHA256 using the configured pepper
UPDATE api_keys
SET key_hash = encode(hmac('demo-readonly-key-001', '${hmacPepper}', 'sha256'), 'hex')
WHERE label = 'Demo Read-Only Client';

UPDATE api_keys
SET key_hash = encode(hmac('demo-admin-key-001', '${hmacPepper}', 'sha256'), 'hex')
WHERE label = 'Demo Admin Client';

-- Step 4: restore UNIQUE constraint (NOT NULL already holds from V1)
ALTER TABLE api_keys ADD CONSTRAINT api_keys_key_hash_key UNIQUE (key_hash);
