package com.digit.employee.client;

import com.digit.employee.config.EmployeeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
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

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String baseURL;

    public KeycloakClient(EmployeeProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String b = props.getKeycloak().getBaseUrl();
        this.baseURL = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** Returns the user id when found, or null when not found. Mirrors Go GetUserByID. */
    public String getUserByID(String tenantId, String userID, String authHeader) {
        String realms = tenantId == null ? "" : tenantId.toUpperCase(Locale.ROOT);
        String reqURL = baseURL + "/admin/realms/" + realms + "/users/" + userID;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .GET();
            if (authHeader != null) {
                b.header("Authorization", authHeader);
            }
            HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());

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
        try {
            for (int first = 0; ; first += ROLE_MEMBER_PAGE_SIZE) {
                String reqURL = baseURL + "/admin/realms/" + realm + "/roles/" + encodedRole
                        + "/users?first=" + first + "&max=" + ROLE_MEMBER_PAGE_SIZE;
                HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(reqURL)).GET();
                if (authHeader != null) {
                    b.header("Authorization", authHeader);
                }
                HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());

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
