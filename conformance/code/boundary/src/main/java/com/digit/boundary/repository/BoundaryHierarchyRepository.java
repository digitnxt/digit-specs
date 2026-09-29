package com.digit.boundary.repository;

import com.digit.boundary.model.AuditDetails;
import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryHierarchyRequest;
import com.digit.boundary.model.BoundaryHierarchySearchCriteria;
import com.digit.boundary.model.BoundaryTypeHierarchy;
import com.digit.boundary.observability.BusinessMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for boundary_hierarchy_v1. Mirrors Go
 * internal/repository/boundary_hierarchy_repository_impl.go (GORM) using plain SQL.
 */
@Repository
public class BoundaryHierarchyRepository {

    private static final String TABLE = "boundary_hierarchy_v1";
    private static final String SELECT_COLS =
            "id, tenantid, hierarchytype, boundaryhierarchy, requestid, "
                    + "\"createdTime\", \"createdBy\", \"modifiedTime\", \"modifiedBy\"";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final BusinessMetrics metrics;

    public BoundaryHierarchyRepository(JdbcTemplate jdbc, JsonMapper jsonMapper, BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
    }

    private final RowMapper<BoundaryHierarchy> rowMapper = (RowMapper<BoundaryHierarchy>) (ResultSet rs, int rowNum) -> {
        BoundaryHierarchy h = new BoundaryHierarchy();
        h.setId(rs.getString("id"));
        h.setTenantId(rs.getString("tenantid"));
        h.setHierarchyType(rs.getString("hierarchytype"));
        h.setBoundaryHierarchy(parseList(rs.getString("boundaryhierarchy")));
        h.setRequestId(rs.getString("requestid"));
        AuditDetails ad = new AuditDetails();
        ad.setCreatedBy(rs.getString("createdBy"));
        ad.setCreatedTime(rs.getLong("createdTime"));
        ad.setModifiedBy(rs.getString("modifiedBy"));
        ad.setModifiedTime(rs.getLong("modifiedTime"));
        h.setAuditDetails(ad);
        return h;
    };

    private List<BoundaryTypeHierarchy> parseList(String json) {
        // Go BoundaryHierarchyList.Scan: NULL jsonb → nil list → "boundaryHierarchy": null
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.readValue(json,
                    TypeFactory.createDefaultInstance().constructCollectionType(List.class, BoundaryTypeHierarchy.class));
        } catch (Exception e) {
            throw new RuntimeException("failed to parse boundaryHierarchy jsonb", e);
        }
    }

    private String writeList(List<BoundaryTypeHierarchy> list) {
        // Go BoundaryHierarchyList.Value: nil list → SQL NULL, not '[]'
        if (list == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize boundaryHierarchy jsonb", e);
        }
    }
    public void create(BoundaryHierarchyRequest request) {
        boolean ok = true;
        try {
            BoundaryHierarchy h = request.getHierarchy();
            AuditDetails ad = h.getAuditDetails();
            jdbc.update(
                    "INSERT INTO " + TABLE + " (id, tenantid, hierarchytype, boundaryhierarchy, requestid,"
                            + " \"createdTime\", \"createdBy\", \"modifiedTime\", \"modifiedBy\")"
                            + " VALUES (?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
                    h.getId(), h.getTenantId(), h.getHierarchyType(), writeList(h.getBoundaryHierarchy()),
                    h.getRequestId(),
                    ad == null ? 0L : ad.getCreatedTime(), ad == null ? null : ad.getCreatedBy(),
                    ad == null ? 0L : ad.getModifiedTime(), ad == null ? null : ad.getModifiedBy());
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("INSERT", "boundary_hierarchy", ok);
        }
    }

    public List<BoundaryHierarchy> search(BoundaryHierarchySearchCriteria criteria) {
        boolean ok = true;
        try {
            StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE tenantid = ?");
            List<Object> args = new ArrayList<>();
            args.add(criteria.getTenantId());
            if (criteria.getHierarchyType() != null && !criteria.getHierarchyType().isEmpty()) {
                sql.append(" AND hierarchytype = ?");
                args.add(criteria.getHierarchyType());
            }
            return jdbc.query(sql.toString(), rowMapper, args.toArray());
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", "boundary_hierarchy", ok);
        }
    }

    /**
     * Updates a hierarchy by (id, tenant, hierarchyType). Mirrors Go's GORM {@code Updates(&hierarchy)},
     * which writes ONLY the struct's non-zero fields: a column is included in the SET clause only when
     * its value is "non-zero" (non-empty string, non-zero long, non-empty list). Zero-valued fields are
     * left untouched — so, e.g., an empty {@code requestId} or a 0 {@code modifiedTime} is not blanked,
     * an unset {@code boundaryHierarchy} is not overwritten with {@code '[]'}, and {@code createdBy}/
     * {@code createdTime} are written only if the request actually carries them. (id/tenantid/
     * hierarchytype are constrained by the WHERE clause, so they are not re-written.)
     */
    public void update(BoundaryHierarchyRequest request) {
        boolean ok = true;
        try {
            BoundaryHierarchy h = request.getHierarchy();
            AuditDetails ad = h.getAuditDetails();

            List<String> setClauses = new ArrayList<>();
            List<Object> args = new ArrayList<>();

            List<BoundaryTypeHierarchy> levels = h.getBoundaryHierarchy();
            if (levels != null && !levels.isEmpty()) {
                setClauses.add("boundaryhierarchy = ?::jsonb");
                args.add(writeList(levels));
            }
            if (h.getRequestId() != null && !h.getRequestId().isEmpty()) {
                setClauses.add("requestid = ?");
                args.add(h.getRequestId());
            }
            if (ad != null && ad.getCreatedBy() != null && !ad.getCreatedBy().isEmpty()) {
                setClauses.add("\"createdBy\" = ?");
                args.add(ad.getCreatedBy());
            }
            if (ad != null && ad.getCreatedTime() != 0L) {
                setClauses.add("\"createdTime\" = ?");
                args.add(ad.getCreatedTime());
            }
            if (ad != null && ad.getModifiedBy() != null && !ad.getModifiedBy().isEmpty()) {
                setClauses.add("\"modifiedBy\" = ?");
                args.add(ad.getModifiedBy());
            }
            if (ad != null && ad.getModifiedTime() != 0L) {
                setClauses.add("\"modifiedTime\" = ?");
                args.add(ad.getModifiedTime());
            }

            if (setClauses.isEmpty()) {
                // Nothing non-zero to update (GORM would issue no-op); skip.
                return;
            }

            args.add(h.getId());
            args.add(h.getTenantId());
            args.add(h.getHierarchyType());
            jdbc.update(
                    "UPDATE " + TABLE + " SET " + String.join(", ", setClauses)
                            + " WHERE id = ?::uuid AND tenantid = ? AND hierarchytype = ?",
                    args.toArray());
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("UPDATE", "boundary_hierarchy", ok);
        }
    }

    public boolean existsByType(String tenantId, String hierarchyType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenantid = ? AND hierarchytype = ?",
                Long.class, tenantId, hierarchyType);
        return count != null && count > 0;
    }

    public BoundaryHierarchy getById(String id, String tenantId) {
        boolean ok = true;
        try {
            List<BoundaryHierarchy> rows = jdbc.query(
                    "SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE id = ?::uuid AND tenantid = ?",
                    rowMapper, id, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", "boundary_hierarchy", ok);
        }
    }}
