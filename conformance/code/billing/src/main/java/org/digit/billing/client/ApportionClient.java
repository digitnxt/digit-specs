package org.digit.billing.client;

import java.util.List;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.model.Bill;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Apportion 3.0 client (POST /apportion/v3/bills). Wire fidelity: bare JSON
 * arrays both ways — no wrapper key, no tenantId in any body (the v2
 * capital-"Bills" envelope died with the v2 service); the payload is this
 * service's own Bill model, which apportion 3.0 adopted verbatim. X-User-ID
 * must be set explicitly (the tracer forwards correlation/tenant only).
 */
@Component
public class ApportionClient {

    private static final ParameterizedTypeReference<List<Bill>> BILLS =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final BillingProperties.Apportion apportion;

    public ApportionClient(@Qualifier("logAwareRestClient") RestClient restClient, BillingProperties properties) {
        this.restClient = restClient;
        this.apportion = properties.apportion();
    }

    public List<Bill> apportionBills(String tenantId, String userId, List<Bill> bills) {
        List<Bill> apportioned = restClient.post()
                .uri(apportion.host() + apportion.billPath())
                .header("X-Tenant-ID", tenantId)
                .header("X-User-ID", userId)
                .body(bills)
                .retrieve()
                .body(BILLS);
        if (apportioned == null || apportioned.isEmpty()) {
            throw new IllegalStateException("apportion returned empty bills");
        }
        return apportioned;
    }
}
