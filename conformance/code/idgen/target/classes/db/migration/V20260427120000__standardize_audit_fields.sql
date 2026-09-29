-- Standardize audit field naming in idgen service
-- Change from lastmodifiedby/lastmodifiedtime to modifiedBy/modifiedTime
-- Change from lowercase createdby/createdtime to camelCase createdBy/createdTime

-- IDGEN_TEMPLATES table
ALTER TABLE IF EXISTS idgen_templates RENAME COLUMN lastmodifiedby TO "modifiedBy";
ALTER TABLE IF EXISTS idgen_templates RENAME COLUMN lastmodifiedtime TO "modifiedTime";
ALTER TABLE IF EXISTS idgen_templates RENAME COLUMN createdby TO "createdBy";
ALTER TABLE IF EXISTS idgen_templates RENAME COLUMN createdtime TO "createdTime";

-- Update indexes to reflect new column names
DROP INDEX IF EXISTS idx_idgen_templates_createdtime;
CREATE INDEX IF NOT EXISTS idx_idgen_templates_createdTime ON idgen_templates ("createdTime");

DROP INDEX IF EXISTS idx_idgen_templates_lastmodifiedtime;
CREATE INDEX IF NOT EXISTS idx_idgen_templates_modifiedTime ON idgen_templates ("modifiedTime");
