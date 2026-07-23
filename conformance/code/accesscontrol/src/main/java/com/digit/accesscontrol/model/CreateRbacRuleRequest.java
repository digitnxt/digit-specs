package com.digit.accesscontrol.model;

import com.digit.accesscontrol.constants.Constants;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request to create an RBAC rule. Mirrors Go CreateRbacRuleRequest.
 * priority/enabled are boxed so we can tell "omitted" (null) from "sent zero/false".
 * applyDefaults() fills nil values with server-side defaults before validation/persistence.
 */
public class CreateRbacRuleRequest {

    private List<String> roleNames;
    private String httpMethod;
    private String path;
    private String effect;
    private Integer priority;
    private Boolean enabled;
    private JsonNode constraints;
    private String description;

    public void applyDefaults() {
        if (priority == null) {
            priority = Constants.DEFAULT_PRIORITY;
        }
        if (enabled == null) {
            enabled = Constants.DEFAULT_ENABLED;
        }
    }

    public List<String> getRoleNames() { return roleNames; }
    public void setRoleNames(List<String> roleNames) { this.roleNames = roleNames; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getEffect() { return effect; }
    public void setEffect(String effect) { this.effect = effect; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public JsonNode getConstraints() { return constraints; }
    public void setConstraints(JsonNode constraints) { this.constraints = constraints; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
