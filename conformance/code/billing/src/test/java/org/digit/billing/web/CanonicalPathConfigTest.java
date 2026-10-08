package org.digit.billing.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.digit.billing.service.BillService;
import org.digit.billing.service.BusinessServiceService;
import org.digit.billing.service.DemandService;
import org.digit.billing.service.PaymentService;
import org.digit.billing.service.TaxHeadService;
import org.digit.tracer.pubsub.PubSubClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * billing.canonical-path moves all 27 canonical routes at once. Worth its own context: the
 * segment is read in three places that must agree — the controller's mappings,
 * HeaderInterceptor's exclude patterns and CanonicalTenantFilter's prefix — and a mismatch
 * shows up only as a 400 on a route that looks correctly mapped.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false", "billing.canonical-path=api"})
@AutoConfigureMockMvc
class CanonicalPathConfigTest {

    private static final String BODY = """
            {"requestMetadata":{"ts":1712830200000,"tenantId":"t1","userInfo":{"userId":"u1"}}}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BusinessServiceService businessServiceService;
    @MockitoBean
    private TaxHeadService taxHeadService;
    @MockitoBean
    private DemandService demandService;
    @MockitoBean
    private BillService billService;
    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private PubSubClient pubSubClient;

    @Test
    void configuredSegmentServesTheCanonicalRoutes() throws Exception {
        when(businessServiceService.search(any(), eq("t1"))).thenReturn(List.of());

        mvc.perform(get("/v3/api/business-services").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseMetadata.status").value("SUCCESSFUL"));
    }

    /** The default segment is not also mapped — the routes moved, they were not duplicated. */
    @Test
    void defaultSegmentNoLongerResolves() throws Exception {
        mvc.perform(get("/v3/canonical/business-services").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("MISSING_HEADER"));
    }
}
