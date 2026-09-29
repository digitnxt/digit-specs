-- individualId is unique per tenant, not globally.
--
-- It is minted from an idgen sequence, and idgen counters are per template rather than global, so
-- two tenants legitimately mint the same value. Under the old global index the second tenant to do
-- so got a duplicate-key failure on create, and a freshly seeded counter kept failing until it had
-- climbed past every value other tenants already owned.
--
-- Widening to (tenantid, individualid) is strictly weaker than the constraint it replaces, so any
-- data that satisfied the old index satisfies the new one and this cannot fail on existing rows.
--
-- Safe because nothing depends on the value being globally unique: no foreign key references
-- individual_v3(individualid); the identifier/document/address child tables key on the individual's
-- row UUID despite their column also being named individualid; and every search pins tenantid
-- before any other predicate (IndividualRepository.buildWhere).

DROP INDEX IF EXISTS uk_individual_individualid_v3;

CREATE UNIQUE INDEX IF NOT EXISTS uk_individual_tenant_individualid_v3
    ON individual_v3 (tenantid, individualid);