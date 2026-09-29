package org.digit.idgen.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Canonical routes: metadata read from requestMetadata instead of X-* headers, query
 * parameters kept as query parameters, and the {responseMetadata, …} envelope on success
 * and error alike. Full context with the services mocked.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class CanonicalWebLayerTest {

    private static final String TEMPLATE = "/v3/canonical/template";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TemplateService templates;

    @MockitoBean
    private GenerationService generation;

    private static final String META = """
            "requestMetadata":{"ts":1712830200000,"msgId":"m-1","requestId":"r-1",
             "correlationId":"c-1","tenantId":"pg","userInfo":{"userId":"u1"}}""";
    private static final String META_NO_USER = """
            "requestMetadata":{"ts":1712830200000,"tenantId":"pg"}""";
    private static final String CONFIG = """
            {"template":"{ORG}-{SEQ}","sequence":{"scope":"GLOBAL","start":1}}""";

    private static Dtos.TemplateResponse template() {
        return new Dtos.TemplateResponse(UUID.randomUUID(), "receipt-id", "v1",
                new TemplateConfig("{ORG}-{SEQ}", null, null),
                new Dtos.AuditDetail("u1", 1000L, "u1", 1000L));
    }

    @Test
    void createTakesTenantAndUserFromBody() throws Exception {
        when(templates.create(eq("pg"), eq("u1"), eq("r-1"), any())).thenReturn(template());

        mvc.perform(post(TEMPLATE).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META + ",\"data\":{\"templateCode\":\"receipt-id\",\"config\":" + CONFIG + "}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.responseMetadata.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.responseMetadata.msgId").value("m-1"))
                .andExpect(jsonPath("$.responseMetadata.requestId").value("r-1"))
                .andExpect(jsonPath("$.data.templateCode").value("receipt-id"))
                .andExpect(jsonPath("$.data.version").value("v1"));
        verify(templates).create(eq("pg"), eq("u1"), eq("r-1"), any());
    }

    @Test
    void updateUsesBodyMetadata() throws Exception {
        when(templates.update(eq("pg"), eq("u1"), any(), any())).thenReturn(template());
        mvc.perform(put(TEMPLATE).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META + ",\"data\":{\"templateCode\":\"receipt-id\",\"config\":" + CONFIG + "}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("v1"));
    }

    /** The tenant is promoted to X-Tenant-ID, so tenant-migration's filter lets it through. */
    @Test
    void tenantIsPromotedToHeaderForDownstreamFilters() throws Exception {
        when(templates.search(eq("pg"), isNull(), isNull(), any(), isNull(), eq(0)))
                .thenReturn(List.of(template()));
        mvc.perform(get(TEMPLATE).contentType(MediaType.APPLICATION_JSON).content("{" + META_NO_USER + "}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Tenant-ID", "pg"))
                .andExpect(jsonPath("$.data[0].templateCode").value("receipt-id"));
    }

    /** Search criteria stay query parameters; the body carries metadata only. */
    @Test
    void searchPassesQueryParametersToTheService() throws Exception {
        UUID id = UUID.randomUUID();
        when(templates.search(eq("pg"), eq("receipt-id"), eq("v2"), eq(List.of(id)), eq(50), eq(10)))
                .thenReturn(List.of(template()));

        mvc.perform(get(TEMPLATE + "?templateCode=receipt-id&version=v2&ids=" + id + "&limit=50&offset=10")
                        .contentType(MediaType.APPLICATION_JSON).content("{" + META_NO_USER + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        verify(templates).search(eq("pg"), eq("receipt-id"), eq("v2"), eq(List.of(id)), eq(50), eq(10));
    }

    /** The 3.0 route's own parameter validation is reused, not re-implemented. */
    @Test
    void searchRejectsBadLimitAndBadIds() throws Exception {
        mvc.perform(get(TEMPLATE + "?limit=500").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META_NO_USER + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMetadata.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));

        mvc.perform(get(TEMPLATE + "?ids=not-a-uuid").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META_NO_USER + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PARAM"));
    }

    /** Delete requires the user id, mirroring the 3.0 route's X-User-ID demand. */
    @Test
    void deleteRequiresUserIdAndUsesQueryParameters() throws Exception {
        mvc.perform(delete(TEMPLATE + "?templateCode=receipt-id&version=v1")
                        .contentType(MediaType.APPLICATION_JSON).content("{" + META + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        verify(templates).delete("pg", "receipt-id", "v1");

        mvc.perform(delete(TEMPLATE + "?templateCode=receipt-id&version=v1")
                        .contentType(MediaType.APPLICATION_JSON).content("{" + META_NO_USER + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].params[0]").value("requestMetadata.userInfo.userId"));
    }

    @Test
    void generateWrapsPayloadUnderIdGeneration() throws Exception {
        when(generation.generate(eq("pg"), any()))
                .thenReturn(new Dtos.GenerateResponse("receipt-id", "v3", "pb-2025-000123-A9"));

        mvc.perform(post("/v3/canonical/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META_NO_USER + ",\"data\":{\"templateCode\":\"receipt-id\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("pb-2025-000123-A9"))
                .andExpect(jsonPath("$.data.version").value("v3"))
                .andExpect(jsonPath("$.responseMetadata.status").value("SUCCESSFUL"));
    }

    @Test
    void bulkGenerateWrapsPayloadUnderBulkIdGeneration() throws Exception {
        when(generation.bulkGenerate(eq("pg"), any()))
                .thenReturn(new Dtos.BulkGenerateResponse("receipt-id", "v1", 2, List.of("A", "B")));

        mvc.perform(post("/v3/canonical/generate/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META_NO_USER + ",\"data\":{\"templateCode\":\"receipt-id\",\"count\":2}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.ids.length()").value(2));
    }

    /** Generation writes no audit row, so it must not demand a user id. */
    @Test
    void generateNeedsNoUserId() throws Exception {
        when(generation.generate(eq("pg"), any()))
                .thenReturn(new Dtos.GenerateResponse("receipt-id", "v1", "X"));
        mvc.perform(post("/v3/canonical/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META_NO_USER + ",\"data\":{\"templateCode\":\"receipt-id\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void missingUserIdOnCreateIs400CanonicalError() throws Exception {
        mvc.perform(post(TEMPLATE).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META_NO_USER + ",\"data\":{\"templateCode\":\"receipt-id\",\"config\":" + CONFIG + "}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMetadata.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.errors[0].params[0]").value("requestMetadata.userInfo.userId"));
        verify(templates, never()).create(anyString(), anyString(), any(), any());
    }

    /** Rejected in the filter, ahead of the DispatcherServlet — still the canonical envelope. */
    @Test
    void missingTenantIdIs400CanonicalError() throws Exception {
        mvc.perform(post(TEMPLATE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestMetadata\":{\"ts\":1712830200000},\"data\":{\"templateCode\":\"x\",\"config\":" + CONFIG + "}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.errors[0].params[0]").value("requestMetadata.tenantId"));
    }

    @Test
    void serviceErrorIsWrappedWithResponseMetadata() throws Exception {
        when(templates.create(anyString(), anyString(), any(), any()))
                .thenThrow(new CustomException("CONFLICT", "template already exists", HttpStatus.CONFLICT));

        mvc.perform(post(TEMPLATE).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META + ",\"data\":{\"templateCode\":\"receipt-id\",\"config\":" + CONFIG + "}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.responseMetadata.requestId").value("r-1"))
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"));
    }

    @Test
    void invalidPayloadIs400CanonicalError() throws Exception {
        mvc.perform(post("/v3/canonical/generate/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + META_NO_USER + ",\"data\":{\"templateCode\":\"receipt-id\",\"count\":0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMetadata.status").value("FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    /** The 3.0 routes are untouched: still header-driven, still bare-array errors. */
    @Test
    void headerRoutesStillReturnBareArray() throws Exception {
        mvc.perform(post("/v3/template").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateCode\":\"x\",\"config\":" + CONFIG + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("MISSING_HEADER"));
    }
}
