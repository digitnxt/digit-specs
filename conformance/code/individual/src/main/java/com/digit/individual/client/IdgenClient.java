package com.digit.individual.client;

import com.digit.individual.config.IndividualProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IDGen service client. Mirrors Go internal/clients/idgen_client.go: posts {templateCode, variables}
 * to {host}{path}, expects {"id": "..."}; on any failure (disabled, network, non-200, bad body)
 * falls back to a UUID-based "IND-XXXXXXXX" id. ORG is always injected (defaults to tenantId).
 */
@Component
public class IdgenClient {

    private static final Logger log = LoggerFactory.getLogger(IdgenClient.class);

    private final IndividualProperties.Idgen config;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper mapper;

    public IdgenClient(IndividualProperties props) {
        this.config = props.getIdgen();
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Dependency-flag semantics: when idgen is DISABLED, generate local fallback ids (no error —
     * runs standalone). When ENABLED, a failure (non-200 / missing id / exception) is surfaced as an
     * error — we do NOT silently fall back, so a broken dependency fails loudly.
     */
    public List<String> generateIds(String tenantId, String idFormat, int count, Map<String, String> customVars) {
        if (!config.isEnabled()) {
            log.info("IDGen disabled, generating fallback IDs (count={})", count);
            return generateFallbackIds(count);
        }

        String url = config.getHost() + config.getPath();

        Map<String, String> vars = customVars == null ? new HashMap<>() : new HashMap<>(customVars);
        vars.putIfAbsent("ORG", tenantId);

        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("templateCode", idFormat);
                payload.put("variables", vars);
                String json = mapper.writeValueAsString(payload);

                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
                if (tenantId != null && !tenantId.isEmpty()) {
                    rb.header("X-Tenant-Id", tenantId);
                }
                HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() != 200) {
                    throw new RuntimeException("idgen returned status=" + resp.statusCode() + " body=" + resp.body());
                }
                Map<?, ?> body = mapper.readValue(resp.body(), Map.class);
                Object idVal = body.get("id");
                if (idVal == null || String.valueOf(idVal).isEmpty()) {
                    throw new RuntimeException("idgen response missing 'id'");
                }
                ids.add(String.valueOf(idVal));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("failed to call IDGen service: " + e.getMessage(), e);
            }
        }
        return ids;
    }

    private List<String> generateFallbackIds(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add("IND-" + UUID.randomUUID().toString().substring(0, 8));
        }
        return ids;
    }
}
