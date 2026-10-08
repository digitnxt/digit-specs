-- Create the payments table
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    total_amount_due DECIMAL(18,2) NOT NULL,
    total_amount_paid DECIMAL(18,2) NOT NULL,
    transaction_number VARCHAR(256) NOT NULL,
    transaction_date BIGINT NOT NULL,
    payment_mode VARCHAR(32) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    instrument_number VARCHAR(256),
    instrument_date BIGINT,
    instrument_status VARCHAR(32) NOT NULL,
    ifsc_code VARCHAR(64),
    paid_by VARCHAR(256),
    payer_id VARCHAR(256),
    payer_name VARCHAR(256),
    payer_address VARCHAR(1024),
    payer_mobile_number VARCHAR(16),
    payer_email VARCHAR(254),
    filestore_id VARCHAR(256),
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payments_tenant_transaction_number
  ON payments (tenant_id, transaction_number);

CREATE INDEX IF NOT EXISTS idx_payments_tenant_payer_id
  ON payments (tenant_id, payer_id);

CREATE INDEX IF NOT EXISTS idx_payments_tenant_mobile_number
  ON payments (tenant_id, payer_mobile_number);

CREATE INDEX IF NOT EXISTS idx_payments_tenant_payment_status
  ON payments (tenant_id, payment_status);

-- Create the payment_details table
CREATE TABLE IF NOT EXISTS payment_details (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    payment_id UUID NOT NULL,
    bill_id UUID NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    total_amount_due DECIMAL(18,2) NOT NULL,
    total_amount_paid DECIMAL(18,2) NOT NULL,
    receipt_number VARCHAR(256) NOT NULL,
    receipt_date BIGINT NOT NULL,
    receipt_type VARCHAR(256) NOT NULL,
    manual_receipt_number VARCHAR(256),
    manual_receipt_date BIGINT,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT uk_payment_details_bill_id
      UNIQUE (bill_id),

    CONSTRAINT fk_payment_details_payment
      FOREIGN KEY (payment_id)
        REFERENCES payments (id)
        ON DELETE CASCADE   
);

CREATE INDEX IF NOT EXISTS idx_payment_details_payment_id
  ON payment_details (payment_id);

CREATE INDEX IF NOT EXISTS idx_payment_details_bill_id
  ON payment_details (bill_id);

CREATE INDEX IF NOT EXISTS idx_payment_details_receipt_number
  ON payment_details (receipt_number);

-- Create the payments_audit table
CREATE TABLE IF NOT EXISTS payments_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    payment_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    total_amount_due DECIMAL(18,2) NOT NULL,
    total_amount_paid DECIMAL(18,2) NOT NULL,
    transaction_number VARCHAR(256) NOT NULL,
    transaction_date BIGINT NOT NULL,
    payment_mode VARCHAR(32) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    instrument_number VARCHAR(256),
    instrument_date BIGINT,
    instrument_status VARCHAR(32),
    ifsc_code VARCHAR(64),
    paid_by VARCHAR(256),
    payer_id VARCHAR(256),
    payer_name VARCHAR(256),
    payer_address VARCHAR(1024),
    payer_mobile_number VARCHAR(16),
    payer_email VARCHAR(254),
    filestore_id VARCHAR(256),
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);

-- Create the payment_details_audit table
CREATE TABLE IF NOT EXISTS payment_details_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    payment_details_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    payment_id UUID NOT NULL,
    bill_id UUID NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    total_amount_due DECIMAL(18,2) NOT NULL,
    total_amount_paid DECIMAL(18,2) NOT NULL,
    receipt_number VARCHAR(256) NOT NULL,
    receipt_date BIGINT NOT NULL,
    receipt_type VARCHAR(256) NOT NULL,
    manual_receipt_number VARCHAR(256),
    manual_receipt_date BIGINT,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL   
);