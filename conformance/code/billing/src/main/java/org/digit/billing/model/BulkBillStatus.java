package org.digit.billing.model;

/** Q6: contract spelling IN_PROGRESS (Go source has the typo IN_PROGESS; value never emitted). */
public enum BulkBillStatus {
    ACCEPTED, IN_PROGRESS, COMPLETED, PARTIALLY_COMPLETED, FAILED
}
