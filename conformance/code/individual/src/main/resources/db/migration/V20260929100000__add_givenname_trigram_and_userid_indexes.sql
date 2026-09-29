-- Index the two search filters that had no usable index.
--
-- givenName search is a case-insensitive "contains" match (givenname ILIKE '%...%'). A btree is
-- ordered by leading characters, so it cannot serve a leading wildcard and every name search read
-- the whole tenant. A pg_trgm GIN index serves it. The extension is pinned to public and the
-- operator class schema-qualified because tenant schemas are migrated with only their own schema on
-- the search_path, where an unqualified gin_trgm_ops does not resolve.
--
-- userId search (userid IN (...)) had no index at all.

CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;

CREATE INDEX IF NOT EXISTS idx_individual_givenname_trgm_v3
    ON individual_v3 USING gin (givenname public.gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_individual_tenant_userid_v3
    ON individual_v3 (tenantid, userid);
