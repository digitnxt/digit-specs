-- Standardize audit field naming in employee service
-- Change from snake_case to quoted camelCase
-- created_by → createdBy, created_time → createdTime
-- last_modified_by → modifiedBy, last_modified_time → modifiedTime

-- employee_v3 table
ALTER TABLE IF EXISTS employee_v3 RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS employee_v3 RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS employee_v3 RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS employee_v3 RENAME COLUMN last_modified_time TO "modifiedTime";

-- employee_jurisdiction_v3 table
ALTER TABLE IF EXISTS employee_jurisdiction_v3 RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS employee_jurisdiction_v3 RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS employee_jurisdiction_v3 RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS employee_jurisdiction_v3 RENAME COLUMN last_modified_time TO "modifiedTime";

-- Update indexes to reflect new column names
DROP INDEX IF EXISTS idx_employee_created_time;
CREATE INDEX IF NOT EXISTS idx_employee_createdTime ON employee_v3 ("createdTime");

DROP INDEX IF EXISTS idx_jurisdiction_created_time;
CREATE INDEX IF NOT EXISTS idx_jurisdiction_createdTime ON employee_jurisdiction_v3 ("createdTime");
