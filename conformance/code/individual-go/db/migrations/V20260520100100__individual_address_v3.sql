-- =============================================================================
-- individual_address_v3       — physical address records
-- individual_address_join_v3  — many-to-many mapping: individual ↔ address
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Address records
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS individual_address_v3
(
    id                character varying(64)   NOT NULL,
    tenantid          character varying(1000),

    -- Address type
    type              character varying(64),

    -- Address fields
    doorno            character varying(64),
    buildingname      character varying(128),
    street            character varying(128),
    landmark          character varying(128),
    addressline1      character varying(256),
    addressline2      character varying(256),
    city              character varying(128),
    region            character varying(128),
    country           character varying(64),
    pincode           character varying(16),
    localitycode      character varying(64),   -- boundaryCode in the API

    -- Geo-coordinates
    latitude          double precision,
    longitude         double precision,
    locationaccuracy  double precision,

    -- Audit
    "createdBy"       character varying(64),
    "modifiedBy"      character varying(64),
    "createdTime"     bigint,
    "modifiedTime"    bigint,
    requestid         text,

    CONSTRAINT pk_individual_address_v3 PRIMARY KEY (id)
);

-- Address type filter within tenant (both equality columns)
CREATE INDEX IF NOT EXISTS idx_individual_address_tenant_type_v3
    ON individual_address_v3 (tenantid, type);

-- Locality code lookup within tenant (equality on both)
CREATE INDEX IF NOT EXISTS idx_individual_address_tenant_locality_v3
    ON individual_address_v3 (tenantid, localitycode);

-- ---------------------------------------------------------------------------
-- Individual ↔ Address join table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS individual_address_join_v3
(
    individualid      character varying(64)   NOT NULL,
    addressid         character varying(64)   NOT NULL,
    type              character varying(64),

    -- Audit
    "createdBy"       character varying(64),
    "modifiedBy"      character varying(64),
    "createdTime"     bigint,
    "modifiedTime"    bigint,

    CONSTRAINT pk_individual_address_join_v3 PRIMARY KEY (individualid, addressid)
);

-- Reverse lookup: find all individuals for a given address
CREATE INDEX IF NOT EXISTS idx_individual_address_join_addressid_v3
    ON individual_address_join_v3 (addressid);

COMMENT ON TABLE  individual_address_v3              IS 'Physical address records associated with individuals.';
COMMENT ON TABLE  individual_address_join_v3         IS 'Many-to-many mapping between individual_v3 and individual_address_v3.';
COMMENT ON COLUMN individual_address_v3.localitycode IS 'Platform boundary/locality code (boundaryCode in the API).';
COMMENT ON COLUMN individual_address_v3.requestid    IS 'X-Request-ID of the request that last wrote this row.';
