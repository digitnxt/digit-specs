package com.digit.employee.client;

import com.digit.employee.config.EmployeeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client for Keycloak. Mirrors Go internal/clients/keycloak/client.go:
 * GET {baseURL}/admin/realms/{TENANT_UPPER}/users/{userId}; 404 -> null (not an error).
 */
@Component
public class KeycloakClient {

    private final DownstreamHttp http;
    private final ObjectMapper objectMapper;
    private final String baseURL;
    /** Supplies the service-account header for read-only lookups; see {@link RealmReadAuth}. */
    private final RealmReadAuth realmReadAuth;

    public KeycloakClient(EmployeeProperties props, ObjectMapper objectMapper,
                          RealmReadAuth realmReadAuth, DownstreamHttp http) {
        this.objectMapper = objectMapper;
        this.http = http;
        this.realmReadAuth = realmReadAuth;
        String b = props.getKeycloak().getBaseUrl();
        this.baseURL = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** Returns the user id when found, or null when not found. Mirrors Go GetUserByID. */
    public String getUserByID(String tenantId, String userID, String authHeader) {
        String realms = tenantId == null ? "" : tenantId.toUpperCase(Locale.ROOT);
        String reqURL = baseURL + "/admin/realms/" + realms + "/users/" + userID;
        try {
            // Read-only lookup: use the service account so an ordinary caller's token, which
            // lacks realm-management view permissions, does not 403.
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .GET();
            String readAuth = realmReadAuth.forRealm(realms, authHeader);
            if (readAuth != null) {
                b.header("Authorization", readAuth);
            }
            HttpResponse<String> resp = http.send(b);

            if (resp.statusCode() == 404) {
                return null;
            }
            if (resp.statusCode() != 200) {
                throw new RuntimeException("keycloak service returned status: " + resp.statusCode());
            }
            JsonNode node = objectMapper.readTree(resp.body());
            // Go returns &User{...} even when id is empty; non-null indicates "found".
            return node.has("id") ? node.get("id").asText("") : "";
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("keycloak service request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a user in the tenant realm (realm == upper(tenantId)) and returns the new user's id,
     * parsed from the Location header Keycloak sets on a 201 (the create endpoint returns an empty
     * body). A 409 (and any other non-201) is surfaced as {@link KeycloakApiException} so the service
     * can map it. The caller's bearer token is forwarded — Keycloak enforces that the caller may
     * create users in the realm. Mirrors Go {@code keycloak.CreateUser}.
     */
    public String createUser(String tenantId, KeycloakUserRequest user, String authHeader) {
        String realm = tenantId == null ? "" : tenantId.toUpperCase(Locale.ROOT);
        String reqURL = baseURL + "/admin/realms/" + realm + "/users";
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("username", user.username);
            if (user.email != null && !user.email.isEmpty()) payload.put("email", user.email);
            if (user.firstName != null && !user.firstName.isEmpty()) payload.put("firstName", user.firstName);
            if (user.lastName != null && !user.lastName.isEmpty()) payload.put("lastName", user.lastName);
            payload.put("enabled", true);
            payload.put("emailVerified", user.emailVerified);
            payload.putObject("attributes").putArray("mobileNumber").add(user.username);
            ObjectNode cred = objectMapper.createObjectNode();
            cred.put("type", "password");
            cred.put("value", user.password);
            cred.put("temporary", false);
            payload.putArray("credentials").add(cred);
            payload.putArray("requiredActions"); // empty: no forced reset (matches account superuser)

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload)));
            if (authHeader != null) {
                b.header("Authorization", authHeader);
            }
            HttpResponse<String> resp = http.send(b);

            if (resp.statusCode() != 201) {
                throw new KeycloakApiException(resp.statusCode(), resp.body());
            }
            // The new user's id comes only from the Location header (.../users/{id}); body is empty.
            String location = resp.headers().firstValue("Location").orElse("");
            int idx = location.lastIndexOf('/');
            String id = idx >= 0 ? location.substring(idx + 1) : location;
            if (id.isEmpty()) {
                throw new RuntimeException("keycloak did not return a user id in the Location header");
            }
            return id;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("keycloak service request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches a realm role by name. Returns null when the role does not exist (404) so callers can
     * reject an unknown role as a clean 400 — this is also the existence check run before creating the
     * user. On success returns the full role representation (used as-is in the role-mappings POST).
     * Mirrors Go {@code keycloak.GetRealmRole}.
     */
    public JsonNode getRealmRole(String tenantId, String roleName, String authHeader) {
        String realm = tenantId == null ? "" : tenantId.toUpperCase(Locale.ROOT);
        String encoded = URLEncoder.encode(roleName, StandardCharsets.UTF_8);
        String reqURL = baseURL + "/admin/realms/" + realm + "/roles/" + encoded;
        try {
            // Read-only lookup: service account. Role-grant escalation is still guarded by
            // assignRealmRoles, which forwards the caller's token and surfaces Keycloak's 403.
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(reqURL)).GET();
            String readAuth = realmReadAuth.forRealm(realm, authHeader);
            if (readAuth != null) {
                b.header("Authorization", readAuth);
            }
            HttpResponse<String> resp = http.send(b);
            if (resp.statusCode() == 404) {
                return null;
            }
            if (resp.statusCode() != 200) {
                throw new KeycloakApiException(resp.statusCode(), resp.body());
            }
            return objectMapper.readTree(resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("keycloak service request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Assigns the given realm roles to a user in one call — Keycloak's role-mappings endpoint accepts
     * an array of role representations and returns 204, so a partial-assignment state is impossible. A
     * non-success response is a {@link KeycloakApiException} (e.g. 403 when the caller may not grant a
     * role). Mirrors Go {@code keycloak.AssignRealmRoles}.
     */
    public void assignRealmRoles(String tenantId, String userID, List<JsonNode> roles, String authHeader) {
        if (roles == null || roles.isEmpty()) {
            return;
        }
        String realm = tenantId == null ? "" : tenantId.toUpperCase(Locale.ROOT);
        String reqURL = baseURL + "/admin/realms/" + realm + "/users/" + userID + "/role-mappings/realm";
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (JsonNode r : roles) {
                arr.add(r);
            }
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(arr)));
            if (authHeader != null) {
                b.header("Authorization", authHeader);
            }
            HttpResponse<String> resp = http.send(b);
            if (resp.statusCode() != 204 && resp.statusCode() != 200) {
                throw new KeycloakApiException(resp.statusCode(), resp.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("keycloak service request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a user by id. A 404 is treated as success so the call is idempotent when used as a
     * compensation step in the onboarding saga. Mirrors Go {@code keycloak.DeleteUser}.
     */
    public void deleteUser(String tenantId, String userID, String authHeader) {
        String realm = tenantId == null ? "" : tenantId.toUpperCase(Locale.ROOT);
        String reqURL = baseURL + "/admin/realms/" + realm + "/users/" + userID;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody());
            if (authHeader != null) {
                b.header("Authorization", authHeader);
            }
            HttpResponse<String> resp = http.send(b);
            if (resp.statusCode() != 204 && resp.statusCode() != 404) {
                throw new KeycloakApiException(resp.statusCode(), resp.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("keycloak service request failed: " + e.getMessage(), e);
        }
    }

    private static final int ROLE_MEMBER_PAGE_SIZE =
            com.digit.employee.constants.ValidationConstants.KEYCLOAK_ROLE_MEMBER_PAGE_SIZE;

    /**
     * Returns the Keycloak user IDs that hold {@code role} in the tenant's realm, paginating
     * (GET /admin/realms/{REALM}/roles/{role}/users?first&max) until a short page ends the set.
     * A missing role (404) yields an empty list, not an error — mirrors Go GetUserIDsByRole.
     */
    public List<String> getUserIDsByRole(String tenantId, String role, String authHeader) {
        String realm = tenantId == null ? "" : tenantId.toUpperCase(Locale.ROOT);
        String encodedRole = URLEncoder.encode(role, StandardCharsets.UTF_8);
        List<String> userIds = new ArrayList<>();
        // Resolved once for the whole pagination loop: the cached service-account token outlives a
        // single page walk.
        String readAuth = realmReadAuth.forRealm(realm, authHeader);
        try {
            for (int first = 0; ; first += ROLE_MEMBER_PAGE_SIZE) {
                String reqURL = baseURL + "/admin/realms/" + realm + "/roles/" + encodedRole
                        + "/users?first=" + first + "&max=" + ROLE_MEMBER_PAGE_SIZE;
                HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(reqURL)).GET();
                if (readAuth != null) {
                    b.header("Authorization", readAuth);
                }
                HttpResponse<String> resp = http.send(b);

                if (resp.statusCode() == 404) {
                    return userIds; // role does not exist → no members
                }
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("keycloak returned status=" + resp.statusCode()
                            + " body=" + resp.body());
                }
                JsonNode members = objectMapper.readTree(resp.body());
                int count = 0;
                if (members.isArray()) {
                    for (JsonNode m : members) {
                        count++;
                        JsonNode idNode = m.get("id");
                        if (idNode != null && !idNode.asText("").isEmpty()) {
                            userIds.add(idNode.asText());
                        }
                    }
                }
                if (count < ROLE_MEMBER_PAGE_SIZE) {
                    break; // short page → end
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("keycloak service request failed: " + e.getMessage(), e);
        }
        return userIds;
    }
}
