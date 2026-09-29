-- Index employee_jurisdiction_v3 by its owning employee.
--
-- Postgres indexes the referenced primary key but never the referencing foreign key column, so
-- every lookup by employee_id was a sequential scan of the whole table.
--
-- (employee_id, "createdTime" DESC) serves the ON DELETE CASCADE from employee_v3, which filters on
-- employee_id alone, and returns an employee's jurisdictions already in the fixed search order.
-- (tenant_id, employee_id) serves the tenant-scoped reads and the PUT/PATCH reconcile.

CREATE INDEX IF NOT EXISTS idx_jurisdiction_employee_createdtime_v3
    ON employee_jurisdiction_v3 (employee_id, "createdTime" DESC);

CREATE INDEX IF NOT EXISTS idx_jurisdiction_tenant_employee_v3
    ON employee_jurisdiction_v3 (tenant_id, employee_id);
