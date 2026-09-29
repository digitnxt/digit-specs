-- Index employee_v3 (tenant_id, user_id) for search-by-userId.
--
-- GET /v3/employees now accepts a `userIds` filter, which the repository turns
-- into `WHERE tenant_id = ? AND user_id IN (...)`. Every search is already
-- tenant-scoped, so the tenant column leads and the index also serves the
-- role-based search (which resolves a Keycloak role to member user ids and
-- filters the same column).
--
-- Not unique: nothing constrains a Keycloak user to a single employee row in a
-- tenant, and user_id is nullable (employees may exist without a linked user).

CREATE INDEX IF NOT EXISTS idx_employee_tenant_user_id_v3 ON employee_v3 (tenant_id, user_id);
