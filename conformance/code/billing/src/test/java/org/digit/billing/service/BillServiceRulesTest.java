package org.digit.billing.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.digit.billing.entity.DemandRow;
import org.digit.billing.entity.DemandRow.LineItemRow;
import org.digit.billing.model.BillRequests;
import org.digit.billing.model.BillStatus;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Review finding #4: pins for Q9, buildBillRows failure branches, DLQ empty-job guard. */
class BillServiceRulesTest {

    private final BillService service = new BillService(null, null, null, null, null, null,
            PaymentValidationTest.props(), null, null);

    private static DemandRow demand(String total, String collected) {
        DemandRow row = new DemandRow();
        row.id = UUID.randomUUID();
        row.periodFrom = 1;
        row.periodTo = 2;
        row.totalAmount = new BigDecimal(total);
        row.totalCollectedAmount = new BigDecimal(collected);
        return row;
    }

    private static LineItemRow item(UUID demandId, String taxHeadCode, String amount, String collected) {
        LineItemRow item = new LineItemRow();
        item.id = UUID.randomUUID();
        item.demandId = demandId;
        item.taxHeadCode = taxHeadCode;
        item.amount = new BigDecimal(amount);
        item.collectedAmount = new BigDecimal(collected);
        return item;
    }

    @Test
    void q9CancelRequiresCancelledStatus() {
        CustomException e = assertThrows(CustomException.class, () -> service.cancel(
                new BillRequests.UpdateBillStatus("PT", "C-1", BillStatus.PAID, Map.of()), "t1", "u1"));
        assertEquals("INVALID_STATUS", e.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, e.getHttpStatus());
        assertEquals(List.of("PAID"), e.getParams());
    }

    @Test
    void zeroOutstandingFailsGeneration() {
        DemandRow demand = demand("100", "100");
        CustomException e = assertThrows(CustomException.class, () -> service.buildBillRows(
                "PT", "C-1", null, List.of(demand),
                Map.of(demand.id, List.of(item(demand.id, "PT_TAX", "100", "100"))),
                Map.of("PT_TAX", 1), "t1", "u1", "BILL-1", null, 1L));
        assertEquals("GENERATION_FAILED", e.getCode());
        assertEquals("no outstanding amount found", e.getDescription());
    }

    @Test
    void missingTaxHeadOrderFailsGeneration() {
        DemandRow demand = demand("100", "0");
        CustomException e = assertThrows(CustomException.class, () -> service.buildBillRows(
                "PT", "C-1", null, List.of(demand),
                Map.of(demand.id, List.of(item(demand.id, "PT_UNKNOWN", "100", "0"))),
                Map.of("PT_TAX", 1), "t1", "u1", "BILL-1", null, 1L));
        assertEquals("GENERATION_FAILED", e.getCode());
        assertEquals("missing tax head: PT_UNKNOWN", e.getDescription());
    }

    @Test
    void happyBuildComputesOutstandingAmounts() {
        DemandRow demand = demand("100", "40");
        var tree = service.buildBillRows("PT", "C-1", null, List.of(demand),
                Map.of(demand.id, List.of(item(demand.id, "PT_TAX", "100", "40"))),
                Map.of("PT_TAX", 1), "t1", "u1", "BILL-1", null, 1L);
        assertEquals(new BigDecimal("60"), tree.bills().get(0).totalAmount);
        assertEquals(new BigDecimal("60"), tree.details().get(0).amount);
        assertEquals(new BigDecimal("60"),
                tree.accountsByDetail().get(tree.details().get(0).id).get(0).amount);
        assertEquals(BillStatus.ACTIVE, tree.bills().get(0).status);
    }

    @Test
    void emptyDlqJobIsNoOpNotAllFailed() {
        assertDoesNotThrow(() -> service.retryBulkJobPerConsumer(
                new BillRequests.BulkBillGenerationJob("j1", "r1", "t1", "PT", List.of(), "u1", 1, null)));
    }
}
