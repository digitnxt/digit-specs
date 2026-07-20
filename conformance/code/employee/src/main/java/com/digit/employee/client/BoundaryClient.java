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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for the Boundary service. Mirrors Go internal/clients/boundary/client.go SearchRelationship:
 * GET {baseURL}/boundary/v3/relationship?hierarchyType=&boundaryType=&codes=... and walks the
 * EnrichedBoundary tree, returning the set of requested codes that matched the boundaryType.
 */
@Component
public class BoundaryClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String baseURL;
    private final String path;

    public BoundaryClient(EmployeeProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String b = props.getBoundary().getBaseUrl();
        this.baseURL = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        // Config-driven relationship endpoint path (Go: "/" + strings.Trim(cfg.Path, "/")).
        this.path = normalizePath(props.getBoundary().getPath());
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

    /** Returns the set of codes matched under the given hierarchyType/boundaryType. */
    public Set<String> searchRelationship(String tenantId, String hierarchyType, String boundaryType,
                                          List<String> codes) {
        Set<String> found = new HashSet<>();
        if (codes == null || codes.isEmpty()) {
            return found;
        }

        StringBuilder params = new StringBuilder();
        if (hierarchyType != null && !hierarchyType.isEmpty()) {
            appendParam(params, "hierarchyType", hierarchyType);
        }
        if (boundaryType != null && !boundaryType.isEmpty()) {
            appendParam(params, "boundaryType", boundaryType);
        }
        for (String code : codes) {
            appendParam(params, "codes", code);
        }
        String reqURL = baseURL + path + "?" + params;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(reqURL))
                    .header("X-Tenant-Id", tenantId)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("boundary relationship service returned status: " + resp.statusCode());
            }

            Set<String> requested = new HashSet<>(codes);
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode tenantBoundary = root.get("tenantBoundary");
            if (tenantBoundary != null && tenantBoundary.isArray()) {
                for (JsonNode tb : tenantBoundary) {
                    String ht = tb.has("hierarchyType") ? tb.get("hierarchyType").asText("") : "";
                    if (hierarchyType != null && !hierarchyType.isEmpty() && !ht.equals(hierarchyType)) {
                        continue;
                    }
                    walk(tb.get("boundary"), requested, boundaryType, found);
                }
            }
            return found;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("boundary relationship request failed: " + e.getMessage(), e);
        }
    }

    private void walk(JsonNode nodes, Set<String> requested, String boundaryType, Set<String> found) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode n : nodes) {
            String code = n.has("code") ? n.get("code").asText("") : "";
            String bt = n.has("boundaryType") ? n.get("boundaryType").asText("") : "";
            if (requested.contains(code) && bt.equals(boundaryType)) {
                found.add(code);
            }
            JsonNode children = n.get("children");
            if (children != null && children.isArray() && children.size() > 0) {
                walk(children, requested, boundaryType, found);
            }
        }
    }

    private void appendParam(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
