package org.digit.billing.model;

public record AuditDetail(String createdBy, long createdTime, String modifiedBy, long modifiedTime) {
}
