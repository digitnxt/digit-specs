-- Standardize audit field naming across boundary service tables
-- Change from modifiedby/modifiedtime (lowercase) to modifiedBy/modifiedTime (camelCase)
-- Change from lowercase createdby/createdtime to camelCase createdBy/createdTime

-- BOUNDARY_V1 table
ALTER TABLE IF EXISTS boundary_v1 RENAME COLUMN modifiedby TO "modifiedBy";
ALTER TABLE IF EXISTS boundary_v1 RENAME COLUMN modifiedtime TO "modifiedTime";
ALTER TABLE IF EXISTS boundary_v1 RENAME COLUMN createdby TO "createdBy";
ALTER TABLE IF EXISTS boundary_v1 RENAME COLUMN createdtime TO "createdTime";

-- BOUNDARY_HIERARCHY_V1 table
ALTER TABLE IF EXISTS boundary_hierarchy_v1 RENAME COLUMN modifiedby TO "modifiedBy";
ALTER TABLE IF EXISTS boundary_hierarchy_v1 RENAME COLUMN modifiedtime TO "modifiedTime";
ALTER TABLE IF EXISTS boundary_hierarchy_v1 RENAME COLUMN createdby TO "createdBy";
ALTER TABLE IF EXISTS boundary_hierarchy_v1 RENAME COLUMN createdtime TO "createdTime";

-- BOUNDARY_RELATIONSHIP_V1 table
ALTER TABLE IF EXISTS boundary_relationship_v1 RENAME COLUMN modifiedby TO "modifiedBy";
ALTER TABLE IF EXISTS boundary_relationship_v1 RENAME COLUMN modifiedtime TO "modifiedTime";
ALTER TABLE IF EXISTS boundary_relationship_v1 RENAME COLUMN createdby TO "createdBy";
ALTER TABLE IF EXISTS boundary_relationship_v1 RENAME COLUMN createdtime TO "createdTime";

-- Update indexes to reflect new column names
DROP INDEX IF EXISTS idx_boundary_v1_modifiedtime;
CREATE INDEX IF NOT EXISTS idx_boundary_v1_modifiedTime ON boundary_v1 ("modifiedTime");

DROP INDEX IF EXISTS idx_boundary_hierarchy_v1_modifiedtime;
CREATE INDEX IF NOT EXISTS idx_boundary_hierarchy_v1_modifiedTime ON boundary_hierarchy_v1 ("modifiedTime");

DROP INDEX IF EXISTS idx_boundary_relationship_v1_modifiedtime;
CREATE INDEX IF NOT EXISTS idx_boundary_relationship_v1_modifiedTime ON boundary_relationship_v1 ("modifiedTime");
