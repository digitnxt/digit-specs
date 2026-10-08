package org.digit.billing.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.digit.billing.model.DemandRequests.BulkFailure;
import org.digit.billing.model.DemandRequests.BulkResponse;
import org.digit.billing.model.ErrorCodes;
import org.digit.tracer.model.Error;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Go writeBulkResponse: 201/200 all-success, 207 mixed, 400/422 all-failed. */
final class BulkResponses {

    private static final Set<String> REFERENTIAL_CODES = Set.of(
            ErrorCodes.UNKNOWN_BUSINESS_SERVICE, ErrorCodes.UNKNOWN_TAX_HEAD, ErrorCodes.INVALID_TAX_HEAD);

    private BulkResponses() {
    }

    static ResponseEntity<?> write(HttpStatus successStatus, BulkResponse response) {
        boolean noFailures = response.failures() == null || response.failures().isEmpty();
        boolean noSuccess = response.success() == null || response.success().isEmpty();
        if (noFailures) {
            return ResponseEntity.status(successStatus).body(response.success());
        }
        if (noSuccess) {
            HttpStatus status = allReferentialFailures(response.failures())
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(flattenFailures(response.failures()));
        }
        return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(response);
    }

    private static boolean allReferentialFailures(List<BulkFailure> failures) {
        for (BulkFailure failure : failures) {
            for (Error error : failure.errors()) {
                if (!REFERENTIAL_CODES.contains(error.getCode())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<Error> flattenFailures(List<BulkFailure> failures) {
        List<Error> out = new ArrayList<>();
        for (BulkFailure failure : failures) {
            if (failure.errors() == null || failure.errors().isEmpty()) {
                out.add(new Error("UNKNOWN_ERROR", "Unknown failure",
                        "Processing failed without error details", null));
                continue;
            }
            for (Error error : failure.errors()) {
                String description = error.getDescription();
                if (description != null && !description.isEmpty()) {
                    description = "item " + failure.index() + ": " + description;
                }
                out.add(new Error(error.getCode(), error.getMessage(), description, error.getParams()));
            }
        }
        return out;
    }
}
