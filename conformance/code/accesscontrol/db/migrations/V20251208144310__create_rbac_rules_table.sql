CREATE TABLE IF NOT EXISTS access_rbac_rules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(255) NOT NULL,
    role_names  TEXT[]       NOT NULL,
    http_method VARCHAR(10)  NOT NULL,
    path        TEXT         NOT NULL,
    effect      VARCHAR(10)  NOT NULL,
    priority    INTEGER      NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    constraints JSONB,
    description TEXT,
    requestid   TEXT,
    created_by  TEXT,
    modified_by TEXT,
    created_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL
);

-- Lookup index: covers the per-tenant rule filtering used by ListRbacRules
-- (e.g. {tenant_id, http_method, enabled} combos) and the tenant-only
-- delete-by-tenant path via the leftmost-prefix rule.
CREATE INDEX IF NOT EXISTS idx_access_rbac_rules_lookup
ON access_rbac_rules (tenant_id, http_method, enabled);

-- Ordering index: ListRbacRules and admin list views ORDER BY created_at DESC
-- within a tenant. Postgres reads this B-tree in either direction, so the
-- query gets a free sort. Without it, a tenant with thousands of rules pays
-- O(N log N) per list call.
CREATE INDEX IF NOT EXISTS idx_access_rbac_rules_tenant_created
ON access_rbac_rules (tenant_id, created_at);
