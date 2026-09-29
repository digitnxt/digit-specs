-- firstloginurl becomes a list of sign-in links instead of a single one.
--
-- A tenant admin normally has more than one destination (admin console, employee portal, citizen
-- portal), and the temp-password email should offer all of them in a defined order.
--
-- Stored as a JSON array in the existing column rather than a new table or a set of numbered
-- columns: the value is read and written whole, never queried by element, so a child table would
-- buy nothing and numbered columns would cap the count arbitrarily. The column keeps its singular
-- name to avoid rewriting every reference for a rename that changes no behaviour.
--
-- VARCHAR(1024) → TEXT because the cap was sized for one URL and several no longer fit. TEXT has no
-- storage penalty in Postgres; both are varlena and share the same TOAST path.
--
-- Rows written before this hold a bare URL rather than a JSON array and are deliberately left as
-- they are: only new tenants matter here. The reader falls back to treating an unparseable value as
-- a single-element list, so those rows still load and still return the one link they were emailed.

ALTER TABLE tenant_v1
    ALTER COLUMN firstloginurl TYPE TEXT;