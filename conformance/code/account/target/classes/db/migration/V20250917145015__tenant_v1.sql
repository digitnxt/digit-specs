CREATE TABLE IF NOT EXISTS tenant_v1 (
		id VARCHAR(128) PRIMARY KEY,
		code VARCHAR(255) UNIQUE NOT NULL,
		name VARCHAR(255) NOT NULL,
		email VARCHAR(512) NOT NULL,
		additionalAttributes JSONB,
		isActive BOOLEAN NOT NULL,
		tenantId VARCHAR(64),
		createdBy VARCHAR(64),
		lastModifiedBy VARCHAR(64),
		createdTime BIGINT,
		lastModifiedTime BIGINT
	);