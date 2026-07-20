-- =============================================================================
-- Address becomes a direct one-to-many child of the individual.
-- Adds individualid to individual_address_v3, backfills it from the join table,
-- then drops the many-to-many join. Each address had exactly one owner in
-- practice, so the backfill is 1:1. (If any orphan/shared rows exist, the
-- SET NOT NULL below will fail loudly rather than hide bad data.)
-- =============================================================================

ALTER TABLE individual_address_v3
    ADD COLUMN IF NOT EXISTS individualid character varying(64);

UPDATE individual_address_v3 a
   SET individualid = j.individualid
  FROM individual_address_join_v3 j
 WHERE j.addressid = a.id
   AND a.individualid IS NULL;

ALTER TABLE individual_address_v3 ALTER COLUMN individualid SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_individual_address_individualid_v3
    ON individual_address_v3 (individualid);

DROP TABLE IF EXISTS individual_address_join_v3;

COMMENT ON COLUMN individual_address_v3.individualid IS 'Owning individual (one-to-many; the join table was removed).';