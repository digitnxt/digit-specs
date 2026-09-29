package org.digit.idgen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Service-level configuration. Timezone governs {DATE:*} rendering and sequence
 * scope keys only — audit/event timestamps are epoch millis and zone-free.
 */
@ConfigurationProperties(prefix = "idgen")
public record IdgenProperties(
		@DefaultValue("UTC") String timezone,
		@DefaultValue Events events) {

	public record Events(
			@DefaultValue("false") boolean enabled,
			@DefaultValue Topics topics) {
	}

	public record Topics(
			@DefaultValue("idgen-create-template") String create,
			@DefaultValue("idgen-update-template") String update,
			@DefaultValue("idgen-delete-template") String delete) {
	}
}
