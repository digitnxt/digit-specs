package org.digit.billing.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.digit.billing.entity.BusinessServiceRow;
import org.digit.billing.model.BusinessServiceRequests;
import org.digit.billing.model.CollectionMode;
import org.digit.billing.model.Json;
import org.digit.billing.model.PaymentMode;
import org.digit.billing.service.RowHash;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/** SQL 1:1 with the Go GORM queries (DISCOVERY §3). Audit columns are quoted camelCase. */
@Repository
public class BusinessServiceRepository {

    private static final String COLUMNS =
            "id, tenant_id, code, version, name, collection_mode, allowed_payment_modes, "
                    + "bill_expiry_days, partial_payment_allowed, min_payable_amount, currency, "
                    + "rounding_rule_code, effective_from, effective_to, is_active, "
                    + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private static final TypeReference<List<PaymentMode>> PAYMENT_MODES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final RowMapper<BusinessServiceRow> rowMapper = (rs, rowNum) -> map(rs);

    public BusinessServiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private BusinessServiceRow map(ResultSet rs) throws SQLException {
        BusinessServiceRow row = new BusinessServiceRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.code = rs.getString("code");
        row.version = rs.getInt("version");
        row.name = rs.getString("name");
        row.collectionMode = CollectionMode.valueOf(rs.getString("collection_mode"));
        String modes = rs.getString("allowed_payment_modes");
        row.allowedPaymentModes = modes == null ? List.of() : Json.MAPPER.readValue(modes, PAYMENT_MODES);
        row.billExpiryDays = rs.getObject("bill_expiry_days", Integer.class);
        row.partialPaymentAllowed = rs.getBoolean("partial_payment_allowed");
        row.minPayableAmount = Db.dec(rs, "min_payable_amount");
        row.currency = rs.getString("currency");
        row.roundingRuleCode = rs.getString("rounding_rule_code");
        row.effectiveFrom = rs.getLong("effective_from");
        row.effectiveTo = rs.getObject("effective_to", Long.class);
        row.isActive = rs.getBoolean("is_active");
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    public void create(List<BusinessServiceRow> rows) {
        for (BusinessServiceRow row : rows) {
            jdbc.sql("INSERT INTO business_services (" + COLUMNS + ") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(row.id, row.tenantId, row.code, row.version, row.name,
                            row.collectionMode.name(), Json.MAPPER.writeValueAsString(row.allowedPaymentModes),
                            row.billExpiryDays, row.partialPaymentAllowed, row.minPayableAmount,
                            row.currency, row.roundingRuleCode, row.effectiveFrom, row.effectiveTo,
                            row.isActive, row.createdBy, row.createdTime, row.modifiedBy, row.modifiedTime)
                    .update();
        }
    }

    public List<BusinessServiceRow> search(BusinessServiceRequests.Filters filters, String tenantId) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLUMNS + " FROM business_services WHERE tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (filters.code() != null) {
            sql.append(" AND code = :code");
            params.put("code", filters.code());
        }
        if (filters.isActive() != null) {
            sql.append(" AND is_active = :isActive");
            params.put("isActive", filters.isActive());
        }
        if (filters.effectiveOn() != null) {
            sql.append(" AND effective_from <= :effectiveOn AND (effective_to IS NULL OR effective_to > :effectiveOn)");
            params.put("effectiveOn", filters.effectiveOn());
        }
        sql.append(" ORDER BY \"createdTime\" DESC LIMIT :limit OFFSET :offset");
        params.put("limit", filters.limit());
        params.put("offset", filters.offset());
        return jdbc.sql(sql.toString()).params(params).query(rowMapper).list();
    }

    public Optional<BusinessServiceRow> getByCode(String code, String tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM business_services WHERE code = ? AND tenant_id = ? LIMIT 1")
                .params(code, tenantId)
                .query(rowMapper)
                .optional();
    }

    public Optional<BusinessServiceRow> fetchForUpdate(String code, String tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM business_services "
                        + "WHERE code = ? AND tenant_id = ? LIMIT 1 FOR UPDATE")
                .params(code, tenantId)
                .query(rowMapper)
                .optional();
    }

    /** Full-column update (Go: Select("*").Updates). */
    public void update(BusinessServiceRow row) {
        jdbc.sql("UPDATE business_services SET tenant_id = ?, code = ?, version = ?, name = ?, "
                        + "collection_mode = ?, allowed_payment_modes = ?::jsonb, bill_expiry_days = ?, "
                        + "partial_payment_allowed = ?, min_payable_amount = ?, currency = ?, "
                        + "rounding_rule_code = ?, effective_from = ?, effective_to = ?, is_active = ?, "
                        + "\"createdBy\" = ?, \"createdTime\" = ?, \"modifiedBy\" = ?, \"modifiedTime\" = ? "
                        + "WHERE id = ?")
                .params(row.tenantId, row.code, row.version, row.name, row.collectionMode.name(),
                        Json.MAPPER.writeValueAsString(row.allowedPaymentModes), row.billExpiryDays,
                        row.partialPaymentAllowed, row.minPayableAmount, row.currency,
                        row.roundingRuleCode, row.effectiveFrom, row.effectiveTo, row.isActive,
                        row.createdBy, row.createdTime, row.modifiedBy, row.modifiedTime, row.id)
                .update();
    }

    public void insertAudit(BusinessServiceRow row) {
        jdbc.sql("INSERT INTO business_services_audit (id, row_hash, business_service_id, tenant_id, code, "
                        + "version, name, collection_mode, allowed_payment_modes, bill_expiry_days, "
                        + "partial_payment_allowed, min_payable_amount, currency, rounding_rule_code, "
                        + "effective_from, effective_to, is_active, "
                        + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), RowHash.of(row), row.id, row.tenantId, row.code,
                        row.version, row.name, row.collectionMode.name(),
                        Json.MAPPER.writeValueAsString(row.allowedPaymentModes), row.billExpiryDays,
                        row.partialPaymentAllowed, row.minPayableAmount, row.currency, row.roundingRuleCode,
                        row.effectiveFrom, row.effectiveTo, row.isActive,
                        row.createdBy, row.createdTime, row.modifiedBy, row.modifiedTime)
                .update();
    }

    public void deleteHard(UUID id) {
        jdbc.sql("DELETE FROM business_services WHERE id = ?").params(id).update();
    }

    public List<String> getExistingCodes(String tenantId, List<String> codes) {
        return jdbc.sql("SELECT code FROM business_services WHERE tenant_id = :tenantId AND code IN (:codes)")
                .param("tenantId", tenantId)
                .param("codes", codes)
                .query(String.class)
                .list();
    }

    public List<BusinessServiceRow> getActiveByCodes(String tenantId, List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM business_services "
                        + "WHERE tenant_id = :tenantId AND code IN (:codes) AND is_active = true")
                .param("tenantId", tenantId)
                .param("codes", codes)
                .query(rowMapper)
                .list();
    }
}
