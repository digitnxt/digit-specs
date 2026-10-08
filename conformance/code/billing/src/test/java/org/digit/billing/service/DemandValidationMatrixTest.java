package org.digit.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.entity.TaxHeadRow;
import org.digit.billing.model.DemandRequests;
import org.digit.billing.model.DemandRequests.LineItemCreate;
import org.digit.billing.repo.BillRepository;
import org.digit.billing.repo.BusinessServiceRepository;
import org.digit.billing.repo.DemandRepository;
import org.digit.billing.repo.TaxHeadRepository;
import org.digit.tracer.model.Error;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Go validateDemandCreate matrix — every code and edge (PORT_PLAN §7-1). */
class DemandValidationMatrixTest {

    private BusinessServiceRepository bsRepo;
    private TaxHeadRepository taxHeadRepo;
    private DemandService service;

    @BeforeEach
    void setUp() {
        bsRepo = mock(BusinessServiceRepository.class);
        taxHeadRepo = mock(TaxHeadRepository.class);
        service = new DemandService(mock(DemandRepository.class), bsRepo, taxHeadRepo,
                mock(BillRepository.class), null, PaymentValidationTest.props());

        BusinessServiceRow bs = new BusinessServiceRow();
        bs.code = "PT";
        bs.isActive = true;
        when(bsRepo.getByCode(eq("PT"), any())).thenReturn(Optional.of(bs));

        TaxHeadRow taxHead = new TaxHeadRow();
        taxHead.code = "PT_TAX";
        taxHead.businessServiceCode = "PT";
        taxHead.isActive = true;
        when(taxHeadRepo.getByCode(eq("PT_TAX"), any())).thenReturn(Optional.of(taxHead));

        TaxHeadRow foreign = new TaxHeadRow();
        foreign.code = "WS_TAX";
        foreign.businessServiceCode = "WS";
        foreign.isActive = true;
        when(taxHeadRepo.getByCode(eq("WS_TAX"), any())).thenReturn(Optional.of(foreign));
    }

    private static DemandRequests.Create demand(long from, long to, List<LineItemCreate> items) {
        return new DemandRequests.Create("PT", from, to, "CONSUMER-1", null, null, items, null, null, null);
    }

    private static LineItemCreate item(String code, String amount, String collected) {
        return new LineItemCreate(code, new BigDecimal(amount),
                collected == null ? null : new BigDecimal(collected), null);
    }

    private List<String> codes(DemandRequests.Create create) {
        return service.validateDemandCreate(create, "t1", new HashMap<>(), new HashMap<>())
                .stream().map(Error::getCode).toList();
    }

    @Test
    void happyPathHasNoErrors() {
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "100", "0")))).isEmpty());
    }

    @Test
    void periodToBeforeFrom() {
        assertEquals(List.of("INVALID_PERIOD"), codes(demand(2, 1, List.of(item("PT_TAX", "100", "0")))));
    }

    @Test
    void unknownAndInactiveBusinessService() {
        when(bsRepo.getByCode(eq("PT"), any())).thenReturn(Optional.empty());
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "100", "0"))))
                .contains("UNKNOWN_BUSINESS_SERVICE"));

        BusinessServiceRow inactive = new BusinessServiceRow();
        inactive.code = "PT";
        inactive.isActive = false;
        when(bsRepo.getByCode(eq("PT"), any())).thenReturn(Optional.of(inactive));
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "100", "0"))))
                .contains("UNKNOWN_BUSINESS_SERVICE"));
    }

    @Test
    void amountBoundsAreInclusiveAtOneBillion() {
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "1000000000", "0")))).isEmpty());
        assertEquals(List.of("INVALID_AMOUNT"),
                codes(demand(1, 2, List.of(item("PT_TAX", "1000000000.01", "0")))));
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "-1000000000", "-1000000000")))).isEmpty());
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "-1000000000.01", "0"))))
                .contains("INVALID_AMOUNT"));
    }

    @Test
    void negativeAmountCollectedMustBeZeroOrEqual() {
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "-50", "0")))).isEmpty());
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "-50", "-50.00")))).isEmpty());
        assertEquals(List.of("INVALID_COLLECTION"),
                codes(demand(1, 2, List.of(item("PT_TAX", "-50", "-25")))));
    }

    @Test
    void positiveAmountCollectedBetweenZeroAndAmount() {
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "50", "50")))).isEmpty());
        assertEquals(List.of("INVALID_COLLECTION"),
                codes(demand(1, 2, List.of(item("PT_TAX", "50", "-1")))));
        assertEquals(List.of("INVALID_COLLECTION"),
                codes(demand(1, 2, List.of(item("PT_TAX", "50", "51")))));
    }

    @Test
    void absentCollectedDefaultsToZero() {
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "50", null)))).isEmpty());
    }

    @Test
    void duplicateTaxHeadInSameDemand() {
        assertTrue(codes(demand(1, 2, List.of(item("PT_TAX", "50", "0"), item("PT_TAX", "10", "0"))))
                .contains("DUPLICATE_TAX_HEAD"));
    }

    @Test
    void unknownInactiveAndForeignTaxHeads() {
        when(taxHeadRepo.getByCode(eq("PT_MISSING"), any())).thenReturn(Optional.empty());
        assertEquals(List.of("UNKNOWN_TAX_HEAD"),
                codes(demand(1, 2, List.of(item("PT_MISSING", "50", "0")))));

        assertEquals(List.of("INVALID_TAX_HEAD"),
                codes(demand(1, 2, List.of(item("WS_TAX", "50", "0")))));
    }

    @Test
    void cachesShortCircuitRepeatedLookups() {
        Map<String, Boolean> bsCache = new HashMap<>();
        Map<String, TaxHeadRow> taxHeadCache = new HashMap<>();
        DemandRequests.Create create = demand(1, 2, List.of(item("PT_TAX", "100", "0")));
        assertTrue(service.validateDemandCreate(create, "t1", bsCache, taxHeadCache).isEmpty());
        assertTrue(service.validateDemandCreate(create, "t1", bsCache, taxHeadCache).isEmpty());
        verify(bsRepo, times(1)).getByCode(eq("PT"), any());
        verify(taxHeadRepo, times(1)).getByCode(eq("PT_TAX"), any());
    }
}
