package com.digit.employee.client;

import com.digit.employee.config.EmployeeProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client for the Individual service. Mirrors Go internal/clients/individual/client.go:
 * GET {baseURL}/individuals/v3/individuals/{id}; 404 -> null (not an error).
 */
@Component
public class IndividualClient {

    private final DownstreamHttp http;
    private final ObjectMapper objectMapper;
    private final String baseURL;
    private final String path;

    public IndividualClient(EmployeeProperties props, ObjectMapper objectMapper, DownstreamHttp http) {
        this.objectMapper = objectMapper;
        this.http = http;
        this.baseURL = props.getIndividual().getHost();
        this.path = props.getIndividual().getPath();
    }

    /** Returns the individual id when found, or null when not found. Mirrors Go GetIndividualByID. */
    public String getIndividualByID(String tenantId, String individualID) {
        String reqURL = baseURL + path + "/" + individualID;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("X-Tenant-ID", tenantId)
                    .header("Content-Type", "application/json")
                    .header("X-User-ID", "employee-service")
                    .GET();
            HttpResponse<String> resp = http.send(req);

            if (resp.statusCode() == 404) {
                return null;
            }
            if (resp.statusCode() != 200) {
                throw new IndividualApiException(resp.statusCode(), resp.body());
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String id = node.has("id") ? node.get("id").asText("") : "";
            if (id.isEmpty()) {
                return null;
            }
            return id;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("individual service request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Creates an individual. {@code linkUserId} (the new Keycloak user id) is injected into the body
     * as {@code userId}; any client-supplied {@code tenantId} is stripped (tenant travels as the
     * header, and the individual DTO rejects unknown fields). {@code auditUserId} (the caller) is
     * forwarded as X-User-ID so the individual's createdBy reflects the real actor. Returns the full
     * response body as a {@code Map} (not a JsonNode — a Map serializes correctly regardless of which
     * Jackson version the response converter uses); throws {@link IndividualApiException} on any
     * non-201. Mirrors Go {@code individual.CreateIndividual}.
     */
    public Map<String, Object> createIndividual(String tenantId, String auditUserId, String linkUserId, JsonNode individual) {
        String reqURL = baseURL + path;
        try {
            ObjectNode body = (ObjectNode) individual.deepCopy();
            body.put("userId", linkUserId);
            body.remove("tenantId");
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("X-Tenant-ID", tenantId)
                    .header("X-User-ID", auditUserId == null ? "" : auditUserId)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            HttpResponse<String> resp = http.send(req);
            if (resp.statusCode() != 201) {
                throw new IndividualApiException(resp.statusCode(), resp.body());
            }
            return objectMapper.readValue(resp.body(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("individual service request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Soft-deletes an individual by its UUID id (the individual service delete is a soft delete). Used
     * as a compensation step; a 404 is treated as success so it's idempotent. Mirrors Go
     * {@code individual.DeleteIndividual}.
     */
    public void deleteIndividual(String tenantId, String auditUserId, String individualId) {
        String reqURL = baseURL + path + "/" + individualId;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("X-Tenant-ID", tenantId)
                    .header("X-User-ID", auditUserId == null ? "" : auditUserId)
                    .method("DELETE", HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> resp = http.send(req);
            if (resp.statusCode() != 204 && resp.statusCode() != 404) {
                throw new IndividualApiException(resp.statusCode(), resp.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("individual service request failed: " + e.getMessage(), e);
        }
    }
}
