-- Create the bills table
CREATE TABLE IF NOT EXISTS bills (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    consumer_code VARCHAR(256) NOT NULL,
    payer_id VARCHAR(256),
    payer_name VARCHAR(256),
    payer_address VARCHAR(1024),
    payer_mobile_number VARCHAR(16),
    payer_email VARCHAR(254),
    bill_number VARCHAR(128) NOT NULL,
    bill_issue_at BIGINT NOT NULL,
    bill_expiry_at BIGINT,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    total_collected_amount DECIMAL(18,2) NOT NULL,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT fk_bills_business_service
      FOREIGN KEY (tenant_id, business_service_code)
        REFERENCES business_services (tenant_id, code)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_bills_tenant_service_consumer
    ON bills (tenant_id, business_service_code, consumer_code);

CREATE INDEX IF NOT EXISTS idx_bills_tenant_bill_number
    ON bills (tenant_id, bill_number);

CREATE INDEX IF NOT EXISTS idx_bills_tenant_status
    ON bills (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_bills_tenant_mobile_number
    ON bills (tenant_id, payer_mobile_number);

CREATE INDEX IF NOT EXISTS idx_bills_tenant_email
    ON bills (tenant_id, payer_email);

-- Unique partial index for ACTIVE bills
CREATE UNIQUE INDEX IF NOT EXISTS uniq_active_bill
ON bills (tenant_id, business_service_code, consumer_code)
WHERE status = 'ACTIVE';

-- Create the bill_details table
CREATE TABLE IF NOT EXISTS bill_details (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    bill_id UUID NOT NULL,
    demand_id UUID NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    amount_paid DECIMAL(18,2) NOT NULL,
    period_from BIGINT NOT NULL,
    period_to BIGINT NOT NULL,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT fk_bill_details_bill
      FOREIGN KEY (bill_id)
        REFERENCES bills (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_bill_details_demand
      FOREIGN KEY (demand_id)
        REFERENCES demands (id)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_bill_details_bill_id
    ON bill_details (bill_id);

-- Create the bill_account_details table
CREATE TABLE IF NOT EXISTS bill_account_details (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    bill_detail_id UUID NOT NULL,
    line_item_id UUID NOT NULL,
    tax_head_code VARCHAR(64) NOT NULL,
    order_number INTEGER NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    adjusted_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT fk_bill_account_details_bill_detail
      FOREIGN KEY (bill_detail_id)
        REFERENCES bill_details (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_bill_account_details_tax_head
      FOREIGN KEY (tenant_id, tax_head_code)
        REFERENCES tax_heads (tenant_id, code)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_bill_account_details_detail
    ON bill_account_details (bill_detail_id);

-- Create the bills_audit table
CREATE TABLE IF NOT EXISTS bills_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    bill_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    consumer_code VARCHAR(256) NOT NULL,
    payer_id VARCHAR(256),
    payer_name VARCHAR(256),
    payer_address VARCHAR(1024),
    payer_mobile_number VARCHAR(16),
    payer_email VARCHAR(254),
    bill_number VARCHAR(128) NOT NULL,
    bill_issue_at BIGINT NOT NULL,
    bill_expiry_at BIGINT,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    total_collected_amount DECIMAL(18,2) NOT NULL,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);

-- Create the bill_details_audit table
CREATE TABLE IF NOT EXISTS bill_details_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    bill_detail_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    bill_id UUID NOT NULL,
    demand_id UUID NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    amount_paid DECIMAL(18,2) NOT NULL,
    period_from BIGINT NOT NULL,
    period_to BIGINT NOT NULL,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);

-- Create the bill_account_details_audit table
CREATE TABLE IF NOT EXISTS bill_account_details_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    bill_account_detail_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    bill_detail_id UUID NOT NULL,
    line_item_id UUID NOT NULL,
    tax_head_code VARCHAR(64) NOT NULL,
    order_number INTEGER NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    adjusted_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);