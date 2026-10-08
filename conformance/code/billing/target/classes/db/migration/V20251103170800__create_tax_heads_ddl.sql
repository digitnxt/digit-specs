-- Create the tax_heads table
CREATE TABLE IF NOT EXISTS tax_heads (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    code VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(256) NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    order_number INTEGER NOT NULL,
    effective_from BIGINT NOT NULL,
    effective_to BIGINT,
    is_active BOOLEAN NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL,

    CONSTRAINT uq_tax_heads_tenant_code 
      UNIQUE (tenant_id, code),

    CONSTRAINT uq_tax_heads_service_order 
      UNIQUE (tenant_id, business_service_code, order_number),

    CONSTRAINT fk_tax_heads_business_service
      FOREIGN KEY (tenant_id, business_service_code)
      REFERENCES business_services (tenant_id, code)
      ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_tax_heads_tenant_active 
 ON tax_heads(tenant_id, is_active, category);

CREATE INDEX IF NOT EXISTS idx_tax_heads_tenant_service_active_category
  ON tax_heads (tenant_id, business_service_code, is_active, category);

-- Create the tax_heads_audit table
CREATE TABLE IF NOT EXISTS tax_heads_audit (
    id UUID PRIMARY KEY,
    row_hash VARCHAR(256) NOT NULL,
    tax_head_id UUID NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    code VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(256) NOT NULL,
    business_service_code VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    order_number INTEGER NOT NULL,
    effective_from BIGINT NOT NULL,
    effective_to BIGINT,
    is_active BOOLEAN NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_time BIGINT NOT NULL,
    last_modified_by VARCHAR(256) NOT NULL,
    last_modified_time BIGINT NOT NULL
);