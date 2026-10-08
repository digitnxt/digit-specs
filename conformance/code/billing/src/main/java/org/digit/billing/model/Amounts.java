package org.digit.billing.model;

import java.math.BigDecimal;

/**
 * Decimal semantics matching shopspring/decimal (HR-2): value comparison is
 * scale-insensitive — always compareTo, never equals.
 */
public final class Amounts {

    private Amounts() {
    }

    public static boolean eq(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    public static boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    public static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    public static boolean isZero(BigDecimal a) {
        return a.signum() == 0;
    }

    /** Go: paid.Equal(paid.Truncate(0)) — no fractional part. */
    public static boolean isIntegral(BigDecimal a) {
        return a.stripTrailingZeros().scale() <= 0;
    }

    /** Go line-item over-collection guard: |collected| > |amount|. */
    public static boolean absGreaterThan(BigDecimal a, BigDecimal b) {
        return a.abs().compareTo(b.abs()) > 0;
    }

    public static BigDecimal nvl(BigDecimal a) {
        return a == null ? BigDecimal.ZERO : a;
    }
}
