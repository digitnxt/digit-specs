package org.digit.idgen.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.TemplateConfig;
import org.digit.idgen.service.GenerationService;
import org.digit.idgen.service.TemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * idgen.canonical-path moves all three canonical routes at once. Worth its own context:
 * the segment is read in three places that must agree — the controller's mappings,
 * HeaderInterceptor's exclude patterns and CanonicalTenantFilter's prefix — and a mismatch
 * shows up only as a 400 on a route that looks correctly mapped.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false", "idgen.canonical-path=api"})
@AutoConfigureMockMvc
class CanonicalPathConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TemplateService templates;

    @MockitoBean
    private GenerationService generation;

    private static final String BODY = """
            {"requestMetadata":{"ts":1712830200000,"tenantId":"pg","userInfo":{"userId":"u1"}},
             "data":{"templateCode":"receipt-id","config":{"template":"{ORG}-{SEQ}"}}}""";

    @Test
    void configuredSegmentServesTheCanonicalRoutes() throws Exception {
        when(templates.create(eq("pg"), eq("u1"), any(), any())).thenReturn(
                new Dtos.TemplateResponse(UUID.randomUUID(), "receipt-id", "v1",
                        new TemplateConfig("{ORG}-{SEQ}", null, null),
                        new Dtos.AuditDetail("u1", 1000L, "u1", 1000L)));

        mvc.perform(post("/v3/api/template").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.responseMetadata.status").value("SUCCESSFUL"));
    }

    /** The default segment is not also mapped — it is one route, moved, not two. */
    @Test
    void defaultSegmentNoLongerResolves() throws Exception {
        mvc.perform(post("/v3/canonical/template").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("MISSING_HEADER"));
    }
}
