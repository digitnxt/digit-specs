package com.digit.employee.client;

import com.digit.employee.config.EmployeeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Client for the Individual service. Mirrors Go internal/clients/individual/client.go:
 * GET {baseURL}/individuals/v3/individuals/{id}; 404 -> null (not an error).
 */
@Component
public class IndividualClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String baseURL;
    private final String path;

    public IndividualClient(EmployeeProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String b = props.getIndividual().getHost();
        this.baseURL = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        // Config-driven individuals-collection path (Go: "/" + strings.Trim(cfg.Path, "/")).
        this.path = normalizePath(props.getIndividual().getPath());
    }

    private static String normalizePath(String p) {
        if (p == null) {
            return "";
        }
        int s = 0, e = p.length();
        while (s < e && p.charAt(s) == '/') s++;
        while (e > s && p.charAt(e - 1) == '/') e--;
        return "/" + p.substring(s, e);
    }

    /** Returns the individual id when found, or null when not found. Mirrors Go GetIndividualByID. */
    public String getIndividualByID(String tenantId, String individualID) {
        String reqURL = baseURL + path + "/" + individualID;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("X-Tenant-ID", tenantId)
                    .header("Content-Type", "application/json")
                    .header("X-User-ID", "employee-service")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 404) {
                return null;
            }
            if (resp.statusCode() != 200) {
                throw new RuntimeException("individual service returned status: " + resp.statusCode());
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
}
