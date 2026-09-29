-- A free-form label for grouping configs, so a caller can find every template belonging to one
-- feature or team without knowing their codes. Nullable: existing configs have none, and it is
-- never required to send.
ALTER TABLE notification_config
    ADD COLUMN IF NOT EXISTS tag VARCHAR(255);

-- Searches are always scoped to a tenant, so the index leads with tenant_id.
CREATE INDEX IF NOT EXISTS idx_notification_config_tenant_tag
    ON notification_config (tenant_id, tag);
