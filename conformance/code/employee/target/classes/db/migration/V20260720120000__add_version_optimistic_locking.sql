-- Add optimistic-concurrency version columns to employee and jurisdiction.
--
-- Each mutation increments `version`; an update carries the version the client
-- last read and the write is a compare-and-swap (WHERE ... AND version = ?).
-- A mismatch means the row moved under the client → 409 ROW_VERSION_MISMATCH.
--
-- Employee and jurisdiction version independently: PUT/PATCH employee guards
-- employee.version; PUT jurisdiction (and in-place updates during an employee
-- reconcile) guard the jurisdiction's own version. See VERSIONING-DESIGN.md.
--
-- Existing rows default to 1. Column named `version` end-to-end (entity field
-- and DTO both `version`, no mapping). This replaces the previous pessimistic
-- SELECT … FOR UPDATE approach.

ALTER TABLE IF EXISTS employee_v3              ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE IF EXISTS employee_jurisdiction_v3 ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;