package org.digit.billing.model;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentMode {
    CASH, CHEQUE, DD, POSTAL_ORDER, OFFLINE_NEFT, OFFLINE_RTGS,
    ONLINE, UPI, CARD, NETBANKING, WALLET, ONLINE_NEFT, ONLINE_RTGS;

    /** Instant electronic modes: DEPOSITED/REMITTED statuses, instrument date defaults to txn date. */
    public static final Set<PaymentMode> ONLINE_FAMILY =
            EnumSet.of(ONLINE, UPI, CARD, NETBANKING, WALLET, ONLINE_NEFT, ONLINE_RTGS);

    public boolean isOnlineFamily() {
        return ONLINE_FAMILY.contains(this);
    }
}
