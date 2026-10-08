package org.digit.billing.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.digit.tracer.model.CustomException;
import org.digit.tracer.model.Error;
import org.digit.tracer.pubsub.PubSubClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The 27 canonical routes of billing-canonical.yml: the envelope in and out, the metadata
 * that replaces the X-* headers, and the error shape — {responseMetadata, errors}
 * everywhere, including from the order-0 filter and from the bulk routes' all-failed
 * answer, which the 3.0 side RETURNS rather than throws.
 *
 * <p>Same setup as {@link WebLayerTest}: full context, mocked services, no DB.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class CanonicalWebLayerTest {

    private static final String META = """
            "requestMetadata":{"ts":1712830200000,"msgId":"1712830200000|en_IN",
              "requestId":"req-1","correlationId":"corr-1","tenantId":"t1",
              "userInfo":{"userId":"u1"}}""";

    private static final String METADATA_ONLY = "{%s}".formatted(META);

    /** Metadata with no userInfo — legal on GETs, rejected on every write. */
    private static final String NO_USER = """
            {"requestMetadata":{"ts":1712830200000,"tenantId":"t1"}}""";

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

    private static BusinessService businessService() {
        return new BusinessService(UUID.randomUUID(), "PT", 1, "Property Tax", CollectionMode.BOTH,
                List.of(PaymentMode.CASH), 30, false, new BigDecimal("10.50"), "INR", null,
                1735669800000L, null, true, new AuditDetail("u1", 1, "u1", 1));
    }

    private static TaxHead taxHead() {
        return new TaxHead(UUID.randomUUID(), "PT_TAX", 1, "Tax", "PT", TaxHeadCategory.TAX,
                1, 1735669800000L, null, true, new AuditDetail("u1", 1, "u1", 1));
    }

    private static Demand demand() {
        return new Demand(UUID.randomUUID(), "PT", 1, 2, "C-1", null, List.of(), List.of(),
                List.of(), DemandStatus.ACTIVE, new BigDecimal("100"), BigDecimal.ZERO, false,
                Map.of(), 1, new AuditDetail("u1", 1, "u1", 1));
    }

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

    // ── business services ─────────────────────────────────────────────────────

    @Test
    void createBusinessServicesWrapsTheArrayAndEchoesTheMetadata() throws Exception {
        when(businessServiceService.create(any(), eq("t1"), eq("u1"))).thenReturn(List.of(businessService()));

        mvc.perform(post("/v3/canonical/business-services").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":[{"code":"PT","name":"Property Tax",
                                  "allowedPaymentModes":["CASH"],"billExpiryDays":30,"currency":"INR",
                                  "effectiveFrom":1735669800000,"isActive":true}]}""".formatted(META)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.responseMetadata.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.responseMetadata.msgId").value("1712830200000|en_IN"))
                .andExpect(jsonPath("$.responseMetadata.requestId").value("req-1"))
                .andExpect(jsonPath("$.responseMetadata.correlationId").value("corr-1"))
                .andExpect(jsonPath("$.responseMetadata.responseTime").isNumber())
                .andExpect(jsonPath("$.data[0].code").value("PT"));
    }

    @Test
    void searchBusinessServicesKeepsQueryParameters() throws Exception {
        when(businessServiceService.search(any(), eq("t1"))).thenReturn(List.of(businessService()));

        mvc.perform(get("/v3/canonical/business-services").contentType(MediaType.APPLICATION_JSON)
                        .param("code", "PT").param("isActive", "true").param("limit", "50")
                        .content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("PT"));

        verify(businessServiceService).search(
                eq(new org.digit.billing.model.BusinessServiceRequests.Filters("PT", true, null, 50, 0)),
                eq("t1"));
    }

    @Test
    void getBusinessServiceWrapsAnd404sInTheEnvelope() throws Exception {
        when(businessServiceService.getByCode(eq("PT"), eq("t1"))).thenReturn(Optional.of(businessService()));
        mvc.perform(get("/v3/canonical/business-services/PT").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("PT"));

        when(businessServiceService.getByCode(eq("GONE"), eq("t1"))).thenReturn(Optional.empty());
        mvc.perform(get("/v3/canonical/business-services/GONE").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.responseMetadata.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void updateAndPatchAndDeleteBusinessService() throws Exception {
        when(businessServiceService.update(eq("PT"), eq("t1"), eq("u1"), any())).thenReturn(businessService());
        mvc.perform(put("/v3/canonical/business-services/PT").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":{"name":"Renamed","allowedPaymentModes":["CASH"],
                                  "billExpiryDays":30,"currency":"INR","effectiveFrom":1735669800000,
                                  "isActive":true}}""".formatted(META)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("PT"));

        when(businessServiceService.patch(eq("PT"), eq("t1"), eq("u1"), any())).thenReturn(businessService());
        mvc.perform(patch("/v3/canonical/business-services/PT").contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":{\"isActive\":false}}".formatted(META)))
                .andExpect(status().isOk());

        mvc.perform(delete("/v3/canonical/business-services/PT").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        verify(businessServiceService).delete("PT", "t1", "u1");
    }

    // ── tax heads ─────────────────────────────────────────────────────────────

    @Test
    void taxHeadCreateSearchGetUpdatePatchDelete() throws Exception {
        when(taxHeadService.create(any(), eq("t1"), eq("u1"))).thenReturn(List.of(taxHead()));
        mvc.perform(post("/v3/canonical/tax-heads").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":[{"code":"PT_TAX","name":"Tax","businessServiceCode":"PT",
                                  "category":"TAX","order":1,"effectiveFrom":1,"isActive":true}]}""".formatted(META)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].code").value("PT_TAX"));

        when(taxHeadService.search(any(), eq("t1"))).thenReturn(List.of(taxHead()));
        mvc.perform(get("/v3/canonical/tax-heads").contentType(MediaType.APPLICATION_JSON)
                        .param("category", "TAX").content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("TAX"));

        when(taxHeadService.getByCode(eq("PT_TAX"), eq("t1"))).thenReturn(Optional.of(taxHead()));
        mvc.perform(get("/v3/canonical/tax-heads/PT_TAX").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk());

        when(taxHeadService.update(eq("PT_TAX"), eq("t1"), eq("u1"), any())).thenReturn(taxHead());
        mvc.perform(put("/v3/canonical/tax-heads/PT_TAX").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":{"name":"Tax","businessServiceCode":"PT","category":"TAX",
                                  "order":1,"effectiveFrom":1,"isActive":true}}""".formatted(META)))
                .andExpect(status().isOk());

        when(taxHeadService.patch(eq("PT_TAX"), eq("t1"), eq("u1"), any())).thenReturn(taxHead());
        mvc.perform(patch("/v3/canonical/tax-heads/PT_TAX").contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":{\"isActive\":false}}".formatted(META)))
                .andExpect(status().isOk());

        mvc.perform(delete("/v3/canonical/tax-heads/PT_TAX").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    // ── demands (the bulk shapes) ─────────────────────────────────────────────

    private static final String DEMAND_BODY = """
            {%s,"data":[{"businessServiceCode":"PT","periodFrom":1,"periodTo":2,
              "consumerCode":"C-1",
              "lineItems":[{"taxHeadCode":"PT_TAX","amount":"100"}]}]}""";

    @Test
    void createDemandsAllSuccessWrapsThePlainArrayAt201() throws Exception {
        when(demandService.create(any(), eq("t1"), eq("u1")))
                .thenReturn(new DemandRequests.BulkResponse(List.of(demand()), List.of()));

        mvc.perform(post("/v3/canonical/demands").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMAND_BODY.formatted(META)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.responseMetadata.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.data[0].consumerCode").value("C-1"));
    }

    @Test
    void mixedBulkIs207WithTheBulkResponseUnderData() throws Exception {
        when(demandService.create(any(), eq("t1"), eq("u1"))).thenReturn(new DemandRequests.BulkResponse(
                List.of(demand()),
                List.of(new DemandRequests.BulkFailure(1, List.of(new Error("INVALID_PERIOD", "bad", "d", null))))));

        mvc.perform(post("/v3/canonical/demands").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMAND_BODY.formatted(META)))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.responseMetadata.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.data.success[0].consumerCode").value("C-1"))
                .andExpect(jsonPath("$.data.failures[0].index").value(1));
    }

    /**
     * BulkResponses RETURNS this rather than throwing it, so no advice sees it — the one
     * place a canonical route could have leaked a bare array.
     */
    @Test
    void allFailedBulkIsEnvelopedNotABareArray() throws Exception {
        when(demandService.create(any(), eq("t1"), eq("u1"))).thenReturn(new DemandRequests.BulkResponse(
                List.of(),
                List.of(new DemandRequests.BulkFailure(0, List.of(new Error("INVALID_PERIOD", "bad", "d", null))))));

        mvc.perform(post("/v3/canonical/demands").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMAND_BODY.formatted(META)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMetadata.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PERIOD"))
                .andExpect(jsonPath("$.errors[0].description").value("item 0: d"))
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void allReferentialFailuresStayA422InTheEnvelope() throws Exception {
        when(demandService.create(any(), eq("t1"), eq("u1"))).thenReturn(new DemandRequests.BulkResponse(
                List.of(),
                List.of(new DemandRequests.BulkFailure(0,
                        List.of(new Error("UNKNOWN_TAX_HEAD", "no", "d", null))))));

        mvc.perform(post("/v3/canonical/demands").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMAND_BODY.formatted(META)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_TAX_HEAD"));
    }

    @Test
    void updateDemandsWrapsAt200() throws Exception {
        when(demandService.update(any(), eq("t1"), eq("u1")))
                .thenReturn(new DemandRequests.BulkResponse(List.of(demand()), List.of()));

        mvc.perform(put("/v3/canonical/demands").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":[{"id":"%s","businessServiceCode":"PT",
                                  "periodFrom":1,"periodTo":2,"consumerCode":"C-1",
                                  "lineItems":[{"taxHeadCode":"PT_TAX","amount":"100"}]}]}"""
                                .formatted(META, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].consumerCode").value("C-1"));
    }

    @Test
    void demandSearchGetPatchFreezeCancel() throws Exception {
        when(demandService.search(any(), eq("t1"))).thenReturn(List.of(demand()));
        mvc.perform(get("/v3/canonical/demands").contentType(MediaType.APPLICATION_JSON)
                        .param("consumerCode", "C-1").content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].consumerCode").value("C-1"));

        UUID id = UUID.randomUUID();
        when(demandService.getById(eq(id), eq("t1"))).thenReturn(demand());
        mvc.perform(get("/v3/canonical/demands/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        when(demandService.patch(eq(id), eq("t1"), eq("u1"), any())).thenReturn(demand());
        mvc.perform(patch("/v3/canonical/demands/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":{\"version\":1,\"metadata\":{}}}".formatted(META)))
                .andExpect(status().isOk());

        when(demandService.freeze(eq(id), eq("t1"), eq("u1"))).thenReturn(demand());
        mvc.perform(post("/v3/canonical/demands/" + id + "/freeze").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk());

        when(demandService.cancel(eq(id), eq("t1"), eq("u1"), any())).thenReturn(demand());
        mvc.perform(post("/v3/canonical/demands/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":{\"reasonCode\":\"DUPLICATE\"}}".formatted(META)))
                .andExpect(status().isOk());
    }

    /** Cancel's payload is optional on the 3.0 route; the envelope keeps it optional. */
    @Test
    void cancelDemandWithoutDataIsAccepted() throws Exception {
        UUID id = UUID.randomUUID();
        when(demandService.cancel(eq(id), eq("t1"), eq("u1"), eq(null))).thenReturn(demand());

        mvc.perform(post("/v3/canonical/demands/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk());
    }

    @Test
    void malformedDemandIdIs400InTheEnvelope() throws Exception {
        mvc.perform(get("/v3/canonical/demands/xyz").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PATH_PARAM"));
    }

    // ── bills ─────────────────────────────────────────────────────────────────

    @Test
    void generateSearchCancelAndBulkGenerateBills() throws Exception {
        when(billService.generate(any(), eq("t1"), eq("u1"))).thenReturn(bill());
        mvc.perform(post("/v3/canonical/bills/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":{"businessServiceCode":"PT","consumerCode":"C-1"}}""".formatted(META)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalAmount").value("100.00"));

        when(billService.search(any(), eq("t1"))).thenReturn(List.of(bill()));
        UUID goodId = UUID.randomUUID();
        mvc.perform(get("/v3/canonical/bills").contentType(MediaType.APPLICATION_JSON)
                        .param("consumerCodes", "C-1, C-2 ,")
                        .param("billIds", goodId + ",not-a-uuid")
                        .content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].billNumber").value("BILL-1"));

        when(billService.cancel(any(), eq("t1"), eq("u1"))).thenReturn(bill());
        mvc.perform(post("/v3/canonical/bills/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":{"businessServiceCode":"PT","consumerCode":"C-1",
                                  "statusToBeUpdated":"CANCELLED","metadata":{}}}""".formatted(META)))
                .andExpect(status().isOk());

        when(billService.bulkGenerate(any(), eq("t1"), eq("u1")))
                .thenReturn(new BillRequests.BulkBillResponse("rid", "PT", BulkBillStatus.ACCEPTED,
                        1, 10, Map.of("maxConsumerCode", "Z")));
        mvc.perform(post("/v3/canonical/bills/bulk-generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":{\"businessServiceCode\":\"PT\"}}".formatted(META)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    /** Bill search parses its CSV parameters with the 3.0 route's own helpers. */
    @Test
    void billSearchCsvParsingIsTheSameAsThe30Route() throws Exception {
        org.mockito.ArgumentCaptor<BillRequests.Filters> captor =
                org.mockito.ArgumentCaptor.forClass(BillRequests.Filters.class);
        when(billService.search(any(), eq("t1"))).thenReturn(List.of());
        UUID goodId = UUID.randomUUID();

        mvc.perform(get("/v3/canonical/bills").contentType(MediaType.APPLICATION_JSON)
                        .param("consumerCodes", "C-1, C-2 ,")
                        .param("billIds", goodId + ",not-a-uuid")
                        .content(METADATA_ONLY))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(billService).search(captor.capture(), eq("t1"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("C-1", "C-2"), captor.getValue().consumerCodes());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(goodId), captor.getValue().billIds());
    }

    // ── payments ──────────────────────────────────────────────────────────────

    private static final String PAYMENT_DATA = """
            {"totalAmountPaid":"100","paymentMode":"CASH","paidBy":"citizen",
             "paymentDetails":[{"totalAmountPaid":"100","billId":"%s"}]}"""
            .formatted(UUID.randomUUID());

    @Test
    void createValidateSearchAndGetPayment() throws Exception {
        when(paymentService.create(any(), eq("t1"), eq("u1"))).thenReturn(payment());
        mvc.perform(post("/v3/canonical/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":%s}".formatted(META, PAYMENT_DATA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentStatus").value("NEW"))
                .andExpect(jsonPath("$.data.totalAmountPaid").value("100"));

        when(paymentService.validate(any(), eq("t1"), eq("u1"))).thenReturn(payment());
        mvc.perform(post("/v3/canonical/payments/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":%s}".formatted(META, PAYMENT_DATA)))
                .andExpect(status().isOk());

        when(paymentService.search(any(), eq("t1"))).thenReturn(List.of(payment()));
        mvc.perform(get("/v3/canonical/payments").contentType(MediaType.APPLICATION_JSON)
                        .param("paymentModes", "CASH").content(METADATA_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].receiptNumber").doesNotExist())
                .andExpect(jsonPath("$.data[0].paymentDetails[0].receiptNumber").value("RCPT-1"));

        UUID id = UUID.randomUUID();
        when(paymentService.getById(eq(id), eq("t1"))).thenReturn(payment());
        mvc.perform(get("/v3/canonical/payments/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isOk());
    }

    @Test
    void paymentSearchRejectsInvalidEnumCsvInTheEnvelope() throws Exception {
        mvc.perform(get("/v3/canonical/payments").contentType(MediaType.APPLICATION_JSON)
                        .param("paymentModes", "CASH,BADMODE").content(METADATA_ONLY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$[0]").doesNotExist());
    }

    @Test
    void malformedPaymentIdIs400InTheEnvelope() throws Exception {
        mvc.perform(get("/v3/canonical/payments/xyz").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_PATH_PARAM"));
    }

    // ── metadata rules ────────────────────────────────────────────────────────

    /** Writes need the user id, exactly as HeaderInterceptor demands X-User-ID on non-GETs. */
    @Test
    void writesWithoutAUserIdAre400NamingTheBodyField() throws Exception {
        mvc.perform(post("/v3/canonical/bills/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestMetadata":{"ts":1712830200000,"tenantId":"t1"},
                                 "data":{"businessServiceCode":"PT","consumerCode":"C-1"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.errors[0].params[0]").value("requestMetadata.userInfo.userId"));
    }

    /** …and reads do not, exactly as the 3.0 GETs do not. */
    @Test
    void readsWithoutAUserIdAreFine() throws Exception {
        when(businessServiceService.search(any(), eq("t1"))).thenReturn(List.of());

        mvc.perform(get("/v3/canonical/business-services").contentType(MediaType.APPLICATION_JSON)
                        .content(NO_USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseMetadata.msgId").doesNotExist())
                .andExpect(jsonPath("$.responseMetadata.requestId").doesNotExist())
                .andExpect(jsonPath("$.data").isArray());
    }

    /** Raised in the order-0 filter, which no advice can reach — written there instead. */
    @Test
    void missingTenantIdIs400FromTheFilterInTheEnvelope() throws Exception {
        mvc.perform(post("/v3/canonical/bills/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"businessServiceCode\":\"PT\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMetadata.status").value("FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.errors[0].params[0]").value("requestMetadata.tenantId"));
    }

    @Test
    void malformedJsonAndMissingDataAre400InTheEnvelope() throws Exception {
        mvc.perform(post("/v3/canonical/payments").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").exists());

        mvc.perform(post("/v3/canonical/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(METADATA_ONLY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").exists());
    }

    @Test
    void emptyListPayloadIsTheSameInvalidRequestAsThe30Route() throws Exception {
        mvc.perform(post("/v3/canonical/business-services").contentType(MediaType.APPLICATION_JSON)
                        .content("{%s,\"data\":[]}".formatted(META)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("At least one business service must be provided"));
    }

    @Test
    void serviceExceptionsKeepTheirStatusInTheEnvelope() throws Exception {
        when(billService.generate(any(), eq("t1"), eq("u1")))
                .thenThrow(new CustomException("BILL_CONFLICT", "already active", HttpStatus.CONFLICT));

        mvc.perform(post("/v3/canonical/bills/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s,"data":{"businessServiceCode":"PT","consumerCode":"C-1"}}""".formatted(META)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("BILL_CONFLICT"));
    }

    // ── the 3.0 routes are untouched ──────────────────────────────────────────

    @Test
    void the30RoutesStillRequireHeadersAndStillAnswerWithABareArray() throws Exception {
        when(businessServiceService.search(any(), eq("t1"))).thenReturn(List.of());

        mvc.perform(get("/v3/business-services").header("X-Tenant-ID", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/v3/payments/xyz").header("X-Tenant-ID", "t1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$[0].code").value("INVALID_PATH_PARAM"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }
}
