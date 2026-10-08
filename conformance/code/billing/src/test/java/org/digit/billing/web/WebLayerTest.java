package org.digit.billing.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.digit.billing.model.AuditDetail;
import org.digit.billing.model.Bill;
import org.digit.billing.model.BillRequests;
import org.digit.billing.model.BillStatus;
import org.digit.billing.model.BulkBillStatus;
import org.digit.billing.model.BusinessService;
import org.digit.billing.model.CollectionMode;
import org.digit.billing.model.Demand;
import org.digit.billing.model.DemandRequests;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.InstrumentStatus;
import org.digit.billing.model.Payment;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.model.PaymentStatus;
import org.digit.billing.model.ReceiptType;
import org.digit.billing.model.TaxHead;
import org.digit.billing.model.TaxHeadCategory;
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
 * Web-layer contract: header enforcement/echo, statuses, bare-array error
 * shape, decimal-as-string serialization (HR-1). Full context with mocked
 * services and pubsub; no DB (flyway off, pools lazy).
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class WebLayerTest {

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

    private static final String BS_BODY = """
            [{"code":"PT","name":"Property Tax","allowedPaymentModes":["CASH"],"billExpiryDays":30,
              "currency":"INR","effectiveFrom":1735669800000,"isActive":true}]""";

    private static BusinessService businessService() {
        return new BusinessService(UUID.randomUUID(), "PT", 1, "Property Tax", CollectionMode.BOTH,
                List.of(PaymentMode.CASH), 30, false, new BigDecimal("10.50"), "INR", null,
                1735669800000L, null, true, new AuditDetail("u1", 1, "u1", 1));
    }

    // ── cross-cutting ─────────────────────────────────────────────────────────

    /**
     * The service's own contract: no tenant, no service. WHICH layer answers depends on
     * configuration — tenant-migration's TenantTransactionFilter (servlet order 40) when
     * {@code digit.tenant-migration.enabled=true}, HeaderInterceptor when it is off and the
     * filter is inert — and the two word the message differently and disagree on whether the
     * header lands in {@code params}. Status and code are the part billing owns and promises
     * its callers, so that is all this pins; the library's own suite covers its message text
     * and X-Error-Source marker.
     */
    @Test
    void missingTenantHeaderIs400MissingHeaderArray() throws Exception {
        mvc.perform(get("/v3/business-services"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("MISSING_HEADER"));
    }

    @Test
    void missingUserHeaderOnWriteIs400() throws Exception {
        mvc.perform(post("/v3/business-services").header("X-Tenant-ID", "t1")
                        .contentType(MediaType.APPLICATION_JSON).content(BS_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$[0].params[0]").value("X-User-ID"));
    }

    @Test
    void tenantAndRequestIdEchoed() throws Exception {
        when(businessServiceService.search(any(), eq("t1"))).thenReturn(List.of());
        mvc.perform(get("/v3/business-services")
                        .header("X-Tenant-ID", "t1").header("X-Request-ID", "req-9"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Tenant-ID", "t1"))
                .andExpect(header().string("X-Request-ID", "req-9"));
    }

    // ── business services ─────────────────────────────────────────────────────

    @Test
    void createBusinessServices201WithDecimalAsString() throws Exception {
        when(businessServiceService.create(any(), eq("t1"), eq("u1"))).thenReturn(List.of(businessService()));
        mvc.perform(post("/v3/business-services").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(BS_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].code").value("PT"))
                // HR-1: quoted decimal string, scale preserved
                .andExpect(jsonPath("$[0].minPayableAmount").value("10.50"));
    }

    @Test
    void createEmptyArrayIs400InvalidRequest() throws Exception {
        mvc.perform(post("/v3/business-services").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("INVALID_REQUEST"));
    }

    @Test
    void createInvalidCodePatternIs400() throws Exception {
        String bad = BS_BODY.replace("\"PT\"", "\"pt lower\"");
        mvc.perform(post("/v3/business-services").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNullAllowedPaymentModeElementIs400() throws Exception {
        String bad = BS_BODY.replace("[\"CASH\"]", "[null]");
        mvc.perform(post("/v3/business-services").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByCode404WhenAbsent() throws Exception {
        when(businessServiceService.getByCode(eq("PT"), eq("t1"))).thenReturn(Optional.empty());
        mvc.perform(get("/v3/business-services/PT").header("X-Tenant-ID", "t1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$[0].code").value("NOT_FOUND"));
    }

    @Test
    void deleteReturnsDeletedTrue() throws Exception {
        mvc.perform(delete("/v3/business-services/PT").header("X-Tenant-ID", "t1").header("X-User-ID", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void putAndPatchReturn200() throws Exception {
        when(businessServiceService.update(eq("PT"), eq("t1"), eq("u1"), any())).thenReturn(businessService());
        when(businessServiceService.patch(eq("PT"), eq("t1"), eq("u1"), any())).thenReturn(businessService());
        String updateBody = BS_BODY.substring(1, BS_BODY.length() - 1); // single object
        mvc.perform(put("/v3/business-services/PT").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());
        mvc.perform(patch("/v3/business-services/PT").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Name\"}"))
                .andExpect(status().isOk());
    }

    // ── tax heads ─────────────────────────────────────────────────────────────

    @Test
    void createTaxHeads201AndOrderFieldName() throws Exception {
        TaxHead taxHead = new TaxHead(UUID.randomUUID(), "PT_TAX", 1, "Base", "PT",
                TaxHeadCategory.TAX, 1, 1L, null, true, new AuditDetail("u1", 1, "u1", 1));
        when(taxHeadService.create(any(), eq("t1"), eq("u1"))).thenReturn(List.of(taxHead));
        mvc.perform(post("/v3/tax-heads").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"code":"PT_TAX","name":"Base","businessServiceCode":"PT",
                                  "order":1,"effectiveFrom":1,"isActive":true}]"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].order").value(1));
    }

    @Test
    void searchTaxHeadsNeedsOnlyTenant() throws Exception {
        when(taxHeadService.search(any(), eq("t1"))).thenReturn(List.of());
        mvc.perform(get("/v3/tax-heads").header("X-Tenant-ID", "t1"))
                .andExpect(status().isOk());
    }

    // ── demands ───────────────────────────────────────────────────────────────

    private static Demand demand() {
        return new Demand(UUID.randomUUID(), "PT", 1, 2, "C-1", null, List.of(), List.of(),
                List.of(), DemandStatus.ACTIVE, new BigDecimal("100"), BigDecimal.ZERO, false,
                Map.of(), 1, new AuditDetail("u1", 1, "u1", 1));
    }

    @Test
    void createDemandsAllSuccess201PlainArray() throws Exception {
        when(demandService.create(any(), eq("t1"), eq("u1")))
                .thenReturn(new DemandRequests.BulkResponse(List.of(demand()), List.of()));
        mvc.perform(post("/v3/demands").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"businessServiceCode":"PT","periodFrom":1,"periodTo":2,"consumerCode":"C-1",
                                  "lineItems":[{"taxHeadCode":"PT_TAX","amount":"100"}]}]"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].totalAmount").value("100"))
                .andExpect(jsonPath("$[0].isDemandPaid").value(false));
    }

    @Test
    void mixedDemandBatchIs207Envelope() throws Exception {
        when(demandService.create(any(), eq("t1"), eq("u1")))
                .thenReturn(new DemandRequests.BulkResponse(List.of(demand()),
                        List.of(new DemandRequests.BulkFailure(1, List.of(
                                new org.digit.tracer.model.Error("DEMAND_CONFLICT", "m", "d", null))))));
        mvc.perform(post("/v3/demands").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"businessServiceCode":"PT","periodFrom":1,"periodTo":2,"consumerCode":"C-1",
                                  "lineItems":[{"taxHeadCode":"PT_TAX","amount":"100"}]}]"""))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.success[0].businessServiceCode").value("PT"))
                .andExpect(jsonPath("$.failures[0].index").value(1));
    }

    @Test
    void createDemandNullPayerElementIs400() throws Exception {
        // Stub a normal all-success response: if the null element ever slipped past
        // validation again, this test would observe the real 201 the controller
        // builds from it, not an incidental NPE from an unstubbed mock.
        when(demandService.create(any(), eq("t1"), eq("u1")))
                .thenReturn(new DemandRequests.BulkResponse(List.of(demand()), List.of()));
        mvc.perform(post("/v3/demands").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"businessServiceCode":"PT","periodFrom":1,"periodTo":2,"consumerCode":"C-1",
                                  "payer":[null],
                                  "lineItems":[{"taxHeadCode":"PT_TAX","amount":"100"}]}]"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDemandNullLineItemElementIs400() throws Exception {
        when(demandService.create(any(), eq("t1"), eq("u1")))
                .thenReturn(new DemandRequests.BulkResponse(List.of(demand()), List.of()));
        mvc.perform(post("/v3/demands").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                [{"businessServiceCode":"PT","periodFrom":1,"periodTo":2,"consumerCode":"C-1",
                                  "lineItems":[null]}]"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void demandByIdBadUuidIs400InvalidPathParam() throws Exception {
        mvc.perform(get("/v3/demands/not-a-uuid").header("X-Tenant-ID", "t1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("INVALID_PATH_PARAM"));
    }

    @Test
    void freezeAndCancelWork() throws Exception {
        UUID id = UUID.randomUUID();
        when(demandService.freeze(eq(id), eq("t1"), eq("u1"))).thenReturn(demand());
        when(demandService.cancel(eq(id), eq("t1"), eq("u1"), any())).thenReturn(demand());
        mvc.perform(post("/v3/demands/" + id + "/freeze")
                        .header("X-Tenant-ID", "t1").header("X-User-ID", "u1"))
                .andExpect(status().isOk());
        // cancel body is optional (Go binds only when Content-Length > 0)
        mvc.perform(post("/v3/demands/" + id + "/cancel")
                        .header("X-Tenant-ID", "t1").header("X-User-ID", "u1"))
                .andExpect(status().isOk());
    }

    // ── bills ─────────────────────────────────────────────────────────────────

    private static Bill bill() {
        Bill bill = new Bill();
        bill.id = UUID.randomUUID();
        bill.businessServiceCode = "PT";
        bill.consumerCode = "C-1";
        bill.billNumber = "BILL-1";
        bill.billIssueAt = 1;
        bill.status = BillStatus.ACTIVE;
        bill.totalAmount = new BigDecimal("100.00");
        bill.totalCollectedAmount = BigDecimal.ZERO;
        bill.metadata = Map.of();
        bill.auditDetail = new AuditDetail("u1", 1, "u1", 1);
        return bill;
    }

    @Test
    void generateBill201() throws Exception {
        when(billService.generate(any(), eq("t1"), eq("u1"))).thenReturn(bill());
        mvc.perform(post("/v3/bills/generate").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessServiceCode\":\"PT\",\"consumerCode\":\"C-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value("100.00"));
    }

    @Test
    void searchBillsParsesCsvParams() throws Exception {
        org.mockito.ArgumentCaptor<BillRequests.Filters> captor =
                org.mockito.ArgumentCaptor.forClass(BillRequests.Filters.class);
        when(billService.search(any(), eq("t1"))).thenReturn(List.of());
        UUID goodId = UUID.randomUUID();
        mvc.perform(get("/v3/bills").header("X-Tenant-ID", "t1")
                        .param("consumerCodes", "C-1, C-2 ,")
                        .param("billIds", goodId + ",not-a-uuid"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(billService).search(captor.capture(), eq("t1"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("C-1", "C-2"), captor.getValue().consumerCodes());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(goodId), captor.getValue().billIds());
    }

    @Test
    void cancelBill200AndBulkGenerate202() throws Exception {
        when(billService.cancel(any(), eq("t1"), eq("u1"))).thenReturn(bill());
        mvc.perform(post("/v3/bills/cancel").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"businessServiceCode":"PT","consumerCode":"C-1",
                                 "statusToBeUpdated":"CANCELLED","metadata":{}}"""))
                .andExpect(status().isOk());

        when(billService.bulkGenerate(any(), eq("t1"), eq("u1")))
                .thenReturn(new BillRequests.BulkBillResponse("rid", "PT", BulkBillStatus.ACCEPTED,
                        1, 10, Map.of("maxConsumerCode", "Z")));
        mvc.perform(post("/v3/bills/bulk-generate").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessServiceCode\":\"PT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    // ── payments ──────────────────────────────────────────────────────────────

    private static Payment payment() {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.totalAmountDue = new BigDecimal("100.00");
        payment.totalAmountPaid = new BigDecimal("100");
        payment.paymentMode = PaymentMode.CASH;
        payment.paymentStatus = PaymentStatus.NEW;
        payment.instrumentStatus = InstrumentStatus.APPROVED;
        payment.paidBy = "citizen";
        payment.metadata = Map.of();
        payment.auditDetail = new AuditDetail("u1", 1, "u1", 1);
        Payment.PaymentDetail detail = new Payment.PaymentDetail();
        detail.receiptNumber = "RCPT-1";
        detail.receiptType = ReceiptType.BILLBASED;
        detail.totalAmountDue = new BigDecimal("100.00");
        detail.totalAmountPaid = new BigDecimal("100");
        detail.billId = UUID.randomUUID();
        payment.paymentDetails.add(detail);
        return payment;
    }

    private static final String PAYMENT_BODY = """
            {"totalAmountPaid":"100","paymentMode":"CASH","paidBy":"citizen",
             "paymentDetails":[{"totalAmountPaid":"100","billId":"%s"}]}"""
            .formatted(UUID.randomUUID());

    @Test
    void createPayment201AndValidate200() throws Exception {
        when(paymentService.create(any(), eq("t1"), eq("u1"))).thenReturn(payment());
        mvc.perform(post("/v3/payments").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentStatus").value("NEW"))
                .andExpect(jsonPath("$.totalAmountPaid").value("100"));

        when(paymentService.validate(any(), eq("t1"), eq("u1"))).thenReturn(payment());
        mvc.perform(post("/v3/payments/validate").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(PAYMENT_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void createPaymentNullDetailElementIs400() throws Exception {
        String bad = """
                {"totalAmountPaid":"100","paymentMode":"CASH","paidBy":"citizen",
                 "paymentDetails":[null]}""";
        mvc.perform(post("/v3/payments").header("X-Tenant-ID", "t1").header("X-User-ID", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchPaymentsRejectsInvalidEnumCsv() throws Exception {
        mvc.perform(get("/v3/payments").header("X-Tenant-ID", "t1")
                        .param("paymentModes", "CASH,BADMODE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("INVALID_REQUEST"));
    }

    @Test
    void getPaymentByIdBadUuid400() throws Exception {
        mvc.perform(get("/v3/payments/xyz").header("X-Tenant-ID", "t1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("INVALID_PATH_PARAM"));
    }
}
