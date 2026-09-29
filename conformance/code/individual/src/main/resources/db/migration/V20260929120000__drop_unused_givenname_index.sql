-- Drop idx_individual_tenant_givenname_v3, which no query can use.
--
-- A plain btree on givenname serves only equality or prefix matches and sorting on the raw value.
-- Name search is a case-insensitive contains match (served by idx_individual_givenname_trgm_v3) and
-- the name uniqueness check compares lowercased values (served by idx_individual_tenant_lower_name_v3),
-- so the index was only maintenance cost on every insert and rename.

DROP INDEX IF EXISTS idx_individual_tenant_givenname_v3;
