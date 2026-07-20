-- =============================================================================
-- Widen PII columns to hold Vault Transit ciphertext.
--
-- When Vault encryption is enabled, mobilenumber / altcontactnumber and AADHAAR
-- identifierid are stored as Vault Transit ciphertext (vault:v1:<base64>...),
-- which is ~60-70 characters even for short plaintext. The original column
-- widths (altcontactnumber varchar(20), identifierid varchar(64)) cannot hold
-- the ciphertext, so encrypted writes failed. These columns are documented as
-- "encrypted at rest via Vault"; widen them so the encryption path persists.
-- mobilenumber (varchar 256) already accommodates ciphertext.
-- =============================================================================

ALTER TABLE individual_v3
    ALTER COLUMN altcontactnumber TYPE character varying(256);

ALTER TABLE individual_identifier_v3
    ALTER COLUMN identifierid TYPE character varying(256);
