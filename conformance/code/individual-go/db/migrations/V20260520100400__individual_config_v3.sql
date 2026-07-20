-- =============================================================================
-- individual_config_v3
-- Per-tenant validation configuration for the Individual Service.
-- At most one row per tenant (unique constraint on tenantid).
-- Controls mobileRegex, nameRegex, and uniquenessCriteria enforced on
-- every individual create/update within that tenant.
-- =============================================================================

CREATE TABLE IF NOT EXISTS individual_config_v3
(
    id                  bigserial               NOT NULL,
    tenantid            text                    NOT NULL,

    mobileregex         text,
    nameregex           text,
    uniquenesscriteria  jsonb,

    -- Optimistic locking
    version             integer                 NOT NULL DEFAULT 1,

    -- Audit
    "createdBy"         character varying(64),
    "modifiedBy"        character varying(64),
    "createdTime"       bigint,
    "modifiedTime"      bigint,
    requestid           text,

    CONSTRAINT pk_individual_config_v3   PRIMARY KEY (id),
    CONSTRAINT uk_individual_config_tenant_v3 UNIQUE (tenantid)
);

COMMENT ON TABLE  individual_config_v3                    IS 'Per-tenant validation config for the Individual Service. One row per tenant.';
COMMENT ON COLUMN individual_config_v3.mobileregex        IS 'Java-compatible regex enforced on Individual.mobileNumber for this tenant.';
COMMENT ON COLUMN individual_config_v3.nameregex          IS 'Java-compatible regex enforced on Individual.givenName for this tenant.';
COMMENT ON COLUMN individual_config_v3.uniquenesscriteria IS 'JSON array of fields to enforce as unique per tenant: ["mobileNumber","name"].';
COMMENT ON COLUMN individual_config_v3.version            IS 'Optimistic-locking version. Incremented on every upsert.';
COMMENT ON COLUMN individual_config_v3.requestid          IS 'X-Request-ID of the request that last wrote this row.';
