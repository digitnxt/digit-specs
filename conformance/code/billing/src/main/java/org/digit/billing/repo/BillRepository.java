package org.digit.billing.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.digit.billing.entity.BillRow;
import org.digit.billing.entity.BillRow.BillAccountDetailRow;
import org.digit.billing.entity.BillRow.BillDetailRow;
import org.digit.billing.model.BillRequests;
import org.digit.billing.model.BillStatus;
import org.digit.billing.model.Json;
import org.digit.billing.service.RowHash;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BillRepository {

    private static final String BILL_COLUMNS =
            "id, tenant_id, business_service_code, consumer_code, payer_id, payer_name, payer_address, "
                    + "payer_mobile_number, payer_email, bill_number, bill_issue_at, bill_expiry_at, status, "
                    + "total_amount, total_collected_amount, metadata, "
                    + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private static final String DETAIL_COLUMNS =
            "id, tenant_id, bill_id, demand_id, amount, amount_paid, period_from, period_to, metadata, "
                    + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private static final String ACCOUNT_COLUMNS =
            "id, tenant_id, bill_detail_id, line_item_id, tax_head_code, order_number, amount, "
                    + "adjusted_amount, metadata, \"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private final JdbcClient jdbc;
    private final RowMapper<BillRow> billMapper = (rs, rowNum) -> mapBill(rs);
    private final RowMapper<BillDetailRow> detailMapper = (rs, rowNum) -> mapDetail(rs);
    private final RowMapper<BillAccountDetailRow> accountMapper = (rs, rowNum) -> mapAccount(rs);

    public BillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private BillRow mapBill(ResultSet rs) throws SQLException {
        BillRow row = new BillRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.businessServiceCode = rs.getString("business_service_code");
        row.consumerCode = rs.getString("consumer_code");
        row.payerId = rs.getString("payer_id");
        row.payerName = rs.getString("payer_name");
        row.payerAddress = rs.getString("payer_address");
        row.payerMobileNumber = rs.getString("payer_mobile_number");
        row.payerEmail = rs.getString("payer_email");
        row.billNumber = rs.getString("bill_number");
        row.billIssueAt = rs.getLong("bill_issue_at");
        row.billExpiryAt = rs.getObject("bill_expiry_at", Long.class);
        row.status = BillStatus.valueOf(rs.getString("status"));
        row.totalAmount = Db.dec(rs, "total_amount");
        row.totalCollectedAmount = Db.dec(rs, "total_collected_amount");
        row.metadata = Json.readMap(rs.getString("metadata"));
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    private BillDetailRow mapDetail(ResultSet rs) throws SQLException {
        BillDetailRow row = new BillDetailRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.billId = rs.getObject("bill_id", UUID.class);
        row.demandId = rs.getObject("demand_id", UUID.class);
        row.amount = Db.dec(rs, "amount");
        row.amountPaid = Db.dec(rs, "amount_paid");
        row.periodFrom = rs.getLong("period_from");
        row.periodTo = rs.getLong("period_to");
        row.metadata = Json.readMap(rs.getString("metadata"));
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    private BillAccountDetailRow mapAccount(ResultSet rs) throws SQLException {
        BillAccountDetailRow row = new BillAccountDetailRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.billDetailId = rs.getObject("bill_detail_id", UUID.class);
        row.lineItemId = rs.getObject("line_item_id", UUID.class);
        row.taxHeadCode = rs.getString("tax_head_code");
        row.orderNumber = rs.getInt("order_number");
        row.amount = Db.dec(rs, "amount");
        row.adjustedAmount = Db.dec(rs, "adjusted_amount");
        row.metadata = Json.readMap(rs.getString("metadata"));
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    public void create(BillRow bill, List<BillDetailRow> details, List<BillAccountDetailRow> accounts) {
        bulkInsertAll(List.of(bill), details, accounts);
    }

    public void bulkInsertAll(List<BillRow> bills, List<BillDetailRow> details,
                              List<BillAccountDetailRow> accounts) {
        for (BillRow bill : bills) {
            jdbc.sql("INSERT INTO bills (" + BILL_COLUMNS + ") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(bill.id, bill.tenantId, bill.businessServiceCode, bill.consumerCode,
                            bill.payerId, bill.payerName, bill.payerAddress, bill.payerMobileNumber,
                            bill.payerEmail, bill.billNumber, bill.billIssueAt, bill.billExpiryAt,
                            bill.status.name(), bill.totalAmount, bill.totalCollectedAmount,
                            Json.writeMap(bill.metadata), bill.createdBy, bill.createdTime,
                            bill.modifiedBy, bill.modifiedTime)
                    .update();
        }
        for (BillDetailRow detail : details) {
            jdbc.sql("INSERT INTO bill_details (" + DETAIL_COLUMNS + ") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(detail.id, detail.tenantId, detail.billId, detail.demandId, detail.amount,
                            detail.amountPaid, detail.periodFrom, detail.periodTo, Json.writeMap(detail.metadata),
                            detail.createdBy, detail.createdTime, detail.modifiedBy, detail.modifiedTime)
                    .update();
        }
        for (BillAccountDetailRow account : accounts) {
            jdbc.sql("INSERT INTO bill_account_details (" + ACCOUNT_COLUMNS + ") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(account.id, account.tenantId, account.billDetailId, account.lineItemId,
                            account.taxHeadCode, account.orderNumber, account.amount, account.adjustedAmount,
                            Json.writeMap(account.metadata), account.createdBy, account.createdTime,
                            account.modifiedBy, account.modifiedTime)
                    .update();
        }
    }

    public record BillTree(List<BillRow> bills, List<BillDetailRow> details,
                           Map<UUID, List<BillAccountDetailRow>> accountsByDetail) {
    }

    private BillTree loadTree(List<BillRow> bills, String tenantId) {
        if (bills.isEmpty()) {
            return new BillTree(bills, List.of(), Map.of());
        }
        List<UUID> billIds = bills.stream().map(b -> b.id).toList();
        List<BillDetailRow> details = jdbc.sql("SELECT " + DETAIL_COLUMNS + " FROM bill_details "
                        + "WHERE bill_id IN (:billIds) AND tenant_id = :tenantId")
                .param("billIds", billIds)
                .param("tenantId", tenantId)
                .query(detailMapper)
                .list();
        Map<UUID, List<BillAccountDetailRow>> accountsByDetail = new LinkedHashMap<>();
        if (!details.isEmpty()) {
            List<UUID> detailIds = details.stream().map(d -> d.id).toList();
            List<BillAccountDetailRow> accounts = jdbc.sql("SELECT " + ACCOUNT_COLUMNS
                            + " FROM bill_account_details WHERE bill_detail_id IN (:detailIds) "
                            + "AND tenant_id = :tenantId ORDER BY order_number ASC")
                    .param("detailIds", detailIds)
                    .param("tenantId", tenantId)
                    .query(accountMapper)
                    .list();
            for (BillAccountDetailRow account : accounts) {
                accountsByDetail.computeIfAbsent(account.billDetailId, k -> new ArrayList<>()).add(account);
            }
        }
        return new BillTree(bills, details, accountsByDetail);
    }

    public BillTree getByIds(String tenantId, List<UUID> billIds) {
        return getByIds(tenantId, billIds, "");
    }

    public BillTree getByIdsForUpdate(String tenantId, List<UUID> billIds) {
        return getByIds(tenantId, billIds, " FOR UPDATE");
    }

    private BillTree getByIds(String tenantId, List<UUID> billIds, String lockClause) {
        if (billIds.isEmpty()) {
            return new BillTree(List.of(), List.of(), Map.of());
        }
        List<BillRow> bills = jdbc.sql("SELECT " + BILL_COLUMNS + " FROM bills "
                        + "WHERE id IN (:billIds) AND tenant_id = :tenantId" + lockClause)
                .param("billIds", billIds)
                .param("tenantId", tenantId)
                .query(billMapper)
                .list();
        return loadTree(bills, tenantId);
    }

    public BillTree search(BillRequests.Filters filters, String tenantId) {
        StringBuilder sql = new StringBuilder("SELECT " + BILL_COLUMNS + " FROM bills WHERE tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (filters.businessServiceCode() != null) {
            sql.append(" AND business_service_code = :businessServiceCode");
            params.put("businessServiceCode", filters.businessServiceCode());
        }
        if (filters.consumerCodes() != null && !filters.consumerCodes().isEmpty()) {
            sql.append(" AND consumer_code IN (:consumerCodes)");
            params.put("consumerCodes", filters.consumerCodes());
        }
        if (filters.billNumbers() != null && !filters.billNumbers().isEmpty()) {
            sql.append(" AND bill_number IN (:billNumbers)");
            params.put("billNumbers", filters.billNumbers());
        }
        if (filters.billIds() != null && !filters.billIds().isEmpty()) {
            sql.append(" AND id IN (:billIds)");
            params.put("billIds", filters.billIds());
        }
        if (filters.status() != null) {
            sql.append(" AND status = :status");
            params.put("status", filters.status().name());
        }
        if (filters.mobileNumber() != null) {
            sql.append(" AND payer_mobile_number = :mobileNumber");
            params.put("mobileNumber", filters.mobileNumber());
        }
        if (filters.email() != null) {
            sql.append(" AND payer_email = :email");
            params.put("email", filters.email());
        }
        sql.append(" ORDER BY \"createdTime\" DESC LIMIT :limit OFFSET :offset");
        params.put("limit", filters.limit());
        params.put("offset", filters.offset());

        List<BillRow> bills = jdbc.sql(sql.toString()).params(params).query(billMapper).list();
        return loadTree(bills, tenantId);
    }

    /** Cancel path: lock the single ACTIVE bill (uniq_active_bill guarantees ≤1). */
    public Optional<BillRow> fetchActiveBillForUpdate(String tenantId, String businessServiceCode,
                                                      String consumerCode) {
        return jdbc.sql("SELECT " + BILL_COLUMNS + " FROM bills WHERE tenant_id = ? "
                        + "AND business_service_code = ? AND consumer_code = ? AND status = 'ACTIVE' "
                        + "LIMIT 1 FOR UPDATE")
                .params(tenantId, businessServiceCode, consumerCode)
                .query(billMapper)
                .optional();
    }

    /** Generate path: lock the latest ACTIVE bill and load its whole tree. */
    public Optional<BillTree> getActiveBillForUpdate(String tenantId, String businessServiceCode,
                                                     String consumerCode) {
        Optional<BillRow> bill = jdbc.sql("SELECT " + BILL_COLUMNS + " FROM bills WHERE tenant_id = ? "
                        + "AND business_service_code = ? AND consumer_code = ? AND status = 'ACTIVE' "
                        + "ORDER BY \"createdTime\" DESC LIMIT 1 FOR UPDATE")
                .params(tenantId, businessServiceCode, consumerCode)
                .query(billMapper)
                .optional();
        return bill.map(b -> loadTree(List.of(b), tenantId));
    }

    public List<BillRow> getActiveBillsByConsumers(String tenantId, String businessServiceCode,
                                                   List<String> consumerCodes) {
        if (consumerCodes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + BILL_COLUMNS + " FROM bills WHERE tenant_id = :tenantId "
                        + "AND business_service_code = :bsCode AND consumer_code IN (:consumers) "
                        + "AND status = 'ACTIVE'")
                .param("tenantId", tenantId)
                .param("bsCode", businessServiceCode)
                .param("consumers", consumerCodes)
                .query(billMapper)
                .list();
    }

    /** Field-map status update (Go UpdateStatusByID: status + modifiedBy/Time). */
    public void updateStatusById(UUID billId, BillStatus status, String userId, long now) {
        jdbc.sql("UPDATE bills SET status = ?, \"modifiedBy\" = ?, \"modifiedTime\" = ? WHERE id = ?")
                .params(status.name(), userId, now, billId)
                .update();
    }

    /** Q9: cancel now also merges the request metadata into the bill. */
    public void cancelById(UUID billId, String metadataJson, String userId, long now) {
        jdbc.sql("UPDATE bills SET status = 'CANCELLED', metadata = ?::jsonb, "
                        + "\"modifiedBy\" = ?, \"modifiedTime\" = ? WHERE id = ?")
                .params(metadataJson, userId, now, billId)
                .update();
    }

    public void bulkExpireBills(List<UUID> billIds, String tenantId, String userId, long now) {
        if (billIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE bills SET status = 'EXPIRED', \"modifiedBy\" = :userId, \"modifiedTime\" = :now "
                        + "WHERE tenant_id = :tenantId AND id IN (:ids) AND status = 'ACTIVE'")
                .param("userId", userId)
                .param("now", now)
                .param("tenantId", tenantId)
                .param("ids", billIds)
                .update();
    }

    /** HR-4: explicit-column updates replacing GORM's non-zero-field Updates. */
    public void updateFullBillAggregate(BillRow bill, List<BillDetailRow> details,
                                        List<BillAccountDetailRow> accounts) {
        jdbc.sql("UPDATE bills SET status = ?, total_amount = ?, total_collected_amount = ?, "
                        + "\"modifiedBy\" = ?, \"modifiedTime\" = ? WHERE id = ?")
                .params(bill.status.name(), bill.totalAmount, bill.totalCollectedAmount,
                        bill.modifiedBy, bill.modifiedTime, bill.id)
                .update();
        for (BillDetailRow detail : details) {
            jdbc.sql("UPDATE bill_details SET amount = ?, amount_paid = ?, "
                            + "\"modifiedBy\" = ?, \"modifiedTime\" = ? WHERE id = ?")
                    .params(detail.amount, detail.amountPaid, detail.modifiedBy, detail.modifiedTime, detail.id)
                    .update();
        }
        for (BillAccountDetailRow account : accounts) {
            jdbc.sql("UPDATE bill_account_details SET amount = ?, adjusted_amount = ?, "
                            + "\"modifiedBy\" = ?, \"modifiedTime\" = ? WHERE id = ?")
                    .params(account.amount, account.adjustedAmount, account.modifiedBy,
                            account.modifiedTime, account.id)
                    .update();
        }
    }

    public void insertAudit(BillRow bill, List<BillDetailRow> details, List<BillAccountDetailRow> accounts) {
        insertAuditBulk(List.of(bill), details, accounts);
    }

    public void insertAuditBulk(List<BillRow> bills, List<BillDetailRow> details,
                                List<BillAccountDetailRow> accounts) {
        for (BillRow bill : bills) {
            jdbc.sql("INSERT INTO bills_audit (id, row_hash, bill_id, tenant_id, business_service_code, "
                            + "consumer_code, payer_id, payer_name, payer_address, payer_mobile_number, "
                            + "payer_email, bill_number, bill_issue_at, bill_expiry_at, status, total_amount, "
                            + "total_collected_amount, metadata, "
                            + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), RowHash.of(bill), bill.id, bill.tenantId,
                            bill.businessServiceCode, bill.consumerCode, bill.payerId, bill.payerName,
                            bill.payerAddress, bill.payerMobileNumber, bill.payerEmail, bill.billNumber,
                            bill.billIssueAt, bill.billExpiryAt, bill.status.name(), bill.totalAmount,
                            bill.totalCollectedAmount, Json.writeMap(bill.metadata),
                            bill.createdBy, bill.createdTime, bill.modifiedBy, bill.modifiedTime)
                    .update();
        }
        for (BillDetailRow detail : details) {
            jdbc.sql("INSERT INTO bill_details_audit (id, row_hash, bill_detail_id, tenant_id, bill_id, "
                            + "demand_id, amount, amount_paid, period_from, period_to, metadata, "
                            + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), RowHash.of(detail), detail.id, detail.tenantId, detail.billId,
                            detail.demandId, detail.amount, detail.amountPaid, detail.periodFrom,
                            detail.periodTo, Json.writeMap(detail.metadata),
                            detail.createdBy, detail.createdTime, detail.modifiedBy, detail.modifiedTime)
                    .update();
        }
        for (BillAccountDetailRow account : accounts) {
            jdbc.sql("INSERT INTO bill_account_details_audit (id, row_hash, bill_account_detail_id, "
                            + "tenant_id, bill_detail_id, line_item_id, tax_head_code, order_number, amount, "
                            + "adjusted_amount, metadata, "
                            + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), RowHash.of(account), account.id, account.tenantId,
                            account.billDetailId, account.lineItemId, account.taxHeadCode,
                            account.orderNumber, account.amount, account.adjustedAmount,
                            Json.writeMap(account.metadata),
                            account.createdBy, account.createdTime, account.modifiedBy, account.modifiedTime)
                    .update();
        }
    }
}
