-- v3 TenantConfig schema realignment.
--
-- The legacy `tenant_config_v1` table modelled config as a multi-field
-- record (defaultLoginType, otpLength, languages, enableUserBasedLogin,
-- name, etc.). The v3 spec treats it as a per-tenant key/value store, so
-- we drop the legacy table and recreate it in the new shape.
-- `tenant_documents_v1` (which held document references hung off legacy
-- configs) is dropped entirely — the v3 spec removes documents from the
-- TenantConfig surface.
--
-- This migration is destructive: any rows in either table at run time are
-- lost. Acceptable because the live deployment has not entered production
-- and any existing rows reflect the legacy multi-field shape that no v3
-- service code can read or update.

DROP TABLE IF EXISTS tenant_documents_v1;
DROP TABLE IF EXISTS tenant_config_v1;

CREATE TABLE tenant_config_v1 (
    id           VARCHAR(128)  PRIMARY KEY,
    tenantid     VARCHAR(128)  NOT NULL,
    configkey    VARCHAR(256)  NOT NULL,
    configvalue  VARCHAR(2048) NOT NULL,
    description  VARCHAR(512),
    isactive     BOOLEAN       NOT NULL DEFAULT TRUE,
    version      INTEGER       NOT NULL DEFAULT 0,
    createdby    VARCHAR(64),
    modifiedby   VARCHAR(64),
    createdtime  BIGINT,
    modifiedtime BIGINT,
    requestid    TEXT,

    -- Per-tenant key uniqueness — backs the 409 the service returns on
    -- duplicate configKey within a tenant, and guards against races where
    -- two concurrent Creates slip past the application-level GetByKey
    -- precheck.
    CONSTRAINT tenant_config_v1_tenant_key UNIQUE (tenantid, configkey)
);

-- Hot path for GET /v3/config is filter-by-tenantid, so a dedicated index
-- earns its keep over relying on the composite unique index alone.
CREATE INDEX idx_tenant_config_v1_tenantid ON tenant_config_v1 (tenantid);
