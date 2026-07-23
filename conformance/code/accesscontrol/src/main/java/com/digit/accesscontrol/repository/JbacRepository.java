package com.digit.accesscontrol.repository;

import com.digit.accesscontrol.model.AuditDetail;
import com.digit.accesscontrol.model.CreateJbacRuleRequest;
import com.digit.accesscontrol.model.JbacRule;
import com.digit.accesscontrol.model.UpdateJbacRuleRequest;
import com.digit.accesscontrol.constants.ErrorCodes;
import org.digit.tracer.model.CustomException;
import org.digit.tracer.observability.ObservabilityMetrics;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC repository for access_jbac_rules_v3. Mirrors the Go gorm jbac repository's queries exactly.
 */
@Repository
public class JbacRepository {

    private static final String TABLE = "access_jbac_rules_v3";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics metrics;

    public JbacRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, ObservabilityMetrics metrics) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    private static final String COLS =
            "id, tenant_id, name, path_pattern, methods, enforcement, parent_implies_children, "
                    + "extract_jurisdiction, description, requestid, created_by, modified_by, created_at, updated_at";

    private final RowMapper<JbacRule> rowMapper = (ResultSet rs, int rowNum) -> {
        JbacRule r = new JbacRule();
        r.setId(rs.getString("id"));
        r.setTenantId(rs.getString("tenant_id"));
        r.setName(rs.getString("name"));
        r.setPathPattern(rs.getString("path_pattern"));
        r.setMethods(readStringArray(rs, "methods"));
        r.setEnforcement(rs.getString("enforcement"));
        r.setParentImpliesChildren(rs.getBoolean("parent_implies_children"));
        r.setExtractJurisdiction(parseJson(rs.getString("extract_jurisdiction")));
        r.setDescription(emptyToNull(rs.getString("description")));
        r.setRequestId(emptyToNull(rs.getString("requestid")));
        AuditDetail a = new AuditDetail();
        a.setCreatedBy(orEmpty(rs.getString("created_by")));
        a.setModifiedBy(orEmpty(rs.getString("modified_by")));
        a.setCreatedTime(rs.getLong("created_at"));
        a.setModifiedTime(rs.getLong("updated_at"));
        r.setAuditDetails(a);
        return r;
    };

    public JbacRule create(String tenantId, CreateJbacRuleRequest req, String userId, String requestId) {
        boolean ok = true;
        try {
            JbacRule r = new JbacRule();
            r.setId(UUID.randomUUID().toString());
            r.setTenantId(tenantId);
            r.setName(req.getName());
            r.setPathPattern(req.getPathPattern());
            r.setMethods(req.getMethods());
            r.setEnforcement(req.getEnforcement());
            r.setParentImpliesChildren(req.isParentImpliesChildren());
            r.setExtractJurisdiction(req.getExtractJurisdiction());
            r.setDescription(req.getDescription() == null ? "" : req.getDescription());
            r.setRequestId(requestId);
            long now = System.currentTimeMillis();
            AuditDetail a = new AuditDetail();
            a.setCreatedBy(userId);
            a.setCreatedTime(now);
            a.setModifiedBy(userId);
            a.setModifiedTime(now);
            r.setAuditDetails(a);
            insert(r);
            r.setDescription(emptyToNull(r.getDescription()));
            r.setRequestId(emptyToNull(r.getRequestId()));
            return r;
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("INSERT", TABLE, ok);
        }
    }

    private void insert(JbacRule r) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO " + TABLE + " (id, tenant_id, name, path_pattern, methods, enforcement,"
                            + " parent_implies_children, extract_jurisdiction, description, requestid,"
                            + " created_by, modified_by, created_at, updated_at)"
                            + " VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)");
            int i = 1;
            ps.setString(i++, r.getId());
            ps.setString(i++, r.getTenantId());
            ps.setString(i++, r.getName());
            ps.setString(i++, r.getPathPattern());
            ps.setArray(i++, con.createArrayOf("text",
                    r.getMethods() == null ? new Object[0] : r.getMethods().toArray()));
            ps.setString(i++, r.getEnforcement());
            ps.setBoolean(i++, r.isParentImpliesChildren());
            ps.setString(i++, writeJson(r.getExtractJurisdiction()));
            ps.setString(i++, r.getDescription());
            ps.setString(i++, r.getRequestId());
            ps.setString(i++, r.getAuditDetails().getCreatedBy());
            ps.setString(i++, r.getAuditDetails().getModifiedBy());
            ps.setLong(i++, r.getAuditDetails().getCreatedTime());
            ps.setLong(i++, r.getAuditDetails().getModifiedTime());
            return ps;
        });
    }

    public JbacRule get(String tenantId, String id) {
        boolean ok = true;
        try {
            List<JbacRule> rows = jdbc.query(
                    "SELECT " + COLS + " FROM " + TABLE + " WHERE id = ?::uuid AND tenant_id = ? LIMIT 1",
                    rowMapper, id, tenantId);
            if (rows.isEmpty()) {
                throw notFound();
            }
            return rows.get(0);
        } catch (RuntimeException e) {
            if (!isNotFound(e)) {
                ok = false;
            }
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", TABLE, ok);
        }
    }

    public JbacRule update(String tenantId, String id, UpdateJbacRuleRequest req, String userId, String requestId) {
        JbacRule existing = get(tenantId, id);
        boolean ok = true;
        try {
            existing.getAuditDetails().setModifiedBy(userId);
            existing.getAuditDetails().setModifiedTime(System.currentTimeMillis());
            existing.setRequestId(requestId);

            if (req.getName() != null) {
                existing.setName(req.getName());
            }
            if (req.getPathPattern() != null) {
                existing.setPathPattern(req.getPathPattern());
            }
            if (req.getMethods() != null) {
                existing.setMethods(req.getMethods());
            }
            if (req.getEnforcement() != null) {
                existing.setEnforcement(req.getEnforcement());
            }
            if (req.getParentImpliesChildren() != null) {
                existing.setParentImpliesChildren(req.getParentImpliesChildren());
            }
            if (req.getExtractJurisdiction().isSet()) {
                existing.setExtractJurisdiction(req.getExtractJurisdiction().isNull()
                        ? null : req.getExtractJurisdiction().getValue());
            }
            if (req.getDescription().isSet()) {
                existing.setDescription(req.getDescription().isNull() ? "" : req.getDescription().getValue());
            }

            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "UPDATE " + TABLE + " SET tenant_id = ?, name = ?, path_pattern = ?, methods = ?,"
                                + " enforcement = ?, parent_implies_children = ?, extract_jurisdiction = ?::jsonb,"
                                + " description = ?, requestid = ?, created_by = ?, modified_by = ?,"
                                + " created_at = ?, updated_at = ? WHERE id = ?::uuid");
                int i = 1;
                ps.setString(i++, existing.getTenantId());
                ps.setString(i++, existing.getName());
                ps.setString(i++, existing.getPathPattern());
                ps.setArray(i++, con.createArrayOf("text",
                        existing.getMethods() == null ? new Object[0] : existing.getMethods().toArray()));
                ps.setString(i++, existing.getEnforcement());
                ps.setBoolean(i++, existing.isParentImpliesChildren());
                ps.setString(i++, writeJson(existing.getExtractJurisdiction()));
                ps.setString(i++, existing.getDescription() == null ? "" : existing.getDescription());
                ps.setString(i++, existing.getRequestId());
                ps.setString(i++, existing.getAuditDetails().getCreatedBy());
                ps.setString(i++, existing.getAuditDetails().getModifiedBy());
                ps.setLong(i++, existing.getAuditDetails().getCreatedTime());
                ps.setLong(i++, existing.getAuditDetails().getModifiedTime());
                ps.setString(i++, existing.getId());
                return ps;
            });
            existing.setDescription(emptyToNull(existing.getDescription()));
            existing.setRequestId(emptyToNull(existing.getRequestId()));
            return existing;
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("UPDATE", TABLE, ok);
        }
    }

    public void delete(String tenantId, String id) {
        boolean ok = true;
        try {
            int rows = jdbc.update("DELETE FROM " + TABLE + " WHERE id = ?::uuid AND tenant_id = ?",
                    id, tenantId);
            if (rows == 0) {
                throw notFound();
            }
        } catch (RuntimeException e) {
            if (!isNotFound(e)) {
                ok = false;
            }
            throw e;
        } finally {
            metrics.recordDbOperation("DELETE", TABLE, ok);
        }
    }

    public ListResult list(String tenantId, com.digit.accesscontrol.model.Filters.JbacRulesFilter f) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (f.name != null && !f.name.isEmpty()) {
            where.append(" AND name ILIKE ?");
            args.add("%" + f.name + "%");
        }
        if (f.enforcement != null && !f.enforcement.isEmpty()) {
            where.append(" AND enforcement = ?");
            args.add(f.enforcement);
        }
        boolean ok = true;
        try {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + where, Integer.class,
                    args.toArray());
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(f.limit);
            pageArgs.add(f.offset);
            List<JbacRule> rules = jdbc.query("SELECT " + COLS + " FROM " + TABLE + where
                    + " ORDER BY created_at DESC LIMIT ? OFFSET ?", rowMapper, pageArgs.toArray());
            return new ListResult(rules, total == null ? 0 : total);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", TABLE, ok);
        }
    }

    public ListResult listAll(com.digit.accesscontrol.model.Filters.AllRulesFilter f) {
        boolean ok = true;
        try {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Integer.class);
            List<JbacRule> rules = jdbc.query("SELECT " + COLS + " FROM " + TABLE
                    + " ORDER BY id LIMIT ? OFFSET ?", rowMapper, f.limit, f.offset);
            return new ListResult(rules, total == null ? 0 : total);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", TABLE, ok);
        }
    }

    public String versionHash() {
        String query = "SELECT COALESCE("
                + " MAX(updated_at)::text || ':' ||"
                + " COUNT(*)::text         || ':' ||"
                + " SUM(hashtext(id::text || updated_at::text))::text,"
                + " 'no-rules') AS hash FROM " + TABLE;
        return jdbc.queryForObject(query, String.class);
    }

    public int bulkCreate(String tenantId, List<CreateJbacRuleRequest> rules, String userId, String requestId) {
        if (rules == null || rules.isEmpty()) {
            return 0;
        }
        boolean ok = true;
        try {
            long now = System.currentTimeMillis();
            for (CreateJbacRuleRequest req : rules) {
                JbacRule r = new JbacRule();
                r.setId(UUID.randomUUID().toString());
                r.setTenantId(tenantId);
                r.setName(req.getName());
                r.setPathPattern(req.getPathPattern());
                r.setMethods(req.getMethods());
                r.setEnforcement(req.getEnforcement());
                r.setParentImpliesChildren(req.isParentImpliesChildren());
                r.setExtractJurisdiction(req.getExtractJurisdiction());
                r.setDescription(req.getDescription() == null ? "" : req.getDescription());
                r.setRequestId(requestId);
                AuditDetail a = new AuditDetail();
                a.setCreatedBy(userId);
                a.setCreatedTime(now);
                a.setModifiedBy(userId);
                a.setModifiedTime(now);
                r.setAuditDetails(a);
                insert(r);
            }
            return rules.size();
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("INSERT", TABLE, ok);
        }
    }

    public int deleteByTenant(String tenantId) {
        boolean ok = true;
        try {
            return jdbc.update("DELETE FROM " + TABLE + " WHERE tenant_id = ?", tenantId);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("DELETE", TABLE, ok);
        }
    }

    public static class ListResult {
        public final List<JbacRule> rules;
        public final int total;
        public ListResult(List<JbacRule> rules, int total) { this.rules = rules; this.total = total; }
    }

    private static List<String> readStringArray(ResultSet rs, String col) throws SQLException {
        java.sql.Array arr = rs.getArray(col);
        if (arr == null) {
            return new ArrayList<>();
        }
        Object[] elems = (Object[]) arr.getArray();
        List<String> out = new ArrayList<>(elems.length);
        for (Object e : elems) {
            out.add(e == null ? null : e.toString());
        }
        return out;
    }

    private JsonNode parseJson(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse jsonb column", e);
        }
    }

    private String writeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private static String orEmpty(String s) { return s == null ? "" : s; }
    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }

    /** Not-found sentinel (mirrors Go repository.ErrNotFound). The controllers remap it to the
     *  endpoint-specific 404 message; the metrics path treats it as an expected (not failed) outcome. */
    private static CustomException notFound() {
        return new CustomException(ErrorCodes.NOT_FOUND, "not found");
    }

    private static boolean isNotFound(RuntimeException e) {
        return e instanceof CustomException ce && ErrorCodes.NOT_FOUND.equals(ce.getCode());
    }
}
