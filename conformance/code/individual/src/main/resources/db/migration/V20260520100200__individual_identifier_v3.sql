-- =============================================================================
-- individual_identifier_v3
-- Government-issued identifiers attached to an individual.
-- Each identifierType may appear at most once per individual (unique partial
-- index on active rows). Verified flag is set by an authorised process only.
-- =============================================================================

CREATE TABLE IF NOT EXISTS individual_identifier_v3
(
    id              character varying(64)   NOT NULL,
    individualid    character varying(64)   NOT NULL,

    identifiertype  character varying(64),
    identifierid    character varying(64),

    -- Verification and document attachment
    verified        boolean                 NOT NULL DEFAULT false,
    documenttype    character varying(64),
    filestoreid     character varying(64),

    -- Soft-deactivation (individual delete marks identifiers inactive)
    active          boolean                 NOT NULL DEFAULT true,

    -- Audit
    "createdBy"     character varying(64),
    "modifiedBy"    character varying(64),
    "createdTime"   bigint,
    "modifiedTime"  bigint,
    requestid       text,

    CONSTRAINT pk_individual_identifier_v3 PRIMARY KEY (id)
);

-- FK-side lookup: all identifiers for an individual (equality)
CREATE INDEX IF NOT EXISTS idx_individual_identifier_individualid_v3
    ON individual_identifier_v3 (individualid);

-- Tenant-scoped active-identifier type lookup (equality on both)
-- Used by uniqueness validation: one identifierType per individual while active
CREATE UNIQUE INDEX IF NOT EXISTS uk_individual_identifier_type_active_v3
    ON individual_identifier_v3 (individualid, identifiertype)
    WHERE active = true;

COMMENT ON TABLE  individual_identifier_v3             IS 'Government-issued identifiers (Aadhaar, PAN, Passport, etc.) per individual.';
COMMENT ON COLUMN individual_identifier_v3.verified    IS 'Set true only by an authorised verification process — not self-asserted.';
COMMENT ON COLUMN individual_identifier_v3.active      IS 'false after the parent individual is soft-deleted.';
COMMENT ON COLUMN individual_identifier_v3.requestid   IS 'X-Request-ID of the request that last wrote this row.';
