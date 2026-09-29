CREATE TABLE IF NOT EXISTS boundary_hierarchy_v1 (
    id VARCHAR(64),
    tenantId VARCHAR(64) NOT NULL,
    hierarchyType VARCHAR(64) NOT NULL,
    boundaryHierarchy JSONB NOT NULL,
    createdtime BIGINT,
    createdby VARCHAR(64),
    lastmodifiedtime BIGINT,
    lastmodifiedby VARCHAR(64),
    CONSTRAINT pk_boundary_hierarchy_v1 PRIMARY KEY (id),
    CONSTRAINT uk_boundary_hierarchy_v1 UNIQUE (tenantId, hierarchyType)
);

-- Create an index on tenantId and hierarchyType
CREATE INDEX IF NOT EXISTS idx_boundary_hierarchy_v1_tenantId_hierarchyType ON boundary_hierarchy_v1 (tenantId, hierarchyType); 