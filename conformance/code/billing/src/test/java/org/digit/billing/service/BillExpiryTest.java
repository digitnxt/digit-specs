package org.digit.billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.digit.billing.entity.DemandRow;
import org.junit.jupiter.api.Test;

/** Expiry precedence: demand-level days → BS-level days; 0 = never (DISCOVERY §2). */
class BillExpiryTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = 86_400_000L;

    private static DemandRow demand(Integer billExpiryDays) {
        DemandRow row = new DemandRow();
        row.billExpiryDays = billExpiryDays;
        return row;
    }

    @Test
    void demandDaysWin() {
        assertEquals(NOW + 5 * DAY, BillService.determineBillExpiry(demand(5), 30, NOW));
    }

    @Test
    void demandZeroMeansNeverEvenWhenBsHasDays() {
        assertNull(BillService.determineBillExpiry(demand(0), 30, NOW));
    }

    @Test
    void bsDaysUsedWhenDemandUnset() {
        assertEquals(NOW + 30 * DAY, BillService.determineBillExpiry(demand(null), 30, NOW));
    }

    @Test
    void bsZeroOrNullMeansNever() {
        assertNull(BillService.determineBillExpiry(demand(null), 0, NOW));
        assertNull(BillService.determineBillExpiry(demand(null), null, NOW));
    }

    @Test
    void largeDayCountsDoNotOverflowInt() {
        assertEquals(NOW + 1095 * DAY, BillService.determineBillExpiry(demand(1095), null, NOW));
    }
}
