package org.digit.idgen.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.digit.idgen.model.Dtos;
import org.digit.idgen.model.TemplateConfig;
import org.digit.idgen.service.GenerationService;
import org.digit.idgen.service.TemplateService;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer contract: header enforcement, statuses, bare-array error shape.
 * Full context with mocked services; no DB (flyway off, pools are lazy).
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false", "idgen.events.enabled=false"})
@AutoConfigureMockMvc
class WebLayerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TemplateService templateService;

    @MockitoBean
    private GenerationService generationService;

    private static final String BODY = """
            {"templateCode":"receipt-id","config":{"template":"R-{SEQ}"}}""";

    /**
     * The service's own contract: no tenant, no service. WHICH layer answers depends on
     * configuration — tenant-migration's TenantTransactionFilter (servlet order 40) when
     * {@code digit.tenant-migration.enabled=true}, HeaderInterceptor when it is off and the
     * filter is inert — and the two word the message differently and disagree on whether the
     * header lands in {@code params}. Status and code are the part idgen owns and promises
     * its callers, so that is all this pins; the library's own suite covers its message text
     * and X-Error-Source marker.
     */
    @Test
    void missingTenantHeaderIs400MissingHeaderArray() throws Exception {
        mvc.perform(post("/v3/template").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("MISSING_HEADER"));
    }

    @Test
    void missingUserHeaderOnTemplateWriteIs400() throws Exception {
        mvc.perform(post("/v3/template").header("X-Tenant-ID", "pb")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$[0].params[0]").value("X-User-ID"));
    }

    @Test
    void searchNeedsNoUserHeader() throws Exception {
        when(templateService.search(eq("pb"), any(), any(), any(), any(), eq(0)))
                .thenReturn(List.of());
        mvc.perform(get("/v3/template").header("X-Tenant-ID", "pb"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Tenant-ID", "pb"));
    }

    @Test
    void createReturns201AndEchoesHeaders() throws Exception {
        var response = new Dtos.TemplateResponse(UUID.randomUUID(), "receipt-id", "v1",
                new TemplateConfig("R-{SEQ}", null, null).normalized(),
                new Dtos.AuditDetail("u1", 1L, "u1", 1L));
        when(templateService.create(eq("pb"), eq("u1"), any(), any())).thenReturn(response);

        mvc.perform(post("/v3/template")
                        .header("X-Tenant-ID", "pb").header("X-User-ID", "u1")
                        .header("X-Request-ID", "r-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-ID", "r-123"))
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.config.sequence.scope").value("GLOBAL"));
    }

    @Test
    void customExceptionRendersBareArrayWithStatus() throws Exception {
        when(templateService.create(anyString(), anyString(), any(), any()))
                .thenThrow(new CustomException("CONFLICT", "template already exists", HttpStatus.CONFLICT));

        mvc.perform(post("/v3/template")
                        .header("X-Tenant-ID", "pb").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$[0].code").value("CONFLICT"))
                .andExpect(jsonPath("$[0].message").value("template already exists"));
    }

    @Test
    void generateNeedsTenantButNotUser() throws Exception {
        when(generationService.generate(eq("pb"), any()))
                .thenReturn(new Dtos.GenerateResponse("receipt-id", "v1", "R-0001"));
        mvc.perform(post("/v3/generate").header("X-Tenant-ID", "pb")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateCode":"receipt-id"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("R-0001"));
    }

    @Test
    void missingUserHeaderOnPutAndDeleteIs400() throws Exception {
        mvc.perform(put("/v3/template").header("X-Tenant-ID", "pb")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].params[0]").value("X-User-ID"));
        mvc.perform(delete("/v3/template").header("X-Tenant-ID", "pb")
                        .param("templateCode", "receipt-id").param("version", "v1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].params[0]").value("X-User-ID"));
    }

    @Test
    void deleteReturnsDeletedTrue() throws Exception {
        mvc.perform(delete("/v3/template")
                        .header("X-Tenant-ID", "pb").header("X-User-ID", "u1")
                        .param("templateCode", "receipt-id").param("version", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void invalidUuidInIdsIs400InvalidParam() throws Exception {
        mvc.perform(get("/v3/template").header("X-Tenant-ID", "pb").param("ids", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("INVALID_PARAM"));
    }

    @Test
    void queryParamViolationsUseContractCode() throws Exception {
        mvc.perform(get("/v3/template").header("X-Tenant-ID", "pb").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("VALIDATION_ERROR"));
        mvc.perform(get("/v3/template").header("X-Tenant-ID", "pb").param("templateCode", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("VALIDATION_ERROR"));
    }
}
