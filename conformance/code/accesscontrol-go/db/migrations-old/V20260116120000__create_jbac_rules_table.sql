CREATE TABLE IF NOT EXISTS access_jbac_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    path_pattern TEXT NOT NULL,
    methods TEXT[] NOT NULL,
    enforcement VARCHAR(50) NOT NULL,
    parent_implies_children BOOLEAN NOT NULL DEFAULT TRUE,
    extract_jurisdiction JSONB,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_access_jbac_rules_lookup
ON access_jbac_rules (tenant_id);
