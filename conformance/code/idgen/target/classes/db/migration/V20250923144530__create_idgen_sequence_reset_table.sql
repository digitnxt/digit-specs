-- Tracks resets for any scope (daily, monthly, yearly, etc.)
CREATE TABLE IF NOT EXISTS idgen_sequence_resets (
    id UUID PRIMARY KEY,
    templatecode VARCHAR(64) NOT NULL,
    tenantid VARCHAR(64) NOT NULL,
    scopekey   VARCHAR(32) NOT NULL,  -- e.g., '2025-09-19' for daily, '2025-09' for monthly
    lastvalue  BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenantid, templatecode, scopekey)
);
