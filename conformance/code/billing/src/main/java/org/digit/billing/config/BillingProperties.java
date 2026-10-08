package org.digit.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Service-level configuration (was billing-go's env vars; defaults match). */
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
        @DefaultValue("false") boolean demandEnableArrears,
        @DefaultValue("90") int maxInstrumentDateAgeDays,
        @DefaultValue("10") int bulkBillConsumerBatchSize,
        /**
         * Whether a payment may exceed the bill's amount due. Must be kept in step with
         * apportion's {@code apportion.enable-advance}, which is what decides the excess is
         * recordable as an advance credit: with advance off apportion rejects the overpayment,
         * so accepting it here would only move the failure past the point of no return. Left
         * false by default, matching the deployed apportion configuration.
         */
        @DefaultValue("false") boolean overpaymentAllowed,
        @DefaultValue Idgen idgen,
        @DefaultValue Apportion apportion,
        @DefaultValue Topics topics) {

    public record Idgen(
            @DefaultValue("http://localhost:8100") String host,
            @DefaultValue("/idgen/v3/generate") String generatePath,
            @DefaultValue("/idgen/v3/generate/bulk") String bulkGeneratePath,
            @DefaultValue("BillNumber") String billNumberTemplate,
            @DefaultValue("ReceiptNumber") String receiptNumberTemplate,
            @DefaultValue("TransactionNumber") String transactionNumberTemplate) {
    }

    public record Apportion(
            @DefaultValue("http://localhost:8280") String host,
            @DefaultValue("/apportion/v3/bills") String billPath) {
    }

    public record Topics(
            @DefaultValue("bulk-bill-generator") String bulkBillGeneration,
            @DefaultValue("bulk-bill-generator-dlq") String bulkBillGenerationDlq) {
    }
}
