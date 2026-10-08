package org.digit.billing.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.List;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.model.Bill;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Apportion 3.0 wire fidelity: bare JSON arrays both ways on
 * POST /apportion/v3/bills — no capital-"Bills" wrapper, no tenantId in any
 * body (both were v2-isms) — and the tenant/user headers set explicitly.
 */
class ApportionClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ApportionClient client = new ApportionClient(builder.build(),
            new BillingProperties(false, 90, 10, false, null,
                    new BillingProperties.Apportion("http://apportion", "/apportion/v3/bills"), null));

    private static Bill bill() {
        Bill bill = new Bill();
        bill.businessServiceCode = "PT";
        bill.consumerCode = "c-1";
        bill.totalCollectedAmount = new BigDecimal("100");
        return bill;
    }

    @Test
    void postsABareArrayAndReadsABareArray() {
        server.expect(requestTo("http://apportion/apportion/v3/bills"))
                .andExpect(header("X-Tenant-ID", "pg"))
                .andExpect(header("X-User-ID", "user-1"))
                // bare array out: root is [...] and there is no wrapper object
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].businessServiceCode").value("PT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"Bills\""))))
                // bare array back in
                .andRespond(withSuccess("""
                        [{"businessServiceCode":"PT","consumerCode":"c-1",
                          "totalCollectedAmount":"100","totalAmount":"100"}]""",
                        MediaType.APPLICATION_JSON));

        List<Bill> apportioned = client.apportionBills("pg", "user-1", List.of(bill()));

        assertEquals(1, apportioned.size());
        assertEquals(new BigDecimal("100"), apportioned.getFirst().totalAmount);
        server.verify();
    }

    @Test
    void emptyResponseIsAnError() {
        server.expect(requestTo("http://apportion/apportion/v3/bills"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> client.apportionBills("pg", "user-1", List.of(bill())));
    }
}
