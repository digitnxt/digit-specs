package org.digit.billing.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.digit.billing.model.DemandRequests.BulkFailure;
import org.digit.billing.model.DemandRequests.BulkResponse;
import org.digit.tracer.model.Error;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Go writeBulkResponse branch rules + "item N: " prefixes (PORT_PLAN §7-6). */
class BulkResponsesTest {

    private static BulkFailure failure(int index, String code, String description) {
        return new BulkFailure(index, List.of(new Error(code, "msg", description, null)));
    }

    @Test
    void allSuccessReturnsPlainArrayAtSuccessStatus() {
        ResponseEntity<?> response = BulkResponses.write(HttpStatus.CREATED,
                new BulkResponse(List.of(), List.of()));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(List.of(), response.getBody());
    }

    @Test
    void allFailedNonReferentialIs400Flattened() {
        ResponseEntity<?> response = BulkResponses.write(HttpStatus.CREATED, new BulkResponse(null,
                List.of(failure(0, "DEMAND_CONFLICT", "overlap"), failure(2, "CREATION_FAILED", "boom"))));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Error> body = (List<Error>) response.getBody();
        assertEquals("item 0: overlap", body.get(0).getDescription());
        assertEquals("item 2: boom", body.get(1).getDescription());
    }

    @Test
    void allReferentialFailuresAre422() {
        ResponseEntity<?> response = BulkResponses.write(HttpStatus.CREATED, new BulkResponse(null,
                List.of(failure(0, "UNKNOWN_BUSINESS_SERVICE", "x"),
                        failure(1, "UNKNOWN_TAX_HEAD", "y"),
                        failure(2, "INVALID_TAX_HEAD", "z"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    }

    @Test
    void oneNonReferentialFailureDowngradesTo400() {
        ResponseEntity<?> response = BulkResponses.write(HttpStatus.CREATED, new BulkResponse(null,
                List.of(failure(0, "UNKNOWN_TAX_HEAD", "y"), failure(1, "INVALID_PERIOD", "p"))));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void mixedIs207WithEnvelope() {
        org.digit.billing.model.Demand demand = new org.digit.billing.model.Demand(
                java.util.UUID.randomUUID(), "PT", 1, 2, "C-1", null, null, null, List.of(),
                org.digit.billing.model.DemandStatus.ACTIVE, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, false, java.util.Map.of(), 1,
                new org.digit.billing.model.AuditDetail("u", 1, "u", 1));
        BulkResponse mixed = new BulkResponse(List.of(demand),
                List.of(failure(1, "DEMAND_CONFLICT", "overlap")));
        ResponseEntity<?> response = BulkResponses.write(HttpStatus.CREATED, mixed);
        assertEquals(HttpStatus.MULTI_STATUS, response.getStatusCode());
        assertEquals(mixed, response.getBody());
    }

    @Test
    void emptyErrorListBecomesUnknownError() {
        ResponseEntity<?> response = BulkResponses.write(HttpStatus.CREATED, new BulkResponse(null,
                List.of(new BulkFailure(0, List.of()))));
        @SuppressWarnings("unchecked")
        List<Error> body = (List<Error>) response.getBody();
        assertEquals("UNKNOWN_ERROR", body.get(0).getCode());
    }
}
