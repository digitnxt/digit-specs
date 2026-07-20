-- =============================================================================
-- individual_v3
-- Core citizen record. PII fields (mobilenumber, email) encrypted at rest
-- via Vault. Mobile number additionally hashed for indexed exact-match search.
-- Soft-delete via active=false. Optimistic locking via rowversion.
-- =============================================================================

CREATE TABLE IF NOT EXISTS individual_v3
(
    id                    character varying(64)   NOT NULL,
    individualid          character varying(64)   NOT NULL,
    tenantid              character varying(1000) NOT NULL,

    -- Name
    givenname             character varying(128),
    familyname            character varying(128),
    othernames            character varying(256),

    -- Personal details
    dateofbirth           date,
    gender                character varying(20),
    age                   integer,

    -- Contact (PII — encrypted at rest via Vault)
    mobilenumber          character varying(256),
    hashedmobilenumber    character varying(128),
    mobilenumberverified  boolean                 NOT NULL DEFAULT false,
    altcontactnumber      character varying(20),
    email                 character varying(256),
    emailverified         boolean                 NOT NULL DEFAULT false,

    -- Profile
    locale                character varying(16),
    photo                 character varying(512),
    userid                character varying(64),

    -- State
    active                boolean                 NOT NULL DEFAULT false,
    rowversion            integer                 NOT NULL DEFAULT 1,

    -- Relationships
    fathername            character varying(128),
    husbandname           character varying(128),

    -- Open key/value bag for jurisdiction-specific fields
    additionaldetails     jsonb,

    -- Audit
    "createdBy"           character varying(64),
    "modifiedBy"          character varying(64),
    "createdTime"         bigint,
    "modifiedTime"        bigint,
    requestid             text,

    CONSTRAINT pk_individual_v3 PRIMARY KEY (id)
);

-- Unique: one individualId per row (system-generated external ID)
CREATE UNIQUE INDEX IF NOT EXISTS uk_individual_individualid_v3
    ON individual_v3 (individualid);

-- Main list / pagination query: tenant scope → active filter → creation-time sort
CREATE INDEX IF NOT EXISTS idx_individual_tenant_active_created_v3
    ON individual_v3 (tenantid, active, "createdTime" DESC);

-- Mobile number exact-match search (equality on both columns)
CREATE INDEX IF NOT EXISTS idx_individual_tenant_mobile_v3
    ON individual_v3 (tenantid, hashedmobilenumber);

-- Name search (equality/prefix on givenname within tenant)
CREATE INDEX IF NOT EXISTS idx_individual_tenant_givenname_v3
    ON individual_v3 (tenantid, givenname);

-- Gender filter (equality on both columns)
CREATE INDEX IF NOT EXISTS idx_individual_tenant_gender_v3
    ON individual_v3 (tenantid, gender);

-- Date-of-birth exact match (equality on both columns)
CREATE INDEX IF NOT EXISTS idx_individual_tenant_dob_v3
    ON individual_v3 (tenantid, dateofbirth);

COMMENT ON TABLE  individual_v3                         IS 'Citizen / individual registry. PII encrypted via Vault.';
COMMENT ON COLUMN individual_v3.mobilenumber            IS 'Primary mobile number — encrypted at rest via Vault.';
COMMENT ON COLUMN individual_v3.hashedmobilenumber      IS 'SHA-256 hash of mobilenumber — used for indexed exact-match search.';
COMMENT ON COLUMN individual_v3.active                  IS 'false after soft-delete via DELETE /individuals/{id}.';
COMMENT ON COLUMN individual_v3.rowversion              IS 'Optimistic-locking version. Incremented on every update.';
COMMENT ON COLUMN individual_v3.requestid               IS 'X-Request-ID of the request that last wrote this row.';
