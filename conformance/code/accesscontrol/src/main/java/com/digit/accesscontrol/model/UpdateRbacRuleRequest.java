package com.digit.accesscontrol.model;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * PATCH-style partial update of an RBAC rule. Mirrors Go UpdateRbacRuleRequest.
 * Required fields use plain nullable refs: null = don't touch, present = update (JSON null on these
 * is rejected upstream). Optional clearable fields (constraints, description) use Nullable to
 * distinguish absent from explicit-null-clears.
 *
 * <p>Populated manually from the JSON tree by the controller (not by Jackson binding) so the
 * three-state semantics survive.
 */
public class UpdateRbacRuleRequest {

    /** Top-level fields that must not accept JSON null. Mirrors Go RbacNonNullableUpdateFields. */
    public static final List<String> NON_NULLABLE_FIELDS =
            List.of("roleNames", "httpMethod", "path", "effect", "priority", "enabled");

    private List<String> roleNames;   // null = absent
    private String httpMethod;
    private String path;
    private String effect;
    private Integer priority;
    private Boolean enabled;
    private Nullable<JsonNode> constraints = new Nullable<>();
    private Nullable<String> description = new Nullable<>();

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
    public Nullable<JsonNode> getConstraints() { return constraints; }
    public void setConstraints(Nullable<JsonNode> constraints) { this.constraints = constraints; }
    public Nullable<String> getDescription() { return description; }
    public void setDescription(Nullable<String> description) { this.description = description; }
}
