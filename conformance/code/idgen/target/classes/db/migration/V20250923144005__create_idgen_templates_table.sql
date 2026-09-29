-- Create the idgen_templates table
CREATE TABLE IF NOT EXISTS idgen_templates (
    id UUID PRIMARY KEY,
    templatecode VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    tenantid VARCHAR(64) NOT NULL,
    config JSONB NOT NULL,
    createdtime BIGINT,
    createdby VARCHAR(64),
    lastmodifiedtime BIGINT,
    lastmodifiedby VARCHAR(64),
    UNIQUE (tenantid, templatecode, version)
);

-- Index to speed up queries for "latest version per template"
CREATE INDEX IF NOT EXISTS idx_idgen_templates_tenant_template_version_desc
ON idgen_templates (tenantid, templatecode, version DESC);
