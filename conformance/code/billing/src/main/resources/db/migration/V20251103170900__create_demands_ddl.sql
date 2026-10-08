-- Required for equality operators in GiST exclusion constraint
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Create the demands table
CREATE TABLE IF NOT EXISTS demands (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    period_from BIGINT NOT NULL,
    period_to BIGINT NOT NULL,
    consumer_code VARCHAR(256) NOT NULL,
    bill_expiry_days INTEGER,
    payer JSONB,
    arrear_demand_ids JSONB,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    total_collected_amount DECIMAL(18,2) NOT NULL,
    is_demand_paid BOOLEAN,
    metadata JSONB,
    version INTEGER NOT NULL CHECK (version > 0),
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT fk_demands_business_service
      FOREIGN KEY (tenant_id, business_service_code)
        REFERENCES business_services (tenant_id, code)
        ON DELETE RESTRICT,

    CONSTRAINT no_overlapping_demands
      EXCLUDE USING gist (
          tenant_id WITH =,
          business_service_code WITH =,
          consumer_code WITH =,
          tstzrange(
              to_timestamp(period_from / 1000.0),
              to_timestamp(period_to / 1000.0),
              '[]'
          ) WITH &&
      )
      WHERE (status IN ('ACTIVE','FROZEN','PARTIALLY_PAID','PAID','ROLL_FORWARDED'))
);

CREATE INDEX IF NOT EXISTS idx_demands_latest_open
  ON demands (tenant_id, business_service_code, consumer_code, status, period_to DESC);

CREATE INDEX IF NOT EXISTS idx_demands_tenant_service_status
  ON demands (tenant_id, business_service_code, status);

CREATE INDEX IF NOT EXISTS idx_demands_tenant_status
  ON demands (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_demands_tenant_service_created_time
  ON demands (tenant_id, business_service_code, created_time);

CREATE INDEX IF NOT EXISTS idx_demands_tenant_created_time
  ON demands (tenant_id, created_time);

-- Create the line_items table
CREATE TABLE IF NOT EXISTS line_items (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    demand_id UUID NOT NULL,
    tax_head_code VARCHAR(64) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    collected_amount DECIMAL(18,2) NOT NULL,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT uq_line_items_demand_tax_head
      UNIQUE (tenant_id, demand_id, tax_head_code),

    CONSTRAINT fk_line_items_demand
      FOREIGN KEY (demand_id)
        REFERENCES demands (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_line_items_tax_head
      FOREIGN KEY (tenant_id, tax_head_code)
        REFERENCES tax_heads (tenant_id, code)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_line_items_demand_id
  ON line_items (demand_id);

-- Create the demands_audit table
CREATE TABLE IF NOT EXISTS demands_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    demand_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    period_from BIGINT NOT NULL,
    period_to BIGINT NOT NULL,
    consumer_code VARCHAR(256) NOT NULL,
    bill_expiry_days INTEGER,
    payer JSONB,
    arrear_demand_ids JSONB,
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(18,2),
    total_collected_amount DECIMAL(18,2),
    is_demand_paid BOOLEAN,
    metadata JSONB,
    version INTEGER NOT NULL CHECK (version > 0),
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);

-- Create the line_items_audit table
CREATE TABLE IF NOT EXISTS line_items_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    line_item_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    demand_id UUID NOT NULL,
    tax_head_code VARCHAR(64) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    collected_amount DECIMAL(18,2) NOT NULL,
    metadata JSONB,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);