CREATE TABLE IF NOT EXISTS access_jbac_rules_v3 (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              VARCHAR(255) NOT NULL,
    name                   VARCHAR(255) NOT NULL,
    path_pattern           TEXT         NOT NULL,
    methods                TEXT[]       NOT NULL,
    enforcement            VARCHAR(50)  NOT NULL,
    parent_implies_children BOOLEAN     NOT NULL DEFAULT TRUE,
    extract_jurisdiction   JSONB,
    description            TEXT,
    requestid              TEXT,
    created_by             TEXT,
    modified_by            TEXT,
    created_at             BIGINT       NOT NULL,
    updated_at             BIGINT       NOT NULL
);

-- Lookup index: covers the per-tenant rule filtering used by ListJbacRules
-- and the tenant-only delete-by-tenant path.
CREATE INDEX IF NOT EXISTS idx_access_jbac_rules_lookup
ON access_jbac_rules_v3 (tenant_id);

-- Ordering index: ListJbacRules ORDER BY created_at DESC within a tenant.
-- Postgres reads this B-tree in either direction, so the query gets a free
-- sort. Without it, a tenant with many rules pays O(N log N) per list call.
CREATE INDEX IF NOT EXISTS idx_access_jbac_rules_tenant_created
ON access_jbac_rules_v3 (tenant_id, created_at);
