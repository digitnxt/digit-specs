-- Drop redundant tenantId column from tenant_v1 table
-- This column was never populated or used in the application
-- The 'code' field serves as the unique tenant identifier

ALTER TABLE tenant_v1 DROP COLUMN IF EXISTS tenantId;
