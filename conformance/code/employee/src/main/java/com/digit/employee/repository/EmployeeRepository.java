package com.digit.employee.repository;

import org.springframework.http.HttpStatus;

import com.digit.employee.constants.ErrorCodes;

import com.digit.employee.model.AuditDetails;
import com.digit.employee.model.Employee;
import com.digit.employee.model.EmployeeSearchCriteria;
import org.digit.tracer.model.CustomException;
import org.digit.tracer.observability.ObservabilityMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JDBC repository for {@code employee_v3}. Mirrors Go internal/repository/employee_repository.go
 * (GORM) using plain SQL. Audit columns are quoted camelCase ({@code "createdBy"} etc.) per the
 * migrations; the remaining columns are snake_case as GORM mapped them.
 *
 * <p>Search filters use the real columns ({@code department}, {@code designation}, {@code status},
 * {@code employee_type}, {@code user_id}, {@code date_of_appointment}) with a fixed
 * {@code ORDER BY "createdTime" DESC}. PUT ({@link #update}) writes the mutable surface
 * unconditionally (so {@code isActive=false} persists) and omits immutable columns; PATCH
 * ({@link #patch}) writes only supplied fields. Matches Go post-8749c30e.
 */
@Repository
public class EmployeeRepository {

    private static final String TABLE = "employee_v3";

    private final JdbcTemplate jdbc;
    private final ObservabilityMetrics metrics;

    public EmployeeRepository(JdbcTemplate jdbc, ObservabilityMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    private static final String SELECT_COLS =
            "id, code, user_id, individual_id, status, employee_type, date_of_appointment, "
                    + "department, designation, is_active, version, tenant_id, \"createdBy\", \"modifiedBy\", "
                    + "\"createdTime\", \"modifiedTime\"";

    private final RowMapper<Employee> rowMapper = (RowMapper<Employee>) (ResultSet rs, int rowNum) -> {
        Employee e = new Employee();
        Object id = rs.getObject("id");
        e.setId(id == null ? null : id.toString());
        e.setCode(rs.getString("code"));
        e.setUserId(rs.getString("user_id"));
        e.setIndividualId(rs.getString("individual_id"));
        e.setStatus(rs.getString("status"));
        e.setEmployeeType(rs.getString("employee_type"));
        Timestamp ts = rs.getTimestamp("date_of_appointment");
        e.setDateOfAppointment(ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC));
        e.setDepartment(rs.getString("department"));
        e.setDesignation(rs.getString("designation"));
        e.setActive(rs.getBoolean("is_active"));
        e.setVersion(rs.getInt("version"));
        e.setTenantId(rs.getString("tenant_id"));
        AuditDetails ad = new AuditDetails();
        ad.setCreatedBy(rs.getString("createdBy"));
        ad.setModifiedBy(rs.getString("modifiedBy"));
        ad.setCreatedTime(rs.getLong("createdTime"));
        ad.setModifiedTime(rs.getLong("modifiedTime"));
        e.setAuditDetails(ad);
        return e;
    };

    private static Timestamp toTimestamp(OffsetDateTime odt) {
        return odt == null ? null : Timestamp.from(odt.toInstant());
    }

    /** Inserts a new employee row. Generates the id (GORM default uuid_generate_v4 / Go relies on DB). */
    public void create(Employee e) {
        long now = System.currentTimeMillis();
        e.getAuditDetails().setCreatedTime(now);
        e.getAuditDetails().setModifiedTime(now);
        if (e.getAuditDetails().getCreatedBy() == null || e.getAuditDetails().getCreatedBy().isEmpty()) {
            e.getAuditDetails().setCreatedBy("system");
        }
        if (e.getAuditDetails().getModifiedBy() == null || e.getAuditDetails().getModifiedBy().isEmpty()) {
            e.getAuditDetails().setModifiedBy("system");
        }

        boolean ok = true;
        try {
            // Let Postgres generate the id (DEFAULT uuid_generate_v4()) and return it.
            String id = jdbc.queryForObject(
                    "INSERT INTO " + TABLE + " (code, user_id, individual_id, status, employee_type, "
                            + "date_of_appointment, department, designation, is_active, version, tenant_id, "
                            + "\"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id::text",
                    String.class,
                    e.getCode(), e.getUserId(), e.getIndividualId(), e.getStatus(), e.getEmployeeType(),
                    toTimestamp(e.getDateOfAppointment()), e.getDepartment(), e.getDesignation(),
                    e.isActive(), e.getVersion(), e.getTenantId(),
                    e.getAuditDetails().getCreatedBy(), e.getAuditDetails().getModifiedBy(),
                    e.getAuditDetails().getCreatedTime(), e.getAuditDetails().getModifiedTime());
            e.setId(id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // Unique violation on (tenant_id, code) — mirrors Go pgerr 23505 → EMPLOYEE_EXISTS (409).
            ok = false;
            throw new CustomException(ErrorCodes.EMPLOYEE_EXISTS, "Employee code already exists",
                    HttpStatus.CONFLICT);
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("INSERT", "employees", ok);
        }
    }

    /** Finds an employee by id within the tenant; throws NOT_FOUND when absent. */
    public Employee findByUUID(String uuid, String tenantId) {
        boolean ok = true;
        try {
            List<Employee> rows = jdbc.query(
                    "SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE id = ? AND tenant_id = ? LIMIT 1",
                    rowMapper, java.util.UUID.fromString(uuid), tenantId);
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
            metrics.recordDbOperation("SELECT", "employees", ok);
        }
    }

    /**
     * PUT full overwrite of the mutable surface. Mirrors Go Update (Select("*").Omit(immutables)):
     * status/employeeType/department/designation/isActive are written unconditionally — so
     * {@code isActive=false} and cleared strings actually persist — while immutable columns
     * (code, user_id, individual_id, date_of_appointment, tenant_id, createdBy, createdTime) are
     * never touched.
     *
     * <p>Optimistic concurrency: {@code version} is set to {@code expectedVersion + 1} and the WHERE
     * pins both id and the expected version. The row was just loaded within the tenant by the service,
     * so a 0 rows-affected means the version moved under the client → ROW_VERSION_MISMATCH (409).
     */
    public void update(Employee e, int expectedVersion) {
        long now = System.currentTimeMillis();
        String modifiedBy = e.getAuditDetails().getModifiedBy();
        if (modifiedBy == null || modifiedBy.isEmpty()) {
            modifiedBy = "system";
        }
        boolean ok = true;
        try {
            int affected = jdbc.update(
                    "UPDATE " + TABLE + " SET status = ?, employee_type = ?, department = ?, "
                            + "designation = ?, is_active = ?, version = ?, \"modifiedBy\" = ?, \"modifiedTime\" = ? "
                            + "WHERE id = ? AND version = ?",
                    e.getStatus(), e.getEmployeeType(), e.getDepartment(), e.getDesignation(),
                    e.isActive(), expectedVersion + 1, modifiedBy, now,
                    java.util.UUID.fromString(e.getId()), expectedVersion);
            if (affected == 0) {
                throw new CustomException(ErrorCodes.ROW_VERSION_MISMATCH, "employee was modified concurrently", HttpStatus.CONFLICT);
            }
        } catch (CustomException ce) {
            ok = ErrorCodes.ROW_VERSION_MISMATCH.equals(ce.getCode());
            throw ce;
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("UPDATE", "employees", ok);
        }
    }

    /**
     * PATCH partial update. Mirrors Go Patch (Updates(struct) with pointer fields): only non-null
     * fields are written; a non-null {@code false}/empty is written as-is. Audit columns are always
     * set.
     *
     * <p>Optimistic concurrency: {@code version} is set to {@code expectedVersion + 1} and the WHERE
     * pins id, tenant_id, and the expected version. Existence is verified by the service's findByUUID
     * before this call, so a 0 rows-affected means the version moved → ROW_VERSION_MISMATCH (409).
     */
    public void patch(String id, String tenantId, com.digit.employee.model.EmployeePatch p, int expectedVersion) {
        List<String> setClauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (p.getStatus() != null) { setClauses.add("status = ?"); args.add(p.getStatus()); }
        if (p.getEmployeeType() != null) { setClauses.add("employee_type = ?"); args.add(p.getEmployeeType()); }
        if (p.getDepartment() != null) { setClauses.add("department = ?"); args.add(p.getDepartment()); }
        if (p.getDesignation() != null) { setClauses.add("designation = ?"); args.add(p.getDesignation()); }
        if (p.getIsActive() != null) { setClauses.add("is_active = ?"); args.add(p.getIsActive()); }
        setClauses.add("version = ?"); args.add(p.getVersion());
        String modifiedBy = (p.getModifiedBy() == null || p.getModifiedBy().isEmpty()) ? "system" : p.getModifiedBy();
        setClauses.add("\"modifiedBy\" = ?"); args.add(modifiedBy);
        setClauses.add("\"modifiedTime\" = ?"); args.add(p.getModifiedTime() != 0 ? p.getModifiedTime() : System.currentTimeMillis());

        args.add(java.util.UUID.fromString(id));
        args.add(tenantId);
        args.add(expectedVersion);
        boolean ok = true;
        try {
            int affected = jdbc.update("UPDATE " + TABLE + " SET " + String.join(", ", setClauses)
                    + " WHERE id = ? AND tenant_id = ? AND version = ?", args.toArray());
            if (affected == 0) {
                throw new CustomException(ErrorCodes.ROW_VERSION_MISMATCH, "employee was modified concurrently", HttpStatus.CONFLICT);
            }
        } catch (CustomException ce) {
            ok = ErrorCodes.ROW_VERSION_MISMATCH.equals(ce.getCode());
            throw ce;
        } catch (RuntimeException ex) {
            ok = false;
            throw ex;
        } finally {
            metrics.recordDbOperation("UPDATE", "employees", ok);
        }
    }

    /** Hard-deletes an employee by id within the tenant; throws NOT_FOUND when no row matches. */
    public void delete(String id, String tenantId) {
        boolean ok = true;
        try {
            int affected = jdbc.update("DELETE FROM " + TABLE + " WHERE id = ? AND tenant_id = ?",
                    java.util.UUID.fromString(id), tenantId);
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
            metrics.recordDbOperation("DELETE", "employees", ok);
        }
    }

    /** Searches employees. Mirrors the Go GORM query (including its non-existent filter columns). */
    public List<Employee> search(EmployeeSearchCriteria c) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(c.getTenantId());

        if (c.getIds() != null && !c.getIds().isEmpty()) {
            sql.append(" AND id IN (").append(placeholders(c.getIds().size())).append(")");
            for (String u : c.getIds()) { args.add(java.util.UUID.fromString(u)); }
        }
        if (c.getCodes() != null && !c.getCodes().isEmpty()) {
            sql.append(" AND code IN (").append(placeholders(c.getCodes().size())).append(")");
            args.addAll(c.getCodes());
        }
        // user_id IN — populated by the service from Keycloak role resolution (search-by-role).
        if (c.getUserIds() != null && !c.getUserIds().isEmpty()) {
            sql.append(" AND user_id IN (").append(placeholders(c.getUserIds().size())).append(")");
            args.addAll(c.getUserIds());
        }
        if (c.getStatuses() != null && !c.getStatuses().isEmpty()) {
            sql.append(" AND status IN (").append(placeholders(c.getStatuses().size())).append(")");
            args.addAll(c.getStatuses());
        }
        if (c.getEmployeeTypes() != null && !c.getEmployeeTypes().isEmpty()) {
            sql.append(" AND employee_type IN (").append(placeholders(c.getEmployeeTypes().size())).append(")");
            args.addAll(c.getEmployeeTypes());
        }
        if (c.getDepartments() != null && !c.getDepartments().isEmpty()) {
            sql.append(" AND department IN (").append(placeholders(c.getDepartments().size())).append(")");
            args.addAll(c.getDepartments());
        }
        if (c.getDesignations() != null && !c.getDesignations().isEmpty()) {
            sql.append(" AND designation IN (").append(placeholders(c.getDesignations().size())).append(")");
            args.addAll(c.getDesignations());
        }
        if (c.getDateOfAppointmentFrom() != null && !c.getDateOfAppointmentFrom().isEmpty()) {
            sql.append(" AND date_of_appointment >= ?");
            args.add(java.sql.Date.valueOf(c.getDateOfAppointmentFrom()));
        }
        if (c.getDateOfAppointmentTo() != null && !c.getDateOfAppointmentTo().isEmpty()) {
            sql.append(" AND date_of_appointment <= ?");
            args.add(java.sql.Date.valueOf(c.getDateOfAppointmentTo()));
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
            metrics.recordDbOperation("SELECT", "employees", ok);
        }
    }


    private static String placeholders(int n) {
        List<String> p = new ArrayList<>();
        for (int i = 0; i < n; i++) { p.add("?"); }
        return p.stream().collect(Collectors.joining(", "));
    }
}
