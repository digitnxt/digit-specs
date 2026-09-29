CREATE TABLE IF NOT EXISTS tenant_documents_v1 (
		id VARCHAR(128) PRIMARY KEY DEFAULT gen_random_uuid(),
		tenantConfigId VARCHAR(128) REFERENCES tenant_config_v1(id) ON DELETE CASCADE,
		tenantId VARCHAR(255) NOT NULL,
		type VARCHAR(255),
		fileStoreId VARCHAR(255),
		url VARCHAR(512),
		isActive BOOLEAN NOT NULL,
		createdBy VARCHAR(64),
		lastModifiedBy VARCHAR(64),
		createdTime BIGINT,
		lastModifiedTime BIGINT
	);