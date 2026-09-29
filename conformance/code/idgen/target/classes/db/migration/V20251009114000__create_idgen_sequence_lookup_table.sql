CREATE TABLE IF NOT EXISTS idgen_sequence_lookup (
    id UUID PRIMARY KEY,
    seqname VARCHAR(256) NOT NULL UNIQUE,
    tenantid VARCHAR(64) NOT NULL,
    templatecode VARCHAR(64) NOT NULL,
    UNIQUE (tenantid, templatecode)
);
