CREATE UNIQUE INDEX uq_provider_mapping_tenant_channel_country
    ON provider_mapping (tenant_id, channel)
    WHERE country IS NULL;

CREATE UNIQUE INDEX uq_provider_mapping_tenant_channel_country_val
    ON provider_mapping (tenant_id, channel, country)
    WHERE country IS NOT NULL;
