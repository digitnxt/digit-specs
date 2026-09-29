-- notification_attempt reaches a tenant only through notification_id, so the only safe way to read
-- it was to join notification_log and filter there. That made the unsafe query the easy one:
-- findByNotificationId returns any tenant's attempts, because there was nothing to filter on.
ALTER TABLE notification_attempt
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);


-- Every read is scoped to a tenant, so the index leads with tenant_id.
CREATE INDEX IF NOT EXISTS idx_notification_attempt_tenant
    ON notification_attempt (tenant_id, notification_id);
