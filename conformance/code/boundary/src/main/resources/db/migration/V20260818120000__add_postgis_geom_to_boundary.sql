-- Geo search support: native PostGIS geometry column on boundary_v1.
-- Requires a PostGIS-capable Postgres (e.g. the postgis/postgis image); the
-- extension is created here if the migration user has the privilege.
CREATE EXTENSION IF NOT EXISTS postgis;

-- New column only: existing rows keep geom NULL (no backfill by design) and are
-- excluded from geo search until they are updated through the API. New creates
-- and updates populate geom from the GeoJSON geometry (SRID 4326 / WGS84).
ALTER TABLE boundary_v1 ADD COLUMN IF NOT EXISTS geom GEOMETRY(Geometry, 4326);

-- Spatial index so point-in-polygon lookups (ST_Contains) do not full-scan.
CREATE INDEX IF NOT EXISTS idx_boundary_v1_geom ON boundary_v1 USING GIST (geom);
