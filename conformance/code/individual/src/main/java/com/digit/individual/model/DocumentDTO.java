package com.digit.individual.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Wire shape for a document. Mirrors Go internal/models/document_dto.go. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class DocumentDTO {
    private String id = "";
    private String documentType;
    private String fileStoreId;
    private String documentUid;
    private String requestId;
    private AuditDetail auditDetail;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getFileStoreId() { return fileStoreId; }
    public void setFileStoreId(String fileStoreId) { this.fileStoreId = fileStoreId; }
    public String getDocumentUid() { return documentUid; }
    public void setDocumentUid(String documentUid) { this.documentUid = documentUid; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetail getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetail auditDetail) { this.auditDetail = auditDetail; }
}
