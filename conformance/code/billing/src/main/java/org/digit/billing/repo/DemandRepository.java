package org.digit.billing.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.digit.billing.entity.DemandRow;
import org.digit.billing.entity.DemandRow.LineItemRow;
import org.digit.billing.model.DemandRequests;
import org.digit.billing.model.DemandStatus;
import org.digit.billing.model.Json;
import org.digit.billing.service.RowHash;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DemandRepository {

    private static final String DEMAND_COLUMNS =
            "id, tenant_id, business_service_code, period_from, period_to, consumer_code, "
                    + "bill_expiry_days, payer, arrear_demand_ids, status, total_amount, "
                    + "total_collected_amount, is_demand_paid, metadata, version, "
                    + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    private static final String ITEM_COLUMNS =
            "id, tenant_id, demand_id, tax_head_code, amount, collected_amount, metadata, "
                    + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\"";

    /** JSONB columns for the dynamic field-map UPDATE. */
    private static final Set<String> JSONB_FIELDS = Set.of("payer", "metadata");

    private final JdbcClient jdbc;
    private final RowMapper<DemandRow> demandMapper = (rs, rowNum) -> mapDemand(rs);
    private final RowMapper<LineItemRow> itemMapper = (rs, rowNum) -> mapItem(rs);

    public DemandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private DemandRow mapDemand(ResultSet rs) throws SQLException {
        DemandRow row = new DemandRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.businessServiceCode = rs.getString("business_service_code");
        row.periodFrom = rs.getLong("period_from");
        row.periodTo = rs.getLong("period_to");
        row.consumerCode = rs.getString("consumer_code");
        row.billExpiryDays = rs.getObject("bill_expiry_days", Integer.class);
        row.payer = Json.readArray(rs.getString("payer"));
        row.arrearDemandIds = Json.readArray(rs.getString("arrear_demand_ids"));
        row.status = DemandStatus.valueOf(rs.getString("status"));
        row.totalAmount = Db.dec(rs, "total_amount");
        row.totalCollectedAmount = Db.dec(rs, "total_collected_amount");
        row.isDemandPaid = rs.getBoolean("is_demand_paid");
        row.metadata = Json.readMap(rs.getString("metadata"));
        row.version = rs.getInt("version");
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    private LineItemRow mapItem(ResultSet rs) throws SQLException {
        LineItemRow row = new LineItemRow();
        row.id = rs.getObject("id", UUID.class);
        row.tenantId = rs.getString("tenant_id");
        row.demandId = rs.getObject("demand_id", UUID.class);
        row.taxHeadCode = rs.getString("tax_head_code");
        row.amount = Db.dec(rs, "amount");
        row.collectedAmount = Db.dec(rs, "collected_amount");
        row.metadata = Json.readMap(rs.getString("metadata"));
        row.createdBy = rs.getString("createdBy");
        row.createdTime = rs.getLong("createdTime");
        row.modifiedBy = rs.getString("modifiedBy");
        row.modifiedTime = rs.getLong("modifiedTime");
        return row;
    }

    private static boolean isPeriodConflict(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql
                    && ("23P01".equals(sql.getSQLState())
                        || (sql.getMessage() != null && sql.getMessage().contains("no_overlapping_demands")))) {
                return true;
            }
        }
        return false;
    }

    /** Runs an insert/update, translating the exclusion-constraint violation to DemandPeriodConflictException. */
    private static void guardingPeriodConflict(Runnable update) {
        try {
            update.run();
        } catch (DataIntegrityViolationException e) {
            if (isPeriodConflict(e)) {
                throw new DemandPeriodConflictException(e);
            }
            throw e;
        }
    }

    public void create(DemandRow demand, List<LineItemRow> items) {
        guardingPeriodConflict(() ->
                jdbc.sql("INSERT INTO demands (" + DEMAND_COLUMNS + ") VALUES "
                                + "(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)")
                        .params(demand.id, demand.tenantId, demand.businessServiceCode, demand.periodFrom,
                                demand.periodTo, demand.consumerCode, demand.billExpiryDays,
                                Json.writeArray(demand.payer), Json.writeArray(demand.arrearDemandIds),
                                demand.status.name(), demand.totalAmount, demand.totalCollectedAmount,
                                demand.isDemandPaid, Json.writeMap(demand.metadata), demand.version,
                                demand.createdBy, demand.createdTime, demand.modifiedBy, demand.modifiedTime)
                        .update());
        insertLineItems(items);
    }

    /** Full replace of the demand row (Go: Select("*").Updates) + line-item swap. */
    public void replace(DemandRow demand, List<LineItemRow> items) {
        guardingPeriodConflict(() ->
                jdbc.sql("UPDATE demands SET business_service_code = ?, period_from = ?, period_to = ?, "
                                + "consumer_code = ?, bill_expiry_days = ?, payer = ?::jsonb, "
                                + "arrear_demand_ids = ?::jsonb, status = ?, total_amount = ?, "
                                + "total_collected_amount = ?, is_demand_paid = ?, metadata = ?::jsonb, "
                                + "version = ?, \"createdBy\" = ?, \"createdTime\" = ?, \"modifiedBy\" = ?, "
                                + "\"modifiedTime\" = ? WHERE id = ? AND tenant_id = ?")
                        .params(demand.businessServiceCode, demand.periodFrom, demand.periodTo,
                                demand.consumerCode, demand.billExpiryDays, Json.writeArray(demand.payer),
                                Json.writeArray(demand.arrearDemandIds), demand.status.name(),
                                demand.totalAmount, demand.totalCollectedAmount, demand.isDemandPaid,
                                Json.writeMap(demand.metadata), demand.version, demand.createdBy,
                                demand.createdTime, demand.modifiedBy, demand.modifiedTime,
                                demand.id, demand.tenantId)
                        .update());
        deleteLineItems(demand.id);
        insertLineItems(items);
    }

    public void insertLineItems(List<LineItemRow> items) {
        for (LineItemRow item : items) {
            jdbc.sql("INSERT INTO line_items (" + ITEM_COLUMNS + ") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(item.id, item.tenantId, item.demandId, item.taxHeadCode, item.amount,
                            item.collectedAmount, Json.writeMap(item.metadata),
                            item.createdBy, item.createdTime, item.modifiedBy, item.modifiedTime)
                    .update();
        }
    }

    public void deleteLineItems(UUID demandId) {
        jdbc.sql("DELETE FROM line_items WHERE demand_id = ?").params(demandId).update();
    }

    public void insertAudit(DemandRow demand, List<LineItemRow> itemAudits) {
        jdbc.sql("INSERT INTO demands_audit (id, row_hash, demand_id, tenant_id, business_service_code, "
                        + "period_from, period_to, consumer_code, bill_expiry_days, payer, arrear_demand_ids, "
                        + "status, total_amount, total_collected_amount, is_demand_paid, metadata, version, "
                        + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), RowHash.of(demand), demand.id, demand.tenantId,
                        demand.businessServiceCode, demand.periodFrom, demand.periodTo, demand.consumerCode,
                        demand.billExpiryDays, Json.writeArray(demand.payer),
                        Json.writeArray(demand.arrearDemandIds), demand.status.name(), demand.totalAmount,
                        demand.totalCollectedAmount, demand.isDemandPaid, Json.writeMap(demand.metadata),
                        demand.version, demand.createdBy, demand.createdTime, demand.modifiedBy,
                        demand.modifiedTime)
                .update();
        insertLineItemAudits(itemAudits);
    }

    public void insertAuditBulk(List<DemandRow> demandAudits, List<LineItemRow> itemAudits) {
        for (DemandRow demand : demandAudits) {
            insertAudit(demand, List.of());
        }
        insertLineItemAudits(itemAudits);
    }

    private void insertLineItemAudits(List<LineItemRow> itemAudits) {
        for (LineItemRow item : itemAudits) {
            jdbc.sql("INSERT INTO line_items_audit (id, row_hash, line_item_id, tenant_id, demand_id, "
                            + "tax_head_code, amount, collected_amount, metadata, "
                            + "\"createdBy\", \"createdTime\", \"modifiedBy\", \"modifiedTime\") VALUES "
                            + "(?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), RowHash.of(item), item.id, item.tenantId, item.demandId,
                            item.taxHeadCode, item.amount, item.collectedAmount, Json.writeMap(item.metadata),
                            item.createdBy, item.createdTime, item.modifiedBy, item.modifiedTime)
                    .update();
        }
    }

    public Optional<DemandRow> getById(UUID id, String tenantId) {
        return jdbc.sql("SELECT " + DEMAND_COLUMNS + " FROM demands WHERE id = ? AND tenant_id = ? LIMIT 1")
                .params(id, tenantId)
                .query(demandMapper)
                .optional();
    }

    public List<LineItemRow> getLineItems(UUID demandId) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM line_items WHERE demand_id = ? "
                        + "ORDER BY \"createdTime\" ASC")
                .params(demandId)
                .query(itemMapper)
                .list();
    }

    public Map<UUID, List<LineItemRow>> getLineItemsByDemandIds(List<UUID> demandIds) {
        Map<UUID, List<LineItemRow>> byDemand = new LinkedHashMap<>();
        if (demandIds.isEmpty()) {
            return byDemand;
        }
        List<LineItemRow> items = jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM line_items "
                        + "WHERE demand_id IN (:ids) ORDER BY \"createdTime\" ASC")
                .param("ids", demandIds)
                .query(itemMapper)
                .list();
        for (LineItemRow item : items) {
            byDemand.computeIfAbsent(item.demandId, k -> new ArrayList<>()).add(item);
        }
        return byDemand;
    }

    public List<DemandRow> search(DemandRequests.Filters filters, String tenantId) {
        StringBuilder sql = new StringBuilder("SELECT " + DEMAND_COLUMNS + " FROM demands WHERE tenant_id = :tenantId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (filters.businessServiceCode() != null) {
            sql.append(" AND business_service_code = :businessServiceCode");
            params.put("businessServiceCode", filters.businessServiceCode());
        }
        if (filters.consumerCode() != null) {
            sql.append(" AND consumer_code = :consumerCode");
            params.put("consumerCode", filters.consumerCode());
        }
        if (filters.status() != null) {
            sql.append(" AND status = :status");
            params.put("status", filters.status().name());
        }
        if (filters.createdFrom() != null) {
            sql.append(" AND \"createdTime\" >= :createdFrom");
            params.put("createdFrom", filters.createdFrom());
        }
        if (filters.createdTo() != null) {
            sql.append(" AND \"createdTime\" <= :createdTo");
            params.put("createdTo", filters.createdTo());
        }
        sql.append(" ORDER BY \"createdTime\" DESC LIMIT :limit OFFSET :offset");
        params.put("limit", filters.limit());
        params.put("offset", filters.offset());
        return jdbc.sql(sql.toString()).params(params).query(demandMapper).list();
    }

    public Optional<DemandRow> fetchForUpdate(UUID id, String tenantId) {
        return jdbc.sql("SELECT " + DEMAND_COLUMNS + " FROM demands WHERE id = ? AND tenant_id = ? "
                        + "LIMIT 1 FOR UPDATE")
                .params(id, tenantId)
                .query(demandMapper)
                .optional();
    }

    public Optional<DemandRow> getLatestOpenDemandForUpdate(String tenantId, String businessServiceCode,
                                                            String consumerCode) {
        return jdbc.sql("SELECT " + DEMAND_COLUMNS + " FROM demands WHERE tenant_id = :tenantId "
                        + "AND business_service_code = :bsCode AND consumer_code = :consumerCode "
                        + "AND status IN (:statuses) ORDER BY period_to DESC LIMIT 1 FOR UPDATE")
                .param("tenantId", tenantId)
                .param("bsCode", businessServiceCode)
                .param("consumerCode", consumerCode)
                .param("statuses", List.of("ACTIVE", "FROZEN", "PARTIALLY_PAID"))
                .query(demandMapper)
                .optional();
    }

    public List<DemandRow> getByIdsForUpdate(String tenantId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + DEMAND_COLUMNS + " FROM demands "
                        + "WHERE tenant_id = :tenantId AND id IN (:ids) FOR UPDATE")
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query(demandMapper)
                .list();
    }

    public List<DemandRow> getDemandsByConsumersForUpdate(String tenantId, String businessServiceCode,
                                                          List<String> consumerCodes,
                                                          List<DemandStatus> statuses) {
        return jdbc.sql("SELECT " + DEMAND_COLUMNS + " FROM demands WHERE tenant_id = :tenantId "
                        + "AND business_service_code = :bsCode AND consumer_code IN (:consumers) "
                        + "AND status IN (:statuses) ORDER BY consumer_code, period_to DESC FOR UPDATE")
                .param("tenantId", tenantId)
                .param("bsCode", businessServiceCode)
                .param("consumers", consumerCodes)
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .query(demandMapper)
                .list();
    }

    /**
     * Dynamic field-map UPDATE (Go UpdateFields). Values for payer/metadata must be
     * pre-serialized JSON strings; enums pass their name.
     */
    public void updateFields(UUID id, String tenantId, Map<String, Object> updates) {
        StringJoiner assignments = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String column = entry.getKey();
            boolean camel = !column.equals(column.toLowerCase());
            assignments.add((camel ? "\"" + column + "\"" : column)
                    + (JSONB_FIELDS.contains(column) ? " = ?::jsonb" : " = ?"));
            Object value = entry.getValue();
            params.add(value instanceof Enum<?> e ? e.name() : value);
        }
        params.add(id);
        params.add(tenantId);
        guardingPeriodConflict(() ->
                jdbc.sql("UPDATE demands SET " + assignments + " WHERE id = ? AND tenant_id = ?")
                        .params(params.toArray())
                        .update());
    }

    /** HR-4: full-column update replacing GORM's non-zero-field Updates (reachability argument in PORT_PLAN §5). */
    public void updateBatch(List<DemandRow> demands, List<LineItemRow> items) {
        for (DemandRow demand : demands) {
            replaceDemandRowOnly(demand);
        }
        for (LineItemRow item : items) {
            jdbc.sql("UPDATE line_items SET tax_head_code = ?, amount = ?, collected_amount = ?, "
                            + "metadata = ?::jsonb, \"modifiedBy\" = ?, \"modifiedTime\" = ? "
                            + "WHERE id = ? AND tenant_id = ?")
                    .params(item.taxHeadCode, item.amount, item.collectedAmount, Json.writeMap(item.metadata),
                            item.modifiedBy, item.modifiedTime, item.id, item.tenantId)
                    .update();
        }
    }

    private void replaceDemandRowOnly(DemandRow demand) {
        jdbc.sql("UPDATE demands SET status = ?, total_amount = ?, total_collected_amount = ?, "
                        + "is_demand_paid = ?, version = ?, \"modifiedBy\" = ?, \"modifiedTime\" = ? "
                        + "WHERE id = ? AND tenant_id = ?")
                .params(demand.status.name(), demand.totalAmount, demand.totalCollectedAmount,
                        demand.isDemandPaid, demand.version, demand.modifiedBy, demand.modifiedTime,
                        demand.id, demand.tenantId)
                .update();
    }

    public void bulkFreezeDemands(List<UUID> demandIds, String tenantId, String userId, long now) {
        if (demandIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE demands SET status = 'FROZEN', version = version + 1, "
                        + "\"modifiedBy\" = :userId, \"modifiedTime\" = :now "
                        + "WHERE tenant_id = :tenantId AND id IN (:ids) AND status = 'ACTIVE'")
                .param("userId", userId)
                .param("now", now)
                .param("tenantId", tenantId)
                .param("ids", demandIds)
                .update();
    }

    public List<String> getDistinctConsumerCodesBatch(String tenantId, String businessServiceCode,
                                                      List<DemandStatus> statuses, String lastSeen,
                                                      String maxCode, int limit) {
        return jdbc.sql("SELECT DISTINCT consumer_code FROM demands WHERE tenant_id = :tenantId "
                        + "AND business_service_code = :bsCode AND status IN (:statuses) "
                        + "AND consumer_code > :lastSeen AND consumer_code <= :maxCode "
                        + "ORDER BY consumer_code ASC LIMIT :limit")
                .param("tenantId", tenantId)
                .param("bsCode", businessServiceCode)
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .param("lastSeen", lastSeen)
                .param("maxCode", maxCode)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public String getMaxConsumerCode(String tenantId, String businessServiceCode, List<DemandStatus> statuses) {
        String max = jdbc.sql("SELECT MAX(consumer_code) FROM demands WHERE tenant_id = :tenantId "
                        + "AND business_service_code = :bsCode AND status IN (:statuses)")
                .param("tenantId", tenantId)
                .param("bsCode", businessServiceCode)
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .query(String.class)
                .optional()
                .orElse(null);
        return max == null ? "" : max;
    }
}
