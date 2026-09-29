package com.digit.boundary.validator;

import com.digit.boundary.constants.ErrorCodes;
import com.digit.boundary.model.Boundary;
import com.digit.boundary.repository.BoundaryRepository;

import org.digit.tracer.model.CustomException;
import tools.jackson.databind.JsonNode;

/** Boundary validation. Mirrors Go validator.BoundaryValidator.ValidateBoundary. */
public final class BoundaryValidator {
    private BoundaryValidator() {}

    public static void validateBoundary(BoundaryRepository repo, Boundary boundary) {
        if (boundary.getTenantId() == null || boundary.getTenantId().isEmpty()) {
            throw new CustomException(ErrorCodes.BAD_REQUEST, "Missing required field: tenantId");
        }
        if (boundary.getCode() == null || boundary.getCode().isEmpty()) {
            throw new CustomException(ErrorCodes.BAD_REQUEST, "Missing required field: code");
        }
        JsonNode geom = boundary.getGeometry();
        if (geom != null && !geom.isNull()) {
            GeometryValidator.validateGeometry(geom);
        }
        if (repo != null) {
            if (repo.existsByCode(boundary.getTenantId(), boundary.getCode())) {
                throw new CustomException(ErrorCodes.CONFLICT, "Duplicate boundary code for tenant");
            }
        }
    }
}
