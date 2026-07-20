package com.digit.individual.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Wire shape for tenant validation config. Mirrors Go internal/models/config_dto.go. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"mobileRegex", "nameRegex", "uniquenessCriteria", "version", "requestId", "auditDetail"})
public class ConfigDTO {
    private String mobileRegex;
    private String nameRegex;
    private List<String> uniquenessCriteria;
    private int version;
    private String requestId;
    private AuditDetail auditDetail;

    public String getMobileRegex() { return mobileRegex; }
    public void setMobileRegex(String mobileRegex) { this.mobileRegex = mobileRegex; }
    public String getNameRegex() { return nameRegex; }
    public void setNameRegex(String nameRegex) { this.nameRegex = nameRegex; }
    public List<String> getUniquenessCriteria() { return uniquenessCriteria; }
    public void setUniquenessCriteria(List<String> uniquenessCriteria) { this.uniquenessCriteria = uniquenessCriteria; }
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public AuditDetail getAuditDetail() { return auditDetail; }
    public void setAuditDetail(AuditDetail auditDetail) { this.auditDetail = auditDetail; }
}
