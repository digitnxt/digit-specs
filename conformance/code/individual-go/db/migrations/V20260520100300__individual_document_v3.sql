-- =============================================================================
-- individual_document_v3
-- General documents associated with an individual (proof of residence,
-- caste certificate, etc.). Each row references a file uploaded to the
-- file-store service. For identifier-specific documents use documenttype /
-- filestoreid directly on individual_identifier_v3.
-- =============================================================================

CREATE TABLE IF NOT EXISTS individual_document_v3
(
    id              character varying(64)   NOT NULL,
    individualid    character varying(64)   NOT NULL,

    documenttype    character varying(64)   NOT NULL,
    filestoreid     character varying(64)   NOT NULL,
    documentuid     character varying(64),

    -- Audit
    "createdBy"     character varying(64),
    "modifiedBy"    character varying(64),
    "createdTime"   bigint,
    "modifiedTime"  bigint,
    requestid       text,

    CONSTRAINT pk_individual_document_v3 PRIMARY KEY (id)
);

-- FK-side lookup: all documents for an individual (equality)
CREATE INDEX IF NOT EXISTS idx_individual_document_individualid_v3
    ON individual_document_v3 (individualid);

-- Document type filter within an individual (equality on both)
CREATE INDEX IF NOT EXISTS idx_individual_document_individualid_type_v3
    ON individual_document_v3 (individualid, documenttype);

COMMENT ON TABLE  individual_document_v3              IS 'General documents associated with an individual (non-identifier documents).';
COMMENT ON COLUMN individual_document_v3.filestoreid  IS 'Reference ID returned by the file-store service after upload.';
COMMENT ON COLUMN individual_document_v3.documentuid  IS 'Reference number printed on the physical document. Nullable.';
COMMENT ON COLUMN individual_document_v3.requestid    IS 'X-Request-ID of the request that last wrote this row.';
