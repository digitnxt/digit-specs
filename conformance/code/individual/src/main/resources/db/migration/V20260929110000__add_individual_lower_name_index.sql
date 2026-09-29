-- Index the case-insensitive name lookup behind the "name" uniqueness criterion.
--
-- The check compares LOWER(givenname) and LOWER(familyname), computed values that no column index
-- covers, so every create and update under that criterion read the whole tenant. An expression
-- index on the same lowercased values serves it; with familyName absent the leading
-- (tenantid, lower(givenname)) still applies.

CREATE INDEX IF NOT EXISTS idx_individual_tenant_lower_name_v3
    ON individual_v3 (tenantid, lower(givenname), lower(familyname));
