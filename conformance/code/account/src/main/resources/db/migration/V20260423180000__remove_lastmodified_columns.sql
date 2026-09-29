-- Rename lastModifiedBy to modifiedBy in tenant_v1
ALTER TABLE tenant_v1 RENAME COLUMN lastModifiedBy TO modifiedBy;

-- Rename lastModifiedTime to modifiedTime in tenant_v1
ALTER TABLE tenant_v1 RENAME COLUMN lastModifiedTime TO modifiedTime;

-- Rename lastModifiedBy to modifiedBy in tenant_config_v1
ALTER TABLE tenant_config_v1 RENAME COLUMN lastModifiedBy TO modifiedBy;

-- Rename lastModifiedTime to modifiedTime in tenant_config_v1
ALTER TABLE tenant_config_v1 RENAME COLUMN lastModifiedTime TO modifiedTime;

-- Rename lastModifiedBy to modifiedBy in tenant_documents_v1
ALTER TABLE tenant_documents_v1 RENAME COLUMN lastModifiedBy TO modifiedBy;

-- Rename lastModifiedTime to modifiedTime in tenant_documents_v1
ALTER TABLE tenant_documents_v1 RENAME COLUMN lastModifiedTime TO modifiedTime;
