package com.digit.accesscontrol.repository;

import com.digit.accesscontrol.model.AuditDetail;
import com.digit.accesscontrol.model.CreateRbacRuleRequest;
import com.digit.accesscontrol.model.Rule;
import com.digit.accesscontrol.model.UpdateRbacRuleRequest;
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
 * JDBC repository for access_rbac_rules_v3. Mirrors the Go gorm rbac repository's queries exactly:
 * same WHERE/ORDER/LIMIT, same read-modify-write update, same version-hash SQL.
 */
@Repository
public class RbacRepository {

    private static final String TABLE = "access_rbac_rules_v3";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics metrics;

    public RbacRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, ObservabilityMetrics metrics) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    private static final String COLS =
            "id, tenant_id, role_names, http_method, path, effect, priority, enabled, "
                    + "constraints, description, requestid, created_by, modified_by, created_at, updated_at";

    private final RowMapper<Rule> rowMapper = (ResultSet rs, int rowNum) -> {
        Rule r = new Rule();
        r.setId(rs.getString("id"));
        r.setTenantId(rs.getString("tenant_id"));
        r.setRoleNames(readStringArray(rs, "role_names"));
        r.setHttpMethod(rs.getString("http_method"));
        r.setPath(rs.getString("path"));
        r.setEffect(rs.getString("effect"));
        r.setPriority(rs.getInt("priority"));
        r.setEnabled(rs.getBoolean("enabled"));
        r.setConstraints(parseJson(rs.getString("constraints")));
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

    public Rule create(String tenantId, CreateRbacRuleRequest req, String userId, String requestId) {
        boolean ok = true;
        try {
            Rule r = new Rule();
            r.setId(UUID.randomUUID().toString());
            r.setTenantId(tenantId);
            r.setRoleNames(req.getRoleNames());
            r.setHttpMethod(req.getHttpMethod());
            r.setPath(req.getPath());
            r.setEffect(req.getEffect());
            r.setPriority(req.getPriority());
            r.setEnabled(req.getEnabled());
            r.setConstraints(req.getConstraints());
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
            // Normalize empty description/requestId back to null for the response (Go omitempty).
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

    private void insert(Rule r) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO " + TABLE + " (id, tenant_id, role_names, http_method, path, effect,"
                            + " priority, enabled, constraints, description, requestid, created_by,"
                            + " modified_by, created_at, updated_at)"
                            + " VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)");
            int i = 1;
            ps.setString(i++, r.getId());
            ps.setString(i++, r.getTenantId());
            ps.setArray(i++, con.createArrayOf("text",
                    r.getRoleNames() == null ? new Object[0] : r.getRoleNames().toArray()));
            ps.setString(i++, r.getHttpMethod());
            ps.setString(i++, r.getPath());
            ps.setString(i++, r.getEffect());
            ps.setInt(i++, r.getPriority());
            ps.setBoolean(i++, r.isEnabled());
            ps.setString(i++, writeJson(r.getConstraints()));
            ps.setString(i++, r.getDescription());
            ps.setString(i++, r.getRequestId());
            ps.setString(i++, r.getAuditDetails().getCreatedBy());
            ps.setString(i++, r.getAuditDetails().getModifiedBy());
            ps.setLong(i++, r.getAuditDetails().getCreatedTime());
            ps.setLong(i++, r.getAuditDetails().getModifiedTime());
            return ps;
        });
    }

    public Rule get(String tenantId, String id) {
        boolean ok = true;
        try {
            List<Rule> rows = jdbc.query(
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

    /** Read-modify-write update. Mirrors Go: GetRbacRule then db.Save (writes every column). */
    public Rule update(String tenantId, String id, UpdateRbacRuleRequest req, String userId, String requestId) {
        Rule existing = get(tenantId, id);
        boolean ok = true;
        try {
            existing.getAuditDetails().setModifiedBy(userId);
            existing.getAuditDetails().setModifiedTime(System.currentTimeMillis());
            existing.setRequestId(requestId);

            if (req.getRoleNames() != null) {
                existing.setRoleNames(req.getRoleNames());
            }
            if (req.getHttpMethod() != null) {
                existing.setHttpMethod(req.getHttpMethod());
            }
            if (req.getPath() != null) {
                existing.setPath(req.getPath());
            }
            if (req.getEffect() != null) {
                existing.setEffect(req.getEffect());
            }
            if (req.getPriority() != null) {
                existing.setPriority(req.getPriority());
            }
            if (req.getEnabled() != null) {
                existing.setEnabled(req.getEnabled());
            }
            if (req.getConstraints().isSet()) {
                existing.setConstraints(req.getConstraints().isNull() ? null : req.getConstraints().getValue());
            }
            if (req.getDescription().isSet()) {
                existing.setDescription(req.getDescription().isNull() ? "" : req.getDescription().getValue());
            }

            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "UPDATE " + TABLE + " SET tenant_id = ?, role_names = ?, http_method = ?,"
                                + " path = ?, effect = ?, priority = ?, enabled = ?, constraints = ?::jsonb,"
                                + " description = ?, requestid = ?, created_by = ?, modified_by = ?,"
                                + " created_at = ?, updated_at = ? WHERE id = ?::uuid");
                int i = 1;
                ps.setString(i++, existing.getTenantId());
                ps.setArray(i++, con.createArrayOf("text",
                        existing.getRoleNames() == null ? new Object[0] : existing.getRoleNames().toArray()));
                ps.setString(i++, existing.getHttpMethod());
                ps.setString(i++, existing.getPath());
                ps.setString(i++, existing.getEffect());
                ps.setInt(i++, existing.getPriority());
                ps.setBoolean(i++, existing.isEnabled());
                ps.setString(i++, writeJson(existing.getConstraints()));
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

    public ListResult list(String tenantId, com.digit.accesscontrol.model.Filters.RbacRulesFilter f) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (f.roleName != null && !f.roleName.isEmpty()) {
            where.append(" AND ? = ANY(role_names)");
            args.add(f.roleName);
        }
        if (f.httpMethod != null && !f.httpMethod.isEmpty()) {
            where.append(" AND http_method = ?");
            args.add(f.httpMethod);
        }
        if (f.effect != null && !f.effect.isEmpty()) {
            where.append(" AND effect = ?");
            args.add(f.effect);
        }
        if (f.enabled != null) {
            where.append(" AND enabled = ?");
            args.add(f.enabled);
        }
        boolean ok = true;
        try {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + where, Integer.class,
                    args.toArray());
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(f.limit);
            pageArgs.add(f.offset);
            List<Rule> rules = jdbc.query("SELECT " + COLS + " FROM " + TABLE + where
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
            List<Rule> rules = jdbc.query("SELECT " + COLS + " FROM " + TABLE
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

    public int bulkCreate(String tenantId, List<CreateRbacRuleRequest> rules, String userId, String requestId) {
        if (rules == null || rules.isEmpty()) {
            return 0;
        }
        boolean ok = true;
        try {
            long now = System.currentTimeMillis();
            for (CreateRbacRuleRequest req : rules) {
                Rule r = new Rule();
                r.setId(UUID.randomUUID().toString());
                r.setTenantId(tenantId);
                r.setRoleNames(req.getRoleNames());
                r.setHttpMethod(req.getHttpMethod());
                r.setPath(req.getPath());
                r.setEffect(req.getEffect());
                r.setPriority(req.getPriority());
                r.setEnabled(req.getEnabled());
                r.setConstraints(req.getConstraints());
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

    // ---- helpers ----

    public static class ListResult {
        public final List<Rule> rules;
        public final int total;
        public ListResult(List<Rule> rules, int total) { this.rules = rules; this.total = total; }
    }

    @SuppressWarnings("unchecked")
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
