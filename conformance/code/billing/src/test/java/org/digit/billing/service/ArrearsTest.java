package org.digit.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.digit.billing.config.BillingProperties;
import org.digit.billing.entity.BillRow;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.entity.DemandRow;
import org.digit.billing.entity.TaxHeadRow;
import org.digit.billing.model.Demand;
import org.digit.billing.model.DemandRequests;
import org.digit.billing.model.DemandRequests.LineItemCreate;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.repo.BillRepository;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.billing.repo.DemandPeriodConflictException;
import org.digit.billing.repo.DemandRepository;
import org.digit.billing.repo.TaxHeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Arrear roll-forward: prepend, chain ordering, ROLL_FORWARDED transition, missing head. */
class ArrearsTest {

    private DemandRepository repo;
    private TaxHeadRepository taxHeadRepo;
    private BillRepository billRepo;
    private DemandService service;
    private final UUID previousDemandId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(DemandRepository.class);
        BusinessServiceRepository bsRepo = mock(BusinessServiceRepository.class);
        taxHeadRepo = mock(TaxHeadRepository.class);
        billRepo = mock(BillRepository.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));

        BillingProperties props = new BillingProperties(true, 90, 10, false,
                PaymentValidationTest.props().idgen(), PaymentValidationTest.props().apportion(),
                PaymentValidationTest.props().topics());
        service = new DemandService(repo, bsRepo, taxHeadRepo, billRepo, tx, props);

        BusinessServiceRow bs = new BusinessServiceRow();
        bs.code = "PT";
        bs.isActive = true;
        when(bsRepo.getByCode(eq("PT"), any())).thenReturn(Optional.of(bs));

        TaxHeadRow tax = new TaxHeadRow();
        tax.code = "PT_TAX";
        tax.businessServiceCode = "PT";
        tax.isActive = true;
        when(taxHeadRepo.getByCode(eq("PT_TAX"), any())).thenReturn(Optional.of(tax));

        DemandRow previous = new DemandRow();
        previous.id = previousDemandId;
        previous.status = DemandStatus.FROZEN;
        previous.totalAmount = new BigDecimal("100");
        previous.totalCollectedAmount = new BigDecimal("40");
        previous.arrearDemandIds = List.of("older-demand-id");
        previous.version = 2;
        previous.metadata = Map.of();
        when(repo.getLatestOpenDemandForUpdate(any(), eq("PT"), eq("C-1")))
                .thenReturn(Optional.of(previous));
        when(repo.getLineItems(previousDemandId)).thenReturn(List.of());
    }

    private static DemandRequests.Create create() {
        return new DemandRequests.Create("PT", 1L, 2L, "C-1", null, null,
                List.of(new LineItemCreate("PT_TAX", new BigDecimal("200"), null, null)), null, null, null);
    }

    /**
     * The contract change that came with tenant-migration, pinned here so it is not silently
     * reverted: a write-phase conflict fails the WHOLE bulk request with a typed 409 naming the
     * offending item, rather than being collected into {@code failures} alongside successes.
     *
     * <p>Collecting it is no longer possible. The filter puts the request in one transaction, so the
     * first failed statement aborts it — every later item would fail on "current transaction is
     * aborted" and be reported as a failure it never had, and the commit would then throw after a
     * success-shaped body had been built. No rows are written either way; this reports it honestly.
     */
    @Test
    void writeConflictFailsTheWholeBulkRequest() {
        TaxHeadRow arrear = new TaxHeadRow();
        arrear.code = "PT_ARREAR";
        arrear.businessServiceCode = "PT";
        arrear.isActive = true;
        when(taxHeadRepo.getByCode(eq("PT_ARREAR"), any())).thenReturn(Optional.of(arrear));
        doThrow(new DemandPeriodConflictException(new RuntimeException("23P01")))
                .when(repo).create(any(), any());

        CustomException e = assertThrows(CustomException.class,
                () -> service.create(List.of(create(), create()), "t1", "u1"));
        assertEquals("DEMAND_CONFLICT", e.getCode());
        assertEquals(HttpStatus.CONFLICT, e.getHttpStatus());
        assertTrue(e.getDescription().contains("item 0"), e.getDescription());
        assertTrue(e.getDescription().contains("no demands were created"), e.getDescription());
    }

    @Test
    void arrearLineItemPrependedWithOutstandingAndChain() {
        TaxHeadRow arrear = new TaxHeadRow();
        arrear.code = "PT_ARREAR";
        arrear.businessServiceCode = "PT";
        arrear.isActive = true;
        when(taxHeadRepo.getByCode(eq("PT_ARREAR"), any())).thenReturn(Optional.of(arrear));

        DemandRequests.BulkResponse response = service.create(List.of(create()), "t1", "u1");
        assertTrue(response.failures() == null || response.failures().isEmpty(), String.valueOf(response.failures()));

        Demand created = response.success().get(0);
        assertEquals("PT_ARREAR", created.lineItems().get(0).taxHeadCode());
        assertEquals(new BigDecimal("60"), created.lineItems().get(0).amount()); // 100 - 40
        assertEquals("PT_TAX", created.lineItems().get(1).taxHeadCode());
        assertEquals(new BigDecimal("260"), created.totalAmount());
        // newest → oldest chain
        assertEquals(List.of(previousDemandId.toString(), "older-demand-id"), created.arrearDemandIds());

        // previous demand audited and transitioned
        verify(repo).insertAudit(any(DemandRow.class), any());
        ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.captor();
        verify(repo).updateFields(eq(previousDemandId), eq("t1"), updates.capture());
        assertEquals(org.digit.billing.model.DemandStatus.ROLL_FORWARDED, updates.getValue().get("status"));
        assertEquals(3, updates.getValue().get("version"));
    }

    @Test
    void frozenDemandWithActiveBillIsCancelledOnRollForward() {
        TaxHeadRow arrear = new TaxHeadRow();
        arrear.code = "PT_ARREAR";
        arrear.businessServiceCode = "PT";
        arrear.isActive = true;
        when(taxHeadRepo.getByCode(eq("PT_ARREAR"), any())).thenReturn(Optional.of(arrear));

        UUID billId = UUID.randomUUID();
        BillRow bill = new BillRow();
        bill.id = billId;
        bill.metadata = Map.of("existing", "value");
        when(billRepo.fetchActiveBillForUpdate(any(), eq("PT"), eq("C-1"))).thenReturn(Optional.of(bill));

        DemandRequests.BulkResponse response = service.create(List.of(create()), "t1", "u1");
        assertTrue(response.failures() == null || response.failures().isEmpty(), String.valueOf(response.failures()));

        verify(billRepo).insertAudit(eq(bill), any(), any());
        ArgumentCaptor<String> metadataJson = ArgumentCaptor.captor();
        verify(billRepo).cancelById(eq(billId), metadataJson.capture(), eq("u1"), anyLong());
        assertTrue(metadataJson.getValue().contains("ROLLED_FORWARD"));
        assertTrue(metadataJson.getValue().contains(previousDemandId.toString()));
        assertTrue(metadataJson.getValue().contains("existing")); // pre-existing metadata preserved
    }

    @Test
    void activeNeverBilledDemandSkipsBillCancellation() {
        TaxHeadRow arrear = new TaxHeadRow();
        arrear.code = "PT_ARREAR";
        arrear.businessServiceCode = "PT";
        arrear.isActive = true;
        when(taxHeadRepo.getByCode(eq("PT_ARREAR"), any())).thenReturn(Optional.of(arrear));

        DemandRow neverBilled = new DemandRow();
        neverBilled.id = previousDemandId;
        neverBilled.status = DemandStatus.ACTIVE;
        neverBilled.totalAmount = new BigDecimal("100");
        neverBilled.totalCollectedAmount = BigDecimal.ZERO;
        neverBilled.version = 1;
        neverBilled.metadata = Map.of();
        when(repo.getLatestOpenDemandForUpdate(any(), eq("PT"), eq("C-1"))).thenReturn(Optional.of(neverBilled));
        when(repo.getLineItems(previousDemandId)).thenReturn(List.of());

        DemandRequests.BulkResponse response = service.create(List.of(create()), "t1", "u1");
        assertTrue(response.failures() == null || response.failures().isEmpty(), String.valueOf(response.failures()));

        verify(billRepo, never()).fetchActiveBillForUpdate(any(), any(), any());
    }

    /**
     * Was {@code missingArrearTaxHeadFailsTheItem}: this used to be collected as a per-item failure
     * inside a 201. Write-phase failures are fatal to the whole request now, because the
     * tenant-migration filter puts the request in one transaction — the first failure aborts it, so
     * continuing the loop would report later items as failed when they were never really attempted,
     * and the commit would throw after a success-shaped body had already been built. Nothing is
     * persisted either way; this just says so honestly.
     */
    @Test
    void missingArrearTaxHeadFailsTheRequest() {
        when(taxHeadRepo.getByCode(eq("PT_ARREAR"), any())).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class,
                () -> service.create(List.of(create()), "t1", "u1"));
        assertEquals("CREATION_FAILED", e.getCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getHttpStatus());
        assertTrue(e.getDescription().contains("PT_ARREAR"), e.getDescription());
    }

    @Test
    void noArrearWhenNothingOutstanding() {
        when(repo.getLatestOpenDemandForUpdate(any(), eq("PT"), eq("C-1"))).thenReturn(Optional.empty());
        DemandRequests.BulkResponse response = service.create(List.of(create()), "t1", "u1");
        assertEquals(1, response.success().get(0).lineItems().size());
    }
}
