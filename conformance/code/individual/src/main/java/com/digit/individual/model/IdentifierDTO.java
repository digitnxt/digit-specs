package com.digit.individual.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Wire shape for an identifier. Mirrors Go internal/models/identifier_dto.go. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class IdentifierDTO {
    private String id = "";
    private String individualId;
    private String identifierType;
    private String identifierId;
    private boolean verified;
    private String documentType;
    private String fileStoreId;
    private boolean active;
    private String requestId;
    private AuditDetail auditDetail;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIndividualId() { return individualId; }
    public void setIndividualId(String individualId) { this.individualId = individualId; }
    public String getIdentifierType() { return identifierType; }
    public void setIdentifierType(String identifierType) { this.identifierType = identifierType; }
    public String getIdentifierId() { return identifierId; }
    public void setIdentifierId(String identifierId) { this.identifierId = identifierId; }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getFileStoreId() { return fileStoreId; }
    public void setFileStoreId(String fileStoreId) { this.fileStoreId = fileStoreId; }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetail getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetail auditDetail) { this.auditDetail = auditDetail; }
}
