-- SEC-HMAC: Migrate api_keys.key_hash from BCrypt VARCHAR(72) to HMAC-SHA256 VARCHAR(64).
--
-- API keys are high-entropy random strings, not passwords. BCrypt's cost factor
-- compensates for low-entropy inputs and adds only latency here (~250 ms/request).
-- HMAC-SHA256 with a server-side pepper (MCP_HMAC_PEPPER, >= 32 chars) achieves
-- equivalent protection in microseconds. NIST FIPS 198-1.
--
-- The Flyway placeholder ${hmacPepper} is injected at migration time via:
--   spring.flyway.placeholders.hmacPepper=${MCP_HMAC_PEPPER}
-- pgcrypto is available from V1 (CREATE EXTENSION IF NOT EXISTS pgcrypto).

-- Step 1: drop the UNIQUE constraint temporarily (recreated after UPDATE)
ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS api_keys_key_hash_key;

-- Step 2: widen the column and clear existing BCrypt hashes (incompatible format)
ALTER TABLE api_keys ALTER COLUMN key_hash TYPE VARCHAR(64) USING NULL;

-- Step 3: set HMAC-SHA256 hashes for the two demo keys using the configured pepper
UPDATE api_keys
SET key_hash = encode(hmac('demo-readonly-key-001', '${hmacPepper}', 'sha256'), 'hex')
WHERE label = 'Demo Read-Only Client';

UPDATE api_keys
SET key_hash = encode(hmac('demo-admin-key-001', '${hmacPepper}', 'sha256'), 'hex')
WHERE label = 'Demo Admin Client';

-- Step 4: restore NOT NULL and UNIQUE constraints
ALTER TABLE api_keys ALTER COLUMN key_hash SET NOT NULL;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_key_hash_key UNIQUE (key_hash);
