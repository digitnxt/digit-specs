package org.digit.billing.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** HR-2: shopspring-compatible decimal semantics — scale-insensitive comparison. */
class AmountsTest {

    @Test
    void equalityIgnoresScale() {
        assertTrue(Amounts.eq(new BigDecimal("100"), new BigDecimal("100.00")));
        assertTrue(Amounts.eq(new BigDecimal("100.5"), new BigDecimal("100.50")));
        assertFalse(Amounts.eq(new BigDecimal("100.5"), new BigDecimal("100.51")));
        // the reason equals() is banned:
        assertFalse(new BigDecimal("100").equals(new BigDecimal("100.00")));
    }

    @Test
    void integralCheckMatchesGoTruncateEqual() {
        assertTrue(Amounts.isIntegral(new BigDecimal("100")));
        assertTrue(Amounts.isIntegral(new BigDecimal("100.00")));
        assertTrue(Amounts.isIntegral(new BigDecimal("-3")));
        assertTrue(Amounts.isIntegral(BigDecimal.ZERO));
        assertTrue(Amounts.isIntegral(new BigDecimal("0.00")));
        assertFalse(Amounts.isIntegral(new BigDecimal("100.50")));
        assertFalse(Amounts.isIntegral(new BigDecimal("0.01")));
    }

    @Test
    void absComparisonForLineItemOverCollection() {
        assertTrue(Amounts.absGreaterThan(new BigDecimal("-11"), new BigDecimal("-10")));
        assertFalse(Amounts.absGreaterThan(new BigDecimal("-10"), new BigDecimal("-10.00")));
        assertTrue(Amounts.absGreaterThan(new BigDecimal("11"), new BigDecimal("10")));
        assertFalse(Amounts.absGreaterThan(new BigDecimal("9.99"), new BigDecimal("10")));
    }

    @Test
    void zeroAndOrdering() {
        assertTrue(Amounts.isZero(new BigDecimal("0.00")));
        assertTrue(Amounts.lt(new BigDecimal("9.99"), BigDecimal.TEN));
        assertTrue(Amounts.gt(new BigDecimal("10.01"), BigDecimal.TEN));
        assertEquals(BigDecimal.ZERO, Amounts.nvl(null));
    }
}
