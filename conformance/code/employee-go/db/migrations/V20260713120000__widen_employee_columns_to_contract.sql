-- Align employee_v3 column widths with the API binding contract.
--
-- The request DTOs (CreateEmployeeRequest / UpdateEmployeeRequest /
-- PatchEmployeeRequest) validate these fields in-process at bind time, but the
-- original columns (V20251126034400) were provisioned narrower than the
-- contract:
--
--   column         was            binding max    ->  now
--   status         VARCHAR(20)    64                 VARCHAR(64)
--   employee_type  VARCHAR(50)    128                VARCHAR(128)
--   department     VARCHAR(64)    128                VARCHAR(128)
--   designation    VARCHAR(64)    128                VARCHAR(128)
--
-- With the column >= the binding limit, a request that passes validation is
-- guaranteed to fit, so length is rejected in-process (clean 400) instead of
-- overflowing the column and surfacing as a Postgres 22001 -> 500. This mirrors
-- the billing service convention (binding max == column width, generous sizes:
-- names 128-256, statuses 32+). ID fields (user_id / individual_id) stay
-- VARCHAR(64) and are instead tightened on the binding side to the 64 the
-- individual service already uses platform-wide for identifiers.

ALTER TABLE IF EXISTS employee_v3 ALTER COLUMN status        TYPE VARCHAR(64);
ALTER TABLE IF EXISTS employee_v3 ALTER COLUMN employee_type TYPE VARCHAR(128);
ALTER TABLE IF EXISTS employee_v3 ALTER COLUMN department    TYPE VARCHAR(128);
ALTER TABLE IF EXISTS employee_v3 ALTER COLUMN designation   TYPE VARCHAR(128);
