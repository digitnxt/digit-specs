package com.digit.individual.client;

import com.digit.individual.config.IndividualProperties;
import org.digit.tracer.config.TracerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IDGen service client. Mirrors Go internal/clients/idgen_client.go: posts {templateCode, variables}
 * to {host}{path} and expects {"id": "..."}. ORG is always injected (defaults to tenantId). Calls are
 * bounded by the platform's shared {@code tracer.http.connectTimeoutMs} / {@code readTimeoutMs}.
 */
@Component
public class IdgenClient {

    private static final Logger log = LoggerFactory.getLogger(IdgenClient.class);

    /**
     * An IDGen failure: {@code unavailable} when it could not be reached, otherwise the HTTP status it
     * answered with. The transport detail and raw body stay in the message, for the server log only.
     */
    public static final class IdgenException extends RuntimeException {
        private final boolean unavailable;
        private final int statusCode;

        IdgenException(boolean unavailable, int statusCode, String logMessage, Throwable cause) {
            super(logMessage, cause);
            this.unavailable = unavailable;
            this.statusCode = statusCode;
        }

        public boolean isUnavailable() { return unavailable; }

        /** The status IDGen answered with; 0 when it could not be reached. */
        public int getStatusCode() { return statusCode; }
    }

    private final IndividualProperties.Idgen config;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final JsonMapper mapper;

    public IdgenClient(IndividualProperties props, TracerProperties tracer) {
        this.config = props.getIdgen();
        this.mapper = JsonMapper.builder().build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(tracer.getHttp().getConnectTimeoutMs()))
                .build();
        this.requestTimeout = Duration.ofMillis(tracer.getHttp().getReadTimeoutMs());
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
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("templateCode", idFormat);
            payload.put("variables", vars);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            if (tenantId != null && !tenantId.isEmpty()) {
                rb.header("X-Tenant-Id", tenantId);
            }

            HttpResponse<String> resp;
            try {
                resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IdgenException(true, 0, "failed to call IDGen service: " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdgenException(true, 0, "interrupted calling IDGen service", e);
            }

            if (resp.statusCode() != 200) {
                throw new IdgenException(false, resp.statusCode(),
                        "idgen returned status=" + resp.statusCode() + " body=" + resp.body(), null);
            }
            Object idVal;
            try {
                idVal = mapper.readValue(resp.body(), Map.class).get("id");
            } catch (RuntimeException e) {
                throw new IdgenException(false, resp.statusCode(), "idgen returned an unreadable body: " + resp.body(), e);
            }
            if (idVal == null || String.valueOf(idVal).isEmpty()) {
                throw new IdgenException(false, resp.statusCode(), "idgen response missing 'id': " + resp.body(), null);
            }
            ids.add(String.valueOf(idVal));
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
