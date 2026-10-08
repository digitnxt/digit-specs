package org.digit.billing.repo;

/** The demands GiST exclusion constraint (no_overlapping_demands) rejected an insert/update. */
public class DemandPeriodConflictException extends RuntimeException {

    public DemandPeriodConflictException(Throwable cause) {
        super("demand period conflict", cause);
    }
}
