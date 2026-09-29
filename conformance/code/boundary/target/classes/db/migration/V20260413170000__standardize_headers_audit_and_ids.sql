ALTER TABLE IF EXISTS boundary_v1 ALTER COLUMN id TYPE UUID USING id::uuid;
ALTER TABLE IF EXISTS boundary_hierarchy_v1 ALTER COLUMN id TYPE UUID USING id::uuid;
ALTER TABLE IF EXISTS boundary_relationship_v1 ALTER COLUMN id TYPE UUID USING id::uuid;

ALTER TABLE IF EXISTS boundary_v1 RENAME COLUMN additionaldetails TO additionalattributes;
ALTER TABLE IF EXISTS boundary_v1 RENAME COLUMN lastmodifiedby TO modifiedby;
ALTER TABLE IF EXISTS boundary_v1 RENAME COLUMN lastmodifiedtime TO modifiedtime;
ALTER TABLE IF EXISTS boundary_v1 ADD COLUMN IF NOT EXISTS requestid TEXT;

ALTER TABLE IF EXISTS boundary_hierarchy_v1 RENAME COLUMN lastmodifiedby TO modifiedby;
ALTER TABLE IF EXISTS boundary_hierarchy_v1 RENAME COLUMN lastmodifiedtime TO modifiedtime;
ALTER TABLE IF EXISTS boundary_hierarchy_v1 ADD COLUMN IF NOT EXISTS requestid TEXT;

ALTER TABLE IF EXISTS boundary_relationship_v1 RENAME COLUMN lastmodifiedby TO modifiedby;
ALTER TABLE IF EXISTS boundary_relationship_v1 RENAME COLUMN lastmodifiedtime TO modifiedtime;
ALTER TABLE IF EXISTS boundary_relationship_v1 ADD COLUMN IF NOT EXISTS requestid TEXT;
