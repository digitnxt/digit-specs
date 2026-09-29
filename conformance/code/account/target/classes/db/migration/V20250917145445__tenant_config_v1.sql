CREATE TABLE IF NOT EXISTS tenant_config_v1 (
		id VARCHAR(128) PRIMARY KEY,
		code VARCHAR(255) NOT NULL,
		defaultLoginType VARCHAR(50),
		otpLength VARCHAR(50),
		name VARCHAR(255),
		enableUserBasedLogin BOOLEAN,
		additionalAttributes JSONB,
		isActive BOOLEAN NOT NULL,
		languages TEXT[],
		createdBy VARCHAR(64),
		lastModifiedBy VARCHAR(64),
		createdTime BIGINT,
		lastModifiedTime BIGINT
	);