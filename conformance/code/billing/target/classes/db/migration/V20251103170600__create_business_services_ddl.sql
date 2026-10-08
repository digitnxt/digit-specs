-- Create the business_services table
CREATE TABLE IF NOT EXISTS business_services (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    code VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(256) NOT NULL,
    collection_mode VARCHAR(32) NOT NULL,
    allowed_payment_modes JSONB,
    bill_expiry_days INTEGER NOT NULL,
    partial_payment_allowed BOOLEAN NOT NULL,
    min_payable_amount DECIMAL(18,2),
    currency VARCHAR(3) NOT NULL,
    rounding_rule_code VARCHAR(256),
    effective_from BIGINT NOT NULL,
    effective_to BIGINT,
    is_active BOOLEAN NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT uq_business_services_tenant_code 
      UNIQUE (tenant_id, code)
);

CREATE INDEX IF NOT EXISTS idx_business_services_tenant_active
 ON business_services(tenant_id, is_active);

CREATE INDEX IF NOT EXISTS idx_business_services_tenant_active_effective 
 ON business_services(tenant_id, is_active, effective_from, effective_to);

-- Create the business_services_audit table
CREATE TABLE IF NOT EXISTS business_services_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    business_service_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    code VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(256) NOT NULL,
    collection_mode VARCHAR(32) NOT NULL,
    allowed_payment_modes JSONB,
    bill_expiry_days INTEGER NOT NULL,
    partial_payment_allowed BOOLEAN NOT NULL,
    min_payable_amount DECIMAL(18,2),
    currency VARCHAR(3) NOT NULL,
    rounding_rule_code VARCHAR(256),
    effective_from BIGINT NOT NULL,
    effective_to BIGINT,
    is_active BOOLEAN NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);