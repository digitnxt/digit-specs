package com.digit.individual.model;

/** DB-layer entity for an individual document (table individual_document_v3). */
public class Document {
    private String id;
    private String individualId;
    private String documentType;
    private String fileStoreId;
    private String documentUid;
    private boolean active;
    private String createdBy;
    private String modifiedBy;
    private long createdTime;
    private long modifiedTime;
    private String requestId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIndividualId() { return individualId; }
    public void setIndividualId(String individualId) { this.individualId = individualId; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getFileStoreId() { return fileStoreId; }
    public void setFileStoreId(String fileStoreId) { this.fileStoreId = fileStoreId; }
    public String getDocumentUid() { return documentUid; }
    public void setDocumentUid(String documentUid) { this.documentUid = documentUid; }
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
