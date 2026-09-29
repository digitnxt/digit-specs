-- country + firstLoginUrl on tenant_v1.
--
-- country is another optional contact field alongside city/state/pincode, so it is nullable for the
-- same reason those are: TenantCreateRequest treats it as optional and a nil value has to round-trip
-- to SQL NULL rather than an empty string.
--
-- firstLoginUrl is the sign-in link the tenant admin is emailed at creation. It is nullable because
-- rows created before this migration have no recorded value, and because a tenant created with a
-- caller-supplied password is never emailed one. It is 1024 rather than 512: the value is a whole
-- URL and the configured template may carry a context path plus query string.

ALTER TABLE tenant_v1
    ADD COLUMN IF NOT EXISTS country       VARCHAR(128),
    ADD COLUMN IF NOT EXISTS firstloginurl VARCHAR(1024);