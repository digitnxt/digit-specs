CREATE TABLE IF NOT EXISTS access_rbac_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(255) NOT NULL,
    role_names TEXT[] NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    path TEXT NOT NULL,
    effect VARCHAR(10) NOT NULL,
    priority INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    constraints JSONB,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_access_rbac_rules_lookup
ON access_rbac_rules (tenant_id, http_method, enabled);
