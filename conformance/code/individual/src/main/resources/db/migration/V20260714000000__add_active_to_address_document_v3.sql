-- =============================================================================
-- Add soft-deactivation flag to address and document tables.
-- Brings them in line with individual_identifier_v3: a child is marked
-- active = false when removed on a PUT full-replace, or when the parent
-- individual is soft-deleted. Reads return only active = true children.
-- =============================================================================

ALTER TABLE individual_address_v3
    ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;

ALTER TABLE individual_document_v3
    ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN individual_address_v3.active  IS 'false after removal on PUT full-replace or parent individual soft-delete.';
COMMENT ON COLUMN individual_document_v3.active IS 'false after removal on PUT full-replace or parent individual soft-delete.';
