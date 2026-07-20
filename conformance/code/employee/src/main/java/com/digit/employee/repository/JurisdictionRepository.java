package com.digit.employee.repository;

import org.springframework.http.HttpStatus;

import com.digit.employee.constants.ErrorCodes;

import com.digit.employee.model.AuditDetails;
import com.digit.employee.model.BoundaryRef;
import com.digit.employee.model.Jurisdiction;
import com.digit.employee.model.JurisdictionSearchCriteria;
import org.digit.tracer.model.CustomException;
import org.digit.tracer.observability.ObservabilityMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JDBC repository for {@code employee_jurisdiction_v3}. Mirrors Go
 * internal/repository/jurisdiction_repository.go (GORM) using plain SQL. {@code boundary_relation} is
 * a jsonb column (serialized via Jackson). Update follows GORM {@code Updates(struct)} semantics:
 * only non-zero fields are written.
 */
@Repository
public class JurisdictionRepository {

    private static final String TABLE = "employee_jurisdiction_v3";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics metrics;

    public JurisdictionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, ObservabilityMetrics metrics) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    private static final String SELECT_COLS =
            "id, employee_id, boundary_relation, is_active, version, tenant_id, \"createdBy\", \"modifiedBy\", "
                    + "\"createdTime\", \"modifiedTime\"";

    private final RowMapper<Jurisdiction> rowMapper = (RowMapper<Jurisdiction>) (ResultSet rs, int rowNum) -> {
        Jurisdiction j = new Jurisdiction();
        Object id = rs.getObject("id");
        j.setId(id == null ? null : id.toString());
        Object empId = rs.getObject("employee_id");
        j.setEmployeeId(empId == null ? null : empId.toString());
        j.setBoundaryRelation(parseRelation(rs.getString("boundary_relation")));
        j.setActive(rs.getBoolean("is_active"));
        j.setVersion(rs.getInt("version"));
        j.setTenantId(rs.getString("tenant_id"));
        AuditDetails ad = new AuditDetails();
        ad.setCreatedBy(rs.getString("createdBy"));
        ad.setModifiedBy(rs.getString("modifiedBy"));
        ad.setCreatedTime(rs.getLong("createdTime"));
        ad.setModifiedTime(rs.getLong("modifiedTime"));
        j.setAuditDetails(ad);
        return j;
    };

    private List<BoundaryRef> parseRelation(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<BoundaryRef>>() {});
        } catch (Exception e) {
            throw new RuntimeException("failed to parse boundary_relation JSON", e);
        }
    }

    private String writeRelation(List<BoundaryRef> refs) {
        try {
            return objectMapper.writeValueAsString(refs == null ? new ArrayList<>() : refs);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize boundary_relation JSON", e);
        }
    }

    /** Inserts a new jurisdiction row. The service supplies a generated UUID id. */
    public void create(Jurisdiction j) {
        long now = System.currentTimeMillis();
        j.getAuditDetails().setCreatedTime(now);
        j.getAuditDetails().setModifiedTime(now);
        if (j.getAuditDetails().getCreatedBy() == null || j.getAuditDetails().getCreatedBy().isEmpty()) {
            j.getAuditDetails().setCreatedBy("system");
        }
        if (j.getAuditDetails().getModifiedBy() == null || j.getAuditDetails().getModifiedBy().isEmpty()) {
            j.getAuditDetails().setModifiedBy("system");
        }

        boolean ok = true;
        try {
            jdbc.update(
                    "INSERT INTO " + TABLE + " (id, employee_id, boundary_relation, is_active, version, tenant_id, "
                            + "\"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\") "
                            + "VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.fromString(j.getId()),
                    j.getEmployeeId() == null ? null : UUID.fromString(j.getEmployeeId()),
                    writeRelation(j.getBoundaryRelation()),
                    j.isActive(), j.getVersion(), j.getTenantId(),
                    j.getAuditDetails().getCreatedBy(), j.getAuditDetails().getModifiedBy(),
                    j.getAuditDetails().getCreatedTime(), j.getAuditDetails().getModifiedTime());
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // FK violation on employee_id — the client referenced an unknown employee. Mirrors Go
            // pgerr 23503 → NOT_FOUND (404) instead of a generic 500.
            ok = false;
            String m = ex.getMostSpecificCause().getMessage();
            if (m != null && (m.toLowerCase().contains("foreign key") || m.toLowerCase().contains("employee"))) {
                throw new CustomException(ErrorCodes.EMPLOYEE_NOT_FOUND, "employee not found", HttpStatus.NOT_FOUND);
            }
            throw ex;
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("INSERT", "jurisdictions", ok);
        }
    }

    /** Finds a jurisdiction by id within the tenant; throws NOT_FOUND when absent. */
    public Jurisdiction findByUUID(String uuid, String tenantId) {
        boolean ok = true;
        try {
            List<Jurisdiction> rows = jdbc.query(
                    "SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE id = ? AND tenant_id = ? LIMIT 1",
                    rowMapper, UUID.fromString(uuid), tenantId);
            if (rows.isEmpty()) {
                throw new CustomException(ErrorCodes.NOT_FOUND, "The requested resource was not found", HttpStatus.NOT_FOUND);
            }
            return rows.get(0);
        } catch (CustomException ce) {
            ok = (ce.getCode() != null && ce.getCode().equals(ErrorCodes.NOT_FOUND));
            throw ce;
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("SELECT", "jurisdictions", ok);
        }
    }

    /**
     * PUT overwrite of the mutable surface. Mirrors Go Update (Select("*").Omit(id, employee_id,
     * tenant_id, createdBy, createdTime)): boundary_relation and is_active are written unconditionally
     * — so {@code isActive=false} persists — while the immutable owner (employee_id) and creation
     * columns are never touched.
     *
     * <p>Optimistic concurrency: {@code version} is set to {@code expectedVersion + 1} and the WHERE
     * pins both id and the expected version. Existence + ownership are verified by the service before
     * this call, so a 0 rows-affected means the version moved → ROW_VERSION_MISMATCH (409).
     */
    public void update(Jurisdiction j, int expectedVersion) {
        long now = System.currentTimeMillis();
        String modifiedBy = j.getAuditDetails().getModifiedBy();
        if (modifiedBy == null || modifiedBy.isEmpty()) {
            modifiedBy = "system";
        }
        boolean ok = true;
        try {
            int affected = jdbc.update(
                    "UPDATE " + TABLE + " SET boundary_relation = ?::jsonb, is_active = ?, version = ?, "
                            + "\"modifiedBy\" = ?, \"modifiedTime\" = ? WHERE id = ? AND version = ?",
                    writeRelation(j.getBoundaryRelation()), j.isActive(), expectedVersion + 1, modifiedBy, now,
                    UUID.fromString(j.getId()), expectedVersion);
            if (affected == 0) {
                throw new CustomException(ErrorCodes.ROW_VERSION_MISMATCH, "jurisdiction was modified concurrently", HttpStatus.CONFLICT);
            }
        } catch (CustomException ce) {
            ok = ErrorCodes.ROW_VERSION_MISMATCH.equals(ce.getCode());
            throw ce;
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("UPDATE", "jurisdictions", ok);
        }
    }

    /**
     * Soft-deletes (is_active=false, version bumped, audit set) every active jurisdiction of the
     * employee whose id is NOT in {@code keepIds}, in a single UPDATE. Used by the employee PUT/PATCH
     * reconcile to drop jurisdictions the client left out of the supplied array. An empty
     * {@code keepIds} deactivates every active jurisdiction. Mirrors Go DeactivateOmitted.
     */
    public void deactivateOmitted(String employeeId, String tenantId, String userId, List<String> keepIds) {
        long now = System.currentTimeMillis();
        String modifiedBy = (userId == null || userId.isEmpty()) ? "system" : userId;
        StringBuilder sql = new StringBuilder(
                "UPDATE " + TABLE + " SET is_active = false, version = version + 1, "
                        + "\"modifiedBy\" = ?, \"modifiedTime\" = ? "
                        + "WHERE employee_id = ? AND tenant_id = ? AND is_active = true");
        List<Object> args = new ArrayList<>();
        args.add(modifiedBy);
        args.add(now);
        args.add(UUID.fromString(employeeId));
        args.add(tenantId);
        // Guard the empty case: an empty NOT IN (...) is invalid SQL and would also mean "keep nothing",
        // so only add the exclusion when there is something to keep.
        if (keepIds != null && !keepIds.isEmpty()) {
            sql.append(" AND id NOT IN (").append(placeholders(keepIds.size())).append(")");
            for (String k : keepIds) { args.add(UUID.fromString(k)); }
        }
        boolean ok = true;
        try {
            jdbc.update(sql.toString(), args.toArray());
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("UPDATE", "jurisdictions", ok);
        }
    }

    /** Hard-deletes a jurisdiction by id within the tenant; throws NOT_FOUND when no row matches. */
    public void delete(String id, String tenantId) {
        boolean ok = true;
        try {
            int affected = jdbc.update("DELETE FROM " + TABLE + " WHERE id = ? AND tenant_id = ?",
                    UUID.fromString(id), tenantId);
            if (affected == 0) {
                throw new CustomException(ErrorCodes.NOT_FOUND, "The requested resource was not found", HttpStatus.NOT_FOUND);
            }
        } catch (CustomException ce) {
            ok = (ce.getCode() != null && ce.getCode().equals(ErrorCodes.NOT_FOUND));
            throw ce;
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("DELETE", "jurisdictions", ok);
        }
    }

    /**
     * Searches jurisdictions scoped to an owning employee. Mirrors Go
     * Search(ctx, tenantID, employeeID, criteria) — employeeId is a positional owner scope from the
     * nested URL path, not a criteria field.
     */
    public List<Jurisdiction> search(String tenantId, String employeeId, JurisdictionSearchCriteria c) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (tenantId != null && !tenantId.isEmpty()) {
            sql.append(" AND tenant_id = ?");
            args.add(tenantId);
        }
        if (employeeId != null && !employeeId.isEmpty()) {
            sql.append(" AND employee_id = ?");
            args.add(UUID.fromString(employeeId));
        }
        if (c.getIds() != null && !c.getIds().isEmpty()) {
            sql.append(" AND id IN (").append(placeholders(c.getIds().size())).append(")");
            for (String s : c.getIds()) { args.add(UUID.fromString(s)); }
        }
        if (c.getIsActive() != null) {
            sql.append(" AND is_active = ?");
            args.add(c.getIsActive());
        }

        // Fixed server-side ordering, matching Go (post-8749c30e removed client-controlled sort).
        // Never concatenate client sort input into SQL — that was an injection sink.
        sql.append(" ORDER BY \"createdTime\" DESC");

        if (c.getLimit() > 0) {
            sql.append(" LIMIT ?");
            args.add(c.getLimit());
        }
        if (c.getOffset() > 0) {
            sql.append(" OFFSET ?");
            args.add(c.getOffset());
        }

        boolean ok = true;
        try {
            return jdbc.query(sql.toString(), rowMapper, args.toArray());
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("SELECT", "jurisdictions", ok);
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String placeholders(int n) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < n; i++) { p.add("?"); }
        return p.stream().collect(Collectors.joining(", "));
    }
}
