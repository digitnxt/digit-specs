package com.digit.individual.model;

import com.digit.individual.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * auditDetail omits zero timestamps on the way out, so a client echoing a GET body back in a PUT sends
 * an auditDetail without them. The request mapper must accept that rather than reject it.
 */
class AuditDetailBindingTest {

    private final JsonMapper strict = new JacksonConfig().strictJsonMapper();

    @Test
    void auditDetailWithoutTimestamps_isAccepted() {
        String body = "{\"givenName\":\"Ravi\",\"address\":[{\"city\":\"Pune\","
                + "\"auditDetail\":{\"modifiedBy\":\"editor\",\"modifiedTime\":5}}]}";

        IndividualDTO dto = assertDoesNotThrow(() -> strict.readValue(body, IndividualDTO.class));

        AuditDetail audit = dto.getAddresses().get(0).getAuditDetail();
        assertEquals(0L, audit.getCreatedTime());
        assertEquals(5L, audit.getModifiedTime());
    }

    @Test
    void zeroTimestamp_isOmittedOnTheWayOut_andStillReadsBack() {
        String json = strict.writeValueAsString(AuditDetail.of(null, "editor", 0, 5));

        assertFalse(json.contains("createdTime"), json);
        assertEquals(5L, assertDoesNotThrow(() -> strict.readValue(json, AuditDetail.class)).getModifiedTime());
    }
}
