package org.digit.billing.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.digit.billing.entity.PaymentRow;
import org.digit.billing.entity.PaymentRow.PaymentDetailRow;
import org.digit.billing.model.InstrumentStatus;
import org.digit.billing.model.Json;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.model.PaymentRequests;
import org.digit.billing.model.PaymentStatus;
import org.digit.billing.model.ReceiptType;
import org.digit.billing.service.RowHash;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private static final String PAYMENT_COLUMNS =
            "id, tenant_id, total_amount_due, total_amount_paid, transaction_number, transaction_date, "
                    + "payment_mode, payment_status, instrument_number, instrument_date, instrument_status, "
                    + "ifsc_code, paid_by, payer_id, payer_name, payer_address, payer_mobile_number, "
                    + "payer_email, filestore_id, metadata, "
                    + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private static final String DETAIL_COLUMNS =
            "id, tenant_id, payment_id, bill_id, business_service_code, total_amount_due, total_amount_paid, "
                    + "receipt_number, receipt_date, receipt_type, manual_receipt_number, manual_receipt_date, "
                    + "metadata, \"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private final JdbcClient jdbc;
    private final RowMapper<PaymentRow> paymentMapper = (rs, rowNum) -> mapPayment(rs);
    private final RowMapper<PaymentDetailRow> detailMapper = (rs, rowNum) -> mapDetail(rs);

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private PaymentRow mapPayment(ResultSet rs) throws SQLException {
        PaymentRow row = new PaymentRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.totalAmountDue = Db.dec(rs, "total_amount_due");
        row.totalAmountPaid = Db.dec(rs, "total_amount_paid");
        row.transactionNumber = rs.getString("transaction_number");
        row.transactionDate = rs.getLong("transaction_date");
        row.paymentMode = PaymentMode.valueOf(rs.getString("payment_mode"));
        row.paymentStatus = PaymentStatus.valueOf(rs.getString("payment_status"));
        row.instrumentNumber = rs.getString("instrument_number");
        row.instrumentDate = rs.getObject("instrument_date", Long.class);
        row.instrumentStatus = InstrumentStatus.valueOf(rs.getString("instrument_status"));
        row.ifscCode = rs.getString("ifsc_code");
        row.paidBy = rs.getString("paid_by");
        row.payerId = rs.getString("payer_id");
        row.payerName = rs.getString("payer_name");
        row.payerAddress = rs.getString("payer_address");
        row.payerMobileNumber = rs.getString("payer_mobile_number");
        row.payerEmail = rs.getString("payer_email");
        row.fileStoreId = rs.getString("filestore_id");
        row.metadata = Json.readMap(rs.getString("metadata"));
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    private PaymentDetailRow mapDetail(ResultSet rs) throws SQLException {
        PaymentDetailRow row = new PaymentDetailRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.paymentId = rs.getObject("payment_id", UUID.class);
        row.billId = rs.getObject("bill_id", UUID.class);
        row.businessServiceCode = rs.getString("business_service_code");
        row.totalAmountDue = Db.dec(rs, "total_amount_due");
        row.totalAmountPaid = Db.dec(rs, "total_amount_paid");
        row.receiptNumber = rs.getString("receipt_number");
        row.receiptDate = rs.getLong("receipt_date");
        row.receiptType = ReceiptType.valueOf(rs.getString("receipt_type"));
        row.manualReceiptNumber = rs.getString("manual_receipt_number");
        row.manualReceiptDate = rs.getObject("manual_receipt_date", Long.class);
        row.metadata = Json.readMap(rs.getString("metadata"));
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    public void create(PaymentRow payment, List<PaymentDetailRow> details) {
        jdbc.sql("INSERT INTO payments (" + PAYMENT_COLUMNS + ") VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                .params(payment.id, payment.tenantId, payment.totalAmountDue, payment.totalAmountPaid,
                        payment.transactionNumber, payment.transactionDate, payment.paymentMode.name(),
                        payment.paymentStatus.name(), payment.instrumentNumber, payment.instrumentDate,
                        payment.instrumentStatus.name(), payment.ifscCode, payment.paidBy, payment.payerId,
                        payment.payerName, payment.payerAddress, payment.payerMobileNumber,
                        payment.payerEmail, payment.fileStoreId, Json.writeMap(payment.metadata),
                        payment.createdBy, payment.createdTime, payment.modifiedBy, payment.modifiedTime)
                .update();
        for (PaymentDetailRow detail : details) {
            jdbc.sql("INSERT INTO payment_details (" + DETAIL_COLUMNS + ") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(detail.id, detail.tenantId, detail.paymentId, detail.billId,
                            detail.businessServiceCode, detail.totalAmountDue, detail.totalAmountPaid,
                            detail.receiptNumber, detail.receiptDate, detail.receiptType.name(),
                            detail.manualReceiptNumber, detail.manualReceiptDate, Json.writeMap(detail.metadata),
                            detail.createdBy, detail.createdTime, detail.modifiedBy, detail.modifiedTime)
                    .update();
        }
    }

    public Optional<PaymentRow> getById(UUID id, String tenantId) {
        return jdbc.sql("SELECT " + PAYMENT_COLUMNS + " FROM payments WHERE id = ? AND tenant_id = ? LIMIT 1")
                .params(id, tenantId)
                .query(paymentMapper)
                .optional();
    }

    public List<PaymentDetailRow> getDetails(UUID paymentId, String tenantId) {
        return jdbc.sql("SELECT " + DETAIL_COLUMNS + " FROM payment_details "
                        + "WHERE payment_id = ? AND tenant_id = ?")
                .params(paymentId, tenantId)
                .query(detailMapper)
                .list();
    }

    public List<PaymentDetailRow> getDetailsByPaymentIds(List<UUID> paymentIds, String tenantId) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + DETAIL_COLUMNS + " FROM payment_details "
                        + "WHERE payment_id IN (:ids) AND tenant_id = :tenantId")
                .param("ids", paymentIds)
                .param("tenantId", tenantId)
                .query(detailMapper)
                .list();
    }

    public List<PaymentRow> search(PaymentRequests.Filters filters, String tenantId) {
        StringBuilder sql = new StringBuilder("SELECT " + PAYMENT_COLUMNS
                + " FROM payments WHERE payments.tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (filters.paymentIds() != null && !filters.paymentIds().isEmpty()) {
            sql.append(" AND payments.id IN (:paymentIds)");
            params.put("paymentIds", filters.paymentIds());
        }
        if (filters.transactionNumber() != null) {
            sql.append(" AND payments.transaction_number = :transactionNumber");
            params.put("transactionNumber", filters.transactionNumber());
        }
        if (filters.paymentStatuses() != null && !filters.paymentStatuses().isEmpty()) {
            sql.append(" AND payments.payment_status IN (:paymentStatuses)");
            params.put("paymentStatuses", filters.paymentStatuses().stream().map(Enum::name).toList());
        }
        if (filters.instrumentStatuses() != null && !filters.instrumentStatuses().isEmpty()) {
            sql.append(" AND payments.instrument_status IN (:instrumentStatuses)");
            params.put("instrumentStatuses", filters.instrumentStatuses().stream().map(Enum::name).toList());
        }
        if (filters.paymentModes() != null && !filters.paymentModes().isEmpty()) {
            sql.append(" AND payments.payment_mode IN (:paymentModes)");
            params.put("paymentModes", filters.paymentModes().stream().map(Enum::name).toList());
        }
        if (filters.payerIds() != null && !filters.payerIds().isEmpty()) {
            sql.append(" AND payments.payer_id IN (:payerIds)");
            params.put("payerIds", filters.payerIds());
        }
        if (filters.payerMobileNumber() != null) {
            sql.append(" AND payments.payer_mobile_number = :payerMobileNumber");
            params.put("payerMobileNumber", filters.payerMobileNumber());
        }
        if (filters.fromDate() != null) {
            sql.append(" AND payments.transaction_date >= :fromDate");
            params.put("fromDate", filters.fromDate());
        }
        if (filters.toDate() != null) {
            sql.append(" AND payments.transaction_date <= :toDate");
            params.put("toDate", filters.toDate());
        }

        boolean hasDetailFilter = filters.businessServiceCode() != null
                || (filters.billIds() != null && !filters.billIds().isEmpty())
                || (filters.receiptNumbers() != null && !filters.receiptNumbers().isEmpty());
        if (hasDetailFilter) {
            sql.append(" AND payments.id IN (SELECT payment_id FROM payment_details WHERE tenant_id = :tenantId");
            if (filters.businessServiceCode() != null) {
                sql.append(" AND business_service_code = :businessServiceCode");
                params.put("businessServiceCode", filters.businessServiceCode());
            }
            if (filters.billIds() != null && !filters.billIds().isEmpty()) {
                sql.append(" AND bill_id IN (:billIds)");
                params.put("billIds", filters.billIds());
            }
            if (filters.receiptNumbers() != null && !filters.receiptNumbers().isEmpty()) {
                sql.append(" AND receipt_number IN (:receiptNumbers)");
                params.put("receiptNumbers", filters.receiptNumbers());
            }
            sql.append(")");
        }
        if (filters.consumerCodes() != null && !filters.consumerCodes().isEmpty()) {
            sql.append(" AND payments.id IN (SELECT payment_id FROM payment_details "
                    + "WHERE tenant_id = :tenantId AND bill_id IN "
                    + "(SELECT id FROM bills WHERE tenant_id = :tenantId AND consumer_code IN (:consumerCodes)))");
            params.put("consumerCodes", filters.consumerCodes());
        }
        sql.append(" ORDER BY payments.\"createdTime\" DESC LIMIT :limit OFFSET :offset");
        params.put("limit", filters.limit());
        params.put("offset", filters.offset());
        return jdbc.sql(sql.toString()).params(params).query(paymentMapper).list();
    }

    public List<PaymentRow> getByBillIds(String tenantId, List<UUID> billIds) {
        if (billIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT payments.* FROM payments "
                        + "JOIN payment_details ON payment_details.payment_id = payments.id "
                        + "WHERE payment_details.bill_id IN (:billIds) AND payment_details.tenant_id = :tenantId "
                        + "GROUP BY payments.id")
                .param("billIds", billIds)
                .param("tenantId", tenantId)
                .query(paymentMapper)
                .list();
    }

    public void insertAudit(PaymentRow payment, List<PaymentDetailRow> details) {
        jdbc.sql("INSERT INTO payments_audit (id, row_hash, payment_id, tenant_id, total_amount_due, "
                        + "total_amount_paid, transaction_number, transaction_date, payment_mode, payment_status, "
                        + "instrument_number, instrument_date, instrument_status, ifsc_code, paid_by, payer_id, "
                        + "payer_name, payer_address, payer_mobile_number, payer_email, filestore_id, metadata, "
                        + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), RowHash.of(payment), payment.id, payment.tenantId,
                        payment.totalAmountDue, payment.totalAmountPaid, payment.transactionNumber,
                        payment.transactionDate, payment.paymentMode.name(), payment.paymentStatus.name(),
                        payment.instrumentNumber, payment.instrumentDate, payment.instrumentStatus.name(),
                        payment.ifscCode, payment.paidBy, payment.payerId, payment.payerName,
                        payment.payerAddress, payment.payerMobileNumber, payment.payerEmail,
                        payment.fileStoreId, Json.writeMap(payment.metadata),
                        payment.createdBy, payment.createdTime, payment.modifiedBy, payment.modifiedTime)
                .update();
        for (PaymentDetailRow detail : details) {
            jdbc.sql("INSERT INTO payment_details_audit (id, row_hash, payment_details_id, payment_id, "
                            + "tenant_id, bill_id, business_service_code, total_amount_due, total_amount_paid, "
                            + "receipt_number, receipt_date, receipt_type, manual_receipt_number, "
                            + "manual_receipt_date, metadata, "
                            + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), RowHash.of(detail), detail.id, detail.paymentId,
                            detail.tenantId, detail.billId, detail.businessServiceCode, detail.totalAmountDue,
                            detail.totalAmountPaid, detail.receiptNumber, detail.receiptDate,
                            detail.receiptType.name(), detail.manualReceiptNumber, detail.manualReceiptDate,
                            Json.writeMap(detail.metadata),
                            detail.createdBy, detail.createdTime, detail.modifiedBy, detail.modifiedTime)
                    .update();
        }
    }
}
