-- v3 contact + version + passwordGenerated columns on tenant_v1.
--
-- The five contact fields (phone, address, city, state, pincode) are all
-- declared nullable per spec (TenantCreateRequest treats them as optional).
-- The TenantEntity uses *string pointers, so a nil pointer round-trips to
-- SQL NULL on insert — the columns MUST allow NULL.
--
-- `version` and `passwordGenerated` are server-managed and always written
-- by the service on every Create / Update, so they stay NOT NULL with a
-- safe DEFAULT for the rare case a future code path forgets to set them.

ALTER TABLE tenant_v1
    ADD COLUMN IF NOT EXISTS phone             VARCHAR(20),
    ADD COLUMN IF NOT EXISTS address           VARCHAR(512),
    ADD COLUMN IF NOT EXISTS city              VARCHAR(128),
    ADD COLUMN IF NOT EXISTS state             VARCHAR(128),
    ADD COLUMN IF NOT EXISTS pincode           VARCHAR(10),
    ADD COLUMN IF NOT EXISTS version           INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS passwordGenerated BOOLEAN NOT NULL DEFAULT false;