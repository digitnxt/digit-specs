package org.digit.billing.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.digit.billing.entity.TaxHeadRow;
import org.digit.billing.model.TaxHeadCategory;
import org.digit.billing.model.TaxHeadRequests;
import org.digit.billing.service.RowHash;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TaxHeadRepository {

    private static final String COLUMNS =
            "id, tenant_id, code, version, name, business_service_code, category, order_number, "
                    + "effective_from, effective_to, is_active, "
                    + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private final JdbcClient jdbc;
    private final RowMapper<TaxHeadRow> rowMapper = (rs, rowNum) -> map(rs);

    public TaxHeadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private TaxHeadRow map(ResultSet rs) throws SQLException {
        TaxHeadRow row = new TaxHeadRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.code = rs.getString("code");
        row.version = rs.getInt("version");
        row.name = rs.getString("name");
        row.businessServiceCode = rs.getString("business_service_code");
        row.category = TaxHeadCategory.valueOf(rs.getString("category"));
        row.orderNumber = rs.getInt("order_number");
        row.effectiveFrom = rs.getLong("effective_from");
        row.effectiveTo = rs.getObject("effective_to", Long.class);
        row.isActive = rs.getBoolean("is_active");
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    public void create(List<TaxHeadRow> rows) {
        for (TaxHeadRow row : rows) {
            jdbc.sql("INSERT INTO tax_heads (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(row.id, row.tenantId, row.code, row.version, row.name, row.businessServiceCode,
                            row.category.name(), row.orderNumber, row.effectiveFrom, row.effectiveTo,
                            row.isActive, row.createdBy, row.createdTime, row.modifiedBy, row.modifiedTime)
                    .update();
        }
    }

    public List<TaxHeadRow> search(TaxHeadRequests.Filters filters, String tenantId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM tax_heads WHERE tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (filters.code() != null) {
            sql.append(" AND code = :code");
            params.put("code", filters.code());
        }
        if (filters.category() != null) {
            sql.append(" AND category = :category");
            params.put("category", filters.category().name());
        }
        if (filters.businessServiceCode() != null) {
            sql.append(" AND business_service_code = :businessServiceCode");
            params.put("businessServiceCode", filters.businessServiceCode());
        }
        if (filters.isActive() != null) {
            sql.append(" AND is_active = :isActive");
            params.put("isActive", filters.isActive());
        }
        sql.append(" ORDER BY \"createdTime\" DESC LIMIT :limit OFFSET :offset");
        params.put("limit", filters.limit());
        params.put("offset", filters.offset());
        return jdbc.sql(sql.toString()).params(params).query(rowMapper).list();
    }

    public Optional<TaxHeadRow> getByCode(String code, String tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tax_heads WHERE code = ? AND tenant_id = ? LIMIT 1")
                .params(code, tenantId)
                .query(rowMapper)
                .optional();
    }

    public Optional<TaxHeadRow> fetchForUpdate(String code, String tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tax_heads WHERE code = ? AND tenant_id = ? LIMIT 1 FOR UPDATE")
                .params(code, tenantId)
                .query(rowMapper)
                .optional();
    }

    public void update(TaxHeadRow row) {
        jdbc.sql("UPDATE tax_heads SET tenant_id = ?, code = ?, version = ?, name = ?, "
                        + "business_service_code = ?, category = ?, order_number = ?, effective_from = ?, "
                        + "effective_to = ?, is_active = ?, "
                        + "\"createdBy\" = ?, \"createdTime\" = ?, \"modifiedBy\" = ?, \"modifiedTime\" = ? "
                        + "WHERE id = ?")
                .params(row.tenantId, row.code, row.version, row.name, row.businessServiceCode,
                        row.category.name(), row.orderNumber, row.effectiveFrom, row.effectiveTo, row.isActive,
                        row.createdBy, row.createdTime, row.modifiedBy, row.modifiedTime, row.id)
                .update();
    }

    public void insertAudit(TaxHeadRow row) {
        jdbc.sql("INSERT INTO tax_heads_audit (id, row_hash, tax_head_id, tenant_id, code, version, name, "
                        + "business_service_code, category, order_number, effective_from, effective_to, is_active, "
                        + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), RowHash.of(row), row.id, row.tenantId, row.code, row.version,
                        row.name, row.businessServiceCode, row.category.name(), row.orderNumber,
                        row.effectiveFrom, row.effectiveTo, row.isActive,
                        row.createdBy, row.createdTime, row.modifiedBy, row.modifiedTime)
                .update();
    }

    public void deleteHard(UUID id) {
        jdbc.sql("DELETE FROM tax_heads WHERE id = ?").params(id).update();
    }

    public List<String> getExistingCodes(String tenantId, List<String> codes) {
        return jdbc.sql("SELECT code FROM tax_heads WHERE tenant_id = :tenantId AND code IN (:codes)")
                .param("tenantId", tenantId)
                .param("codes", codes)
                .query(String.class)
                .list();
    }

    /** code → order_number for ACTIVE tax heads. */
    public Map<String, Integer> getOrderNumbersByCodes(String tenantId, List<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new HashMap<>();
        jdbc.sql("SELECT code, order_number FROM tax_heads "
                        + "WHERE tenant_id = :tenantId AND code IN (:codes) AND is_active = true")
                .param("tenantId", tenantId)
                .param("codes", codes)
                .query((rs, n) -> result.put(rs.getString("code"), rs.getInt("order_number")))
                .list();
        return result;
    }

    public record OrderConflict(String businessServiceCode, int orderNumber, String code) {
    }

    public record OrderPair(String businessServiceCode, int orderNumber) {
    }

    public List<OrderConflict> getByBusinessServiceAndOrderPairs(String tenantId, List<OrderPair> pairs) {
        if (pairs.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT business_service_code, order_number, code FROM tax_heads WHERE tenant_id = ? AND (");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(business_service_code = ? AND order_number = ?)");
            params.add(pairs.get(i).businessServiceCode());
            params.add(pairs.get(i).orderNumber());
        }
        sql.append(")");
        return jdbc.sql(sql.toString())
                .params(params.toArray())
                .query((rs, n) -> new OrderConflict(rs.getString("business_service_code"),
                        rs.getInt("order_number"), rs.getString("code")))
                .list();
    }

    public boolean existsByBusinessServiceAndOrderExcludingId(String tenantId, String businessServiceCode,
                                                              int orderNumber, UUID excludeId) {
        Long count = jdbc.sql("SELECT count(*) FROM tax_heads WHERE tenant_id = ? "
                        + "AND business_service_code = ? AND order_number = ? AND id <> ?")
                .params(tenantId, businessServiceCode, orderNumber, excludeId)
                .query(Long.class)
                .single();
        return count > 0;
    }
}
