package com.digit.individual.model;

/** DB-layer entity for a government identifier. Mirrors Go internal/models/identifier_entity.go (table individual_identifier_v3). */
public class Identifier {
    private String id;
    private String individualId;
    private String identifierType;
    private String identifierId;
    private boolean verified;
    private String documentType;
    private String fileStoreId;
    private boolean active = true;
    private String createdBy;
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;
    private String requestId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIndividualId() { return individualId; }
    public void setIndividualId(String individualId) { this.individualId = individualId; }
    public String getIdentifierType() { return identifierType; }
    public void setIdentifierType(String identifierType) { this.identifierType = identifierType; }
    public String getIdentifierId() { return identifierId; }
    public void setIdentifierId(String identifierId) { this.identifierId = identifierId; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getFileStoreId() { return fileStoreId; }
    public void setFileStoreId(String fileStoreId) { this.fileStoreId = fileStoreId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String modifiedBy) { this.modifiedBy = modifiedBy; }
    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }
    public long getModifiedTime() { return modifiedTime; }
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
