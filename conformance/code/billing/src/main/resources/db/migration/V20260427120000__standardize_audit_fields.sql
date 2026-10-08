-- Standardize audit field naming in billing service
-- Change from snake_case to quoted camelCase
-- created_by → createdBy, created_time → createdTime
-- last_modified_by → modifiedBy, last_modified_time → modifiedTime

-- BUSINESS_SERVICES table
ALTER TABLE IF EXISTS business_services RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS business_services RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS business_services RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS business_services RENAME COLUMN last_modified_time TO "modifiedTime";

-- BUSINESS_SERVICES_AUDIT table
ALTER TABLE IF EXISTS business_services_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS business_services_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS business_services_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS business_services_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- TAX_HEADS table
ALTER TABLE IF EXISTS tax_heads RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS tax_heads RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS tax_heads RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS tax_heads RENAME COLUMN last_modified_time TO "modifiedTime";

-- TAX_HEADS_AUDIT table
ALTER TABLE IF EXISTS tax_heads_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS tax_heads_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS tax_heads_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS tax_heads_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- DEMANDS table
ALTER TABLE IF EXISTS demands RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS demands RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS demands RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS demands RENAME COLUMN last_modified_time TO "modifiedTime";

-- DEMANDS_AUDIT table
ALTER TABLE IF EXISTS demands_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS demands_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS demands_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS demands_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- LINE_ITEMS table
ALTER TABLE IF EXISTS line_items RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS line_items RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS line_items RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS line_items RENAME COLUMN last_modified_time TO "modifiedTime";

-- LINE_ITEMS_AUDIT table
ALTER TABLE IF EXISTS line_items_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS line_items_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS line_items_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS line_items_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- BILLS table
ALTER TABLE IF EXISTS bills RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS bills RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS bills RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS bills RENAME COLUMN last_modified_time TO "modifiedTime";

-- BILLS_AUDIT table
ALTER TABLE IF EXISTS bills_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS bills_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS bills_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS bills_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- BILL_DETAILS table
ALTER TABLE IF EXISTS bill_details RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS bill_details RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS bill_details RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS bill_details RENAME COLUMN last_modified_time TO "modifiedTime";

-- BILL_DETAILS_AUDIT table
ALTER TABLE IF EXISTS bill_details_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS bill_details_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS bill_details_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS bill_details_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- BILL_ACCOUNT_DETAILS table
ALTER TABLE IF EXISTS bill_account_details RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS bill_account_details RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS bill_account_details RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS bill_account_details RENAME COLUMN last_modified_time TO "modifiedTime";

-- BILL_ACCOUNT_DETAILS_AUDIT table
ALTER TABLE IF EXISTS bill_account_details_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS bill_account_details_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS bill_account_details_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS bill_account_details_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- PAYMENTS table
ALTER TABLE IF EXISTS payments RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS payments RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS payments RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS payments RENAME COLUMN last_modified_time TO "modifiedTime";

-- PAYMENTS_AUDIT table
ALTER TABLE IF EXISTS payments_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS payments_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS payments_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS payments_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- PAYMENT_DETAILS table
ALTER TABLE IF EXISTS payment_details RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS payment_details RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS payment_details RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS payment_details RENAME COLUMN last_modified_time TO "modifiedTime";

-- PAYMENT_DETAILS_AUDIT table
ALTER TABLE IF EXISTS payment_details_audit RENAME COLUMN created_by TO "createdBy";
ALTER TABLE IF EXISTS payment_details_audit RENAME COLUMN created_time TO "createdTime";
ALTER TABLE IF EXISTS payment_details_audit RENAME COLUMN last_modified_by TO "modifiedBy";
ALTER TABLE IF EXISTS payment_details_audit RENAME COLUMN last_modified_time TO "modifiedTime";

-- Update indexes to reflect new column names
DROP INDEX IF EXISTS idx_business_services_created_time;
CREATE INDEX IF NOT EXISTS idx_business_services_createdTime ON business_services ("createdTime");

DROP INDEX IF EXISTS idx_tax_heads_created_time;
CREATE INDEX IF NOT EXISTS idx_tax_heads_createdTime ON tax_heads ("createdTime");

DROP INDEX IF EXISTS idx_demands_created_time;
CREATE INDEX IF NOT EXISTS idx_demands_createdTime ON demands ("createdTime");

DROP INDEX IF EXISTS idx_bills_created_time;
CREATE INDEX IF NOT EXISTS idx_bills_createdTime ON bills ("createdTime");

DROP INDEX IF EXISTS idx_payments_created_time;
CREATE INDEX IF NOT EXISTS idx_payments_createdTime ON payments ("createdTime");
