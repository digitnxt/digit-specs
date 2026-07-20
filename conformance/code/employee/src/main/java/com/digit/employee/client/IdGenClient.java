package com.digit.employee.client;

import com.digit.employee.config.EmployeeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the IDGen service. Mirrors Go internal/clients/idgen/client.go: POSTs {@code count}
 * times to {@code host + path} with {templateCode, variables{ORG:tenantId}} and collects {@code id}.
 */
@Component
public class IdGenClient {

    private static final Logger log = LoggerFactory.getLogger(IdGenClient.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String host;
    private final String path;
    private final String idgenName;
    private final boolean enabled;

    public IdGenClient(EmployeeProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Trim a trailing slash on host so host + path (e.g. "/idgen/v3/generate") yields a
        // single-slash URL, matching Go idgen client (strings.TrimSuffix(host, "/")).
        String h = props.getIdgen().getHost();
        this.host = (h != null && h.endsWith("/")) ? h.substring(0, h.length() - 1) : h;
        this.path = props.getIdgen().getPath();
        this.idgenName = props.getIdgen().getIdgenName();
        this.enabled = props.getIdgen().isEnabled();
    }

    /**
     * Generates {@code count} formatted IDs. Dependency-flag semantics: when idgen is DISABLED we
     * generate local fallback ids (no error — the service runs standalone). When ENABLED, a failure
     * (network / non-200 / missing id) is surfaced as an error — we do NOT silently fall back, so a
     * broken dependency fails loudly rather than minting non-standard ids.
     */
    public List<String> generateIDs(String tenantId, int count, Map<String, String> customVars) {
        if (!enabled) {
            log.info("IDGen disabled — generating {} local fallback id(s)", count);
            return generateFallbackIds(count);
        }
        String url = host + path;

        // Variables are passed through as-is (Go removed the ORG default injection in 8749c30e).
        Map<String, String> vars = customVars == null ? new HashMap<>() : new HashMap<>(customVars);

        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("templateCode", idgenName);
            payload.put("variables", vars);

            try {
                byte[] jsonData = objectMapper.writeValueAsBytes(payload);
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(jsonData));
                if (tenantId != null && !tenantId.isEmpty()) {
                    b.header("X-Tenant-Id", tenantId);
                }
                HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() != 200) {
                    // Fail immediately with status + body (matches Go idgen client).
                    throw new RuntimeException("idgen returned status=" + resp.statusCode()
                            + " body=" + resp.body());
                }
                JsonNode node = objectMapper.readTree(resp.body());
                String id = node.has("id") ? node.get("id").asText("") : "";
                if (id.isEmpty()) {
                    throw new RuntimeException("idgen response missing 'id'");
                }
                ids.add(id);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("failed to call IDGen service: " + e.getMessage(), e);
            }
        }
        return ids;
    }

    /** Local id generation used only when idgen is disabled. */
    private List<String> generateFallbackIds(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add("EMP-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        }
        return ids;
    }
}
