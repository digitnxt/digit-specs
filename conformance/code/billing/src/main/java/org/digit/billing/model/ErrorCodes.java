package org.digit.billing.model;

/** Error codes from DISCOVERY.md §1/§2 plus checkpoint-approved additions (Q3/Q5/Q9/Q10). */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    public static final String MISSING_HEADER = "MISSING_HEADER";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INVALID_PATH_PARAM = "INVALID_PATH_PARAM";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String DUPLICATE_VALUES = "DUPLICATE_VALUES";
    public static final String DEPENDENCY_EXISTS = "DEPENDENCY_EXISTS";

    // business service / tax head
    public static final String INVALID_EFFECTIVE_RANGE = "INVALID_EFFECTIVE_RANGE";
    public static final String INVALID_EFFECTIVE_TO = "INVALID_EFFECTIVE_TO";
    public static final String INVALID_EFFECTIVE_FROM = "INVALID_EFFECTIVE_FROM";
    public static final String INVALID_BUSINESS_SERVICE = "INVALID_BUSINESS_SERVICE";
    public static final String DUPLICATE_ORDER = "DUPLICATE_ORDER";

    // demand
    public static final String INVALID_PERIOD = "INVALID_PERIOD";
    public static final String INVALID_LINE_ITEMS = "INVALID_LINE_ITEMS";
    public static final String UNKNOWN_BUSINESS_SERVICE = "UNKNOWN_BUSINESS_SERVICE";
    public static final String UNKNOWN_TAX_HEAD = "UNKNOWN_TAX_HEAD";
    public static final String INVALID_TAX_HEAD = "INVALID_TAX_HEAD";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String INVALID_COLLECTION = "INVALID_COLLECTION";
    public static final String DUPLICATE_TAX_HEAD = "DUPLICATE_TAX_HEAD";
    public static final String DEMAND_CONFLICT = "DEMAND_CONFLICT";
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String CREATION_FAILED = "CREATION_FAILED";
    public static final String UPDATE_FAILED = "UPDATE_FAILED";
    public static final String FREEZE_FAILED = "FREEZE_FAILED";
    public static final String CANCEL_FAILED = "CANCEL_FAILED";

    // bill
    public static final String NO_ELIGIBLE_DEMANDS = "NO_ELIGIBLE_DEMANDS";
    public static final String GENERATION_FAILED = "GENERATION_FAILED";
    public static final String CANCELLATION_FAILED = "CANCELLATION_FAILED";
    public static final String INVALID_STATUS = "INVALID_STATUS";

    // payment
    public static final String INVALID_BILL_ID = "INVALID_BILL_ID";
    public static final String BILL_ALREADY_PAID = "BILL_ALREADY_PAID";
    public static final String BILL_NOT_ACTIVE = "BILL_NOT_ACTIVE";
    public static final String DUPLICATE_BILL_ID = "DUPLICATE_BILL_ID";
    public static final String INVALID_TOTAL_AMOUNT_PAID = "INVALID_TOTAL_AMOUNT_PAID";
    public static final String INVALID_PAYMENTDETAIL = "INVALID_PAYMENTDETAIL";
    /**
     * Payment exceeds the bill's amount due. Deliberately the same value apportion uses for
     * the same condition, so a caller sees one code whether billing rejects it up front or
     * apportion rejects it during bill apportioning.
     */
    public static final String OVERPAYMENT_NOT_ALLOWED = "OVERPAYMENT_NOT_ALLOWED";
    public static final String INVALID_PAYMENT_MODE = "INVALID_PAYMENT_MODE";
    public static final String INVALID_INST_NUMBER = "INVALID_INST_NUMBER";
    public static final String INVALID_INST_DATE = "INVALID_INST_DATE";
    public static final String INVALID_CHEQUE_DD_DATE = "INVALID_CHEQUE_DD_DATE";
    public static final String CHEQUE_DD_DATE_EXCEEDS_MANUAL_RECEIPT = "CHEQUE_DD_DATE_EXCEEDS_MANUAL_RECEIPT";
    public static final String CHEQUE_DD_DATE_EXCEEDS_RECEIPT = "CHEQUE_DD_DATE_EXCEEDS_RECEIPT";
    // Q5: the constant Go defined but never used — replaces "ChequeDDDateWithFutureDate"
    public static final String CHEQUE_DD_DATE_IN_FUTURE = "CHEQUE_DD_DATE_IN_FUTURE";
    public static final String INVALID_NEFT_RTGS_DATE = "INVALID_NEFT_RTGS_DATE";
    public static final String INVALID_TXN_NUMBER = "INVALID_TXN_NUMBER";
    public static final String INVALID_INSTRUMENT_NUMBER = "INVALID_INSTRUMENT_NUMBER";
    public static final String TXN_NUMBER_GENERATION_ERROR = "TXN_NUMBER_GENERATION_ERROR";
    public static final String RECEIPT_NUMBER_GENERATION_ERROR = "RECEIPT_NUMBER_GENERATION_ERROR";
    public static final String APPORTION_MISSING_BILL = "APPORTION_MISSING_BILL";
    public static final String APPORTION_MISSING_DETAIL = "APPORTION_MISSING_DETAIL";
    public static final String APPORTION_MISSING_ACCOUNT_DETAIL = "APPORTION_MISSING_ACCOUNT_DETAIL";
    public static final String OVER_COLLECTION_DETECTED = "OVER_COLLECTION_DETECTED";
    public static final String OVER_COLLECTION_LINEITEM = "OVER_COLLECTION_LINEITEM";
}
