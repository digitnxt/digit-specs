package org.digit.billing.client;

import java.util.List;
import java.util.Map;
import org.digit.billing.config.BillingProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Internal idgen HTTP client over the tracer's logAwareRestClient (correlation
 * forwarded automatically; X-Tenant-ID set explicitly as the Go client did).
 * Non-2xx or empty ids surface as RuntimeException — callers wrap with their
 * Go error codes.
 */
@Component
public class IdgenClient {

    public record GenerateIdRequest(String templateCode, Map<String, String> variables) {
    }

    public record GenerateIdResponse(String tenantId, String templateCode, String version, String id) {
    }

    public record BulkGenerateIdRequest(String templateCode, int count, Map<String, String> variables) {
    }

    public record BulkGenerateIdResponse(String templateCode, String version, int count, List<String> ids) {
    }

    private final RestClient restClient;
    private final BillingProperties.Idgen idgen;

    public IdgenClient(@Qualifier("logAwareRestClient") RestClient restClient, BillingProperties properties) {
        this.restClient = restClient;
        this.idgen = properties.idgen();
    }

    public String generateId(String tenantId, String templateCode, Map<String, String> variables) {
        GenerateIdResponse response = restClient.post()
                .uri(idgen.host() + idgen.generatePath())
                .header("X-Tenant-ID", tenantId)
                .body(new GenerateIdRequest(templateCode, variables))
                .retrieve()
                .body(GenerateIdResponse.class);
        if (response == null || response.id() == null || response.id().isEmpty()) {
            throw new IllegalStateException("idgen returned empty id");
        }
        return response.id();
    }

    public List<String> bulkGenerateId(String tenantId, String templateCode, int count,
                                       Map<String, String> variables) {
        BulkGenerateIdResponse response = restClient.post()
                .uri(idgen.host() + idgen.bulkGeneratePath())
                .header("X-Tenant-ID", tenantId)
                .body(new BulkGenerateIdRequest(templateCode, count, variables))
                .retrieve()
                .body(BulkGenerateIdResponse.class);
        if (response == null || response.ids() == null || response.ids().isEmpty()) {
            throw new IllegalStateException("idgen bulk returned empty ids");
        }
        return response.ids();
    }
}
