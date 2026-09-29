package com.digit.boundary.repository;

import com.digit.boundary.model.AuditDetails;
import com.digit.boundary.model.BoundaryHierarchy;
import com.digit.boundary.model.BoundaryHierarchySearchCriteria;
import com.digit.boundary.model.BoundaryRelationship;
import com.digit.boundary.model.BoundaryRelationshipRequest;
import com.digit.boundary.model.BoundaryRelationshipSearchCriteria;
import com.digit.boundary.observability.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JDBC repository for boundary_relationship_v1. Mirrors Go
 * internal/repository/boundary_relationship_repository_impl.go (GORM) using plain SQL, including the
 * ancestral materialized-path build/update and the materialized-path search used for tree building.
 */
@Repository
public class BoundaryRelationshipRepository {

    private static final String TABLE = "boundary_relationship_v1";
    private static final String SELECT_COLS =
            "id, tenantid, code, hierarchytype, boundarytype, parent, ancestralmaterializedpath, requestid, "
                    + "\"createdTime\", \"createdBy\", \"modifiedTime\", \"modifiedBy\"";

    private static final Logger log = LoggerFactory.getLogger(BoundaryRelationshipRepository.class);

    private final JdbcTemplate jdbc;
    private final BusinessMetrics metrics;

    public BoundaryRelationshipRepository(JdbcTemplate jdbc, BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    private final RowMapper<BoundaryRelationship> rowMapper = (RowMapper<BoundaryRelationship>) (ResultSet rs, int rowNum) -> {
        BoundaryRelationship r = new BoundaryRelationship();
        r.setId(rs.getString("id"));
        r.setTenantId(rs.getString("tenantid"));
        r.setCode(rs.getString("code"));
        r.setHierarchyType(rs.getString("hierarchytype"));
        r.setBoundaryType(rs.getString("boundarytype"));
        String parent = rs.getString("parent");
        r.setParent(parent == null ? "" : parent);
        String amp = rs.getString("ancestralmaterializedpath");
        r.setAncestralMaterializedPath(amp == null ? "" : amp);
        r.setRequestId(rs.getString("requestid"));
        AuditDetails ad = new AuditDetails();
        ad.setCreatedBy(rs.getString("createdBy"));
        ad.setCreatedTime(rs.getLong("createdTime"));
        ad.setModifiedBy(rs.getString("modifiedBy"));
        ad.setModifiedTime(rs.getLong("modifiedTime"));
        r.setAuditDetails(ad);
        return r;
    };
    /** Create: resolve parent's materialized path, build this node's path, then insert. Mirrors Go Create. */
    public void create(BoundaryRelationshipRequest request) {
        boolean ok = true;
        try {
            BoundaryRelationship rel = request.getRelationship();
            String parent = rel.getParent();
            String parentPath = "";
            if (parent != null && !parent.isEmpty() && !"null".equals(parent)) {
                BoundaryRelationship parentRel = findOne(
                        "SELECT " + SELECT_COLS + " FROM " + TABLE
                                + " WHERE code = ? AND tenantid = ? AND hierarchytype = ? ORDER BY id LIMIT 1",
                        parent, rel.getTenantId(), rel.getHierarchyType());
                if (parentRel == null) {
                    throw new IllegalStateException("parent relationship with code '" + parent + "' does not exist");
                }
                parentPath = parentRel.getAncestralMaterializedPath();
            }
            rel.setAncestralMaterializedPath(buildMaterializedPath(parentPath, rel.getCode()));
            AuditDetails ad = rel.getAuditDetails();
            jdbc.update(
                    "INSERT INTO " + TABLE + " (id, tenantid, code, hierarchytype, boundarytype, parent,"
                            + " ancestralmaterializedpath, requestid,"
                            + " \"createdTime\", \"createdBy\", \"modifiedTime\", \"modifiedBy\")"
                            + " VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    rel.getId(), rel.getTenantId(), rel.getCode(), rel.getHierarchyType(), rel.getBoundaryType(),
                    safe(rel.getParent()), rel.getAncestralMaterializedPath(), rel.getRequestId(),
                    ad == null ? 0L : ad.getCreatedTime(), ad == null ? null : ad.getCreatedBy(),
                    ad == null ? 0L : ad.getModifiedTime(), ad == null ? null : ad.getModifiedBy());
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("INSERT", "boundary_relationship", ok);
        }
    }

    /** Basic search with optional filters. Mirrors Go Search. */
    public List<BoundaryRelationship> search(BoundaryRelationshipSearchCriteria criteria) {
        boolean ok = true;
        try {
            StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE tenantid = ?");
            List<Object> args = new ArrayList<>();
            args.add(criteria.getTenantId());
            if (criteria.getCodes() != null && !criteria.getCodes().isEmpty()) {
                sql.append(" AND code IN (").append(placeholders(criteria.getCodes().size())).append(")");
                args.addAll(criteria.getCodes());
            }
            if (notEmpty(criteria.getHierarchyType())) {
                sql.append(" AND hierarchytype = ?");
                args.add(criteria.getHierarchyType());
            }
            if (notEmpty(criteria.getBoundaryType())) {
                sql.append(" AND boundarytype = ?");
                args.add(criteria.getBoundaryType());
            }
            if (notEmpty(criteria.getParent())) {
                sql.append(" AND parent = ?");
                args.add(criteria.getParent());
            }
            if (criteria.getLimit() > 0) {
                sql.append(" LIMIT ").append(criteria.getLimit());
            }
            if (criteria.getOffset() > 0) {
                sql.append(" OFFSET ").append(criteria.getOffset());
            }
            return jdbc.query(sql.toString(), rowMapper, args.toArray());
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", "boundary_relationship", ok);
        }
    }

    /** Update: recompute materialized path if parent changed (and cascade to children), then update. */
    public void update(BoundaryRelationshipRequest request) {
        BoundaryRelationship rel = request.getRelationship();
        BoundaryRelationship current = getById(rel.getId(), rel.getTenantId());
        if (current == null) {
            throw new IllegalStateException("boundary relationship not found");
        }

        if (!safe(current.getParent()).equals(safe(rel.getParent()))) {
            String parentPath = "";
            if (notEmpty(rel.getParent()) && !"null".equals(rel.getParent())) {
                BoundaryRelationship parentRel = findOne(
                        "SELECT " + SELECT_COLS + " FROM " + TABLE
                                + " WHERE code = ? AND tenantid = ? AND hierarchytype = ? ORDER BY id LIMIT 1",
                        rel.getParent(), rel.getTenantId(), rel.getHierarchyType());
                if (parentRel == null) {
                    throw new IllegalStateException(
                            "parent relationship with code '" + rel.getParent() + "' does not exist");
                }
                parentPath = parentRel.getAncestralMaterializedPath();
            }
            rel.setAncestralMaterializedPath(buildMaterializedPath(parentPath, rel.getCode()));
            updateChildrenMaterializedPaths(current.getAncestralMaterializedPath(),
                    rel.getAncestralMaterializedPath(), rel.getTenantId(), rel.getHierarchyType());
        } else {
            // Parent unchanged: preserve the stored path. Without this, the UPDATE below would
            // overwrite it with the request object's default empty string, breaking
            // includeParents/includeChildren for this row.
            rel.setAncestralMaterializedPath(current.getAncestralMaterializedPath());
        }
        boolean ok = true;
        try {
            AuditDetails ad = rel.getAuditDetails();
            jdbc.update(
                    "UPDATE " + TABLE + " SET hierarchytype = ?, boundarytype = ?, parent = ?,"
                            + " ancestralmaterializedpath = ?, requestid = ?, \"modifiedBy\" = ?, \"modifiedTime\" = ?"
                            + " WHERE id = ?::uuid AND tenantid = ?",
                    rel.getHierarchyType(), rel.getBoundaryType(), safe(rel.getParent()),
                    rel.getAncestralMaterializedPath(), rel.getRequestId(),
                    ad == null ? null : ad.getModifiedBy(), ad == null ? 0L : ad.getModifiedTime(),
                    rel.getId(), rel.getTenantId());
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("UPDATE", "boundary_relationship", ok);
        }
    }

    public BoundaryRelationship getById(String id, String tenantId) {
        return findOne("SELECT " + SELECT_COLS + " FROM " + TABLE
                        + " WHERE id = ?::uuid AND tenantid = ? ORDER BY id LIMIT 1",
                id, tenantId);
    }

    public boolean existsByCode(String tenantId, String code, String hierarchyType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenantid = ? AND code = ? AND hierarchytype = ?",
                Long.class, tenantId, code, hierarchyType);
        return count != null && count > 0;
    }

    public boolean parentExists(String tenantId, String parent, String hierarchyType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenantid = ? AND code = ? AND hierarchytype = ?",
                Long.class, tenantId, parent, hierarchyType);
        return count != null && count > 0;
    }

    /** Boundary existence check against boundary_v1. Mirrors Go BoundaryExists. */
    public boolean boundaryExists(String tenantId, String boundaryCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boundary_v1 WHERE tenantid = ? AND code = ?",
                Long.class, tenantId, boundaryCode);
        boolean exists = count != null && count > 0;
        log.info("Boundary existence check completed tenant_id={} boundary_code={} count={} exists={}",
                tenantId, boundaryCode, count == null ? 0 : count, exists);
        return exists;
    }

    /** Hierarchy existence check against boundary_hierarchy_v1. Mirrors Go HierarchyExists. */
    public boolean hierarchyExists(String tenantId, String hierarchyType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boundary_hierarchy_v1 WHERE tenantid = ? AND hierarchytype = ?",
                Long.class, tenantId, hierarchyType);
        boolean exists = count != null && count > 0;
        log.info("Hierarchy existence check completed tenant_id={} hierarchy_type={} count={} exists={}",
                tenantId, hierarchyType, count == null ? 0 : count, exists);
        return exists;
    }

    /** Gets the hierarchy definition for validation. Mirrors Go GetHierarchyDefinition. */
    public BoundaryHierarchy getHierarchyDefinition(String tenantId, String hierarchyType,
                                                    BoundaryHierarchyRepository hierarchyRepo) {
        List<BoundaryHierarchy> rows = hierarchyRepo.search(
                new BoundaryHierarchySearchCriteria(tenantId, hierarchyType));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Fetch a relationship by code (used by hierarchy-order validation). Mirrors Go GetByCode. */
    public BoundaryRelationship getByCode(String tenantId, String code, String hierarchyType) {
        return findOne("SELECT " + SELECT_COLS + " FROM " + TABLE
                        + " WHERE tenantid = ? AND code = ? AND hierarchytype = ? ORDER BY id LIMIT 1",
                tenantId, code, hierarchyType);
    }

    private String buildMaterializedPath(String parentPath, String code) {
        if (parentPath == null || parentPath.isEmpty()) {
            return code;
        }
        return parentPath + "|" + code;
    }

    /**
     * Updates the materialized path of all descendants when a node's parent changes.
     * Mirrors Go updateChildrenMaterializedPaths (escapes LIKE wildcards, replaces the old prefix).
     */
    private void updateChildrenMaterializedPaths(String oldPath, String newPath, String tenantId, String hierarchyType) {
        String escapedOldPath = oldPath.replace("%", "\\%").replace("_", "\\_");
        List<BoundaryRelationship> children = jdbc.query(
                "SELECT " + SELECT_COLS + " FROM " + TABLE
                        + " WHERE tenantid = ? AND hierarchytype = ? AND ancestralmaterializedpath LIKE ?",
                rowMapper, tenantId, hierarchyType, escapedOldPath + "|%");
        for (BoundaryRelationship child : children) {
            String updatedPath = replaceFirst(child.getAncestralMaterializedPath(), oldPath, newPath);
            jdbc.update("UPDATE " + TABLE + " SET ancestralmaterializedpath = ? WHERE id = ?::uuid",
                    updatedPath, child.getId());
        }
    }

    /**
     * Materialized-path search used for hierarchical tree building. Mirrors Go SearchWithMaterializedPath,
     * including the isSearchForRootNode branch and the currentBoundaryCodes OR-group used to find
     * descendants, ordered by ancestralmaterializedpath then "createdTime" desc.
     */
    public List<BoundaryRelationship> searchWithMaterializedPath(BoundaryRelationshipSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE tenantid = ?");
        List<Object> args = new ArrayList<>();
        args.add(criteria.getTenantId());

        if (notEmpty(criteria.getHierarchyType())) {
            sql.append(" AND hierarchytype = ?");
            args.add(criteria.getHierarchyType());
        }
        if (notEmpty(criteria.getParent())) {
            sql.append(" AND parent = ?");
            args.add(criteria.getParent());
        }

        if (criteria.isSearchForRootNode()) {
            sql.append(" AND (parent IS NULL OR parent = '')");
        } else {
            if (notEmpty(criteria.getBoundaryType())) {
                sql.append(" AND boundarytype = ?");
                args.add(criteria.getBoundaryType());
            }
            if (criteria.getCodes() != null && !criteria.getCodes().isEmpty()) {
                sql.append(" AND code IN (").append(placeholders(criteria.getCodes().size())).append(")");
                args.addAll(criteria.getCodes());
            }
        }

        if (criteria.getCurrentBoundaryCodes() != null && !criteria.getCurrentBoundaryCodes().isEmpty()) {
            // GORM builds an OR-group: for each code, exact-root / start / middle / end materialized-path matches.
            List<String> ors = new ArrayList<>();
            for (String code : criteria.getCurrentBoundaryCodes()) {
                ors.add("ancestralmaterializedpath = ?");
                args.add(code);
                ors.add("ancestralmaterializedpath LIKE ?");
                args.add(code + "|%");
                ors.add("ancestralmaterializedpath LIKE ?");
                args.add("%|" + code + "|%");
                ors.add("ancestralmaterializedpath LIKE ?");
                args.add("%|" + code);
            }
            sql.append(" AND (").append(String.join(" OR ", ors)).append(")");
        }

        // ORDER BY must precede LIMIT/OFFSET (valid PostgreSQL). GORM emits them in this order too:
        // .Order(...).Limit(...).Offset(...) -> ORDER BY ... LIMIT ... OFFSET ...
        sql.append(" ORDER BY ancestralmaterializedpath, \"createdTime\" desc");
        if (criteria.getLimit() > 0) {
            sql.append(" LIMIT ").append(criteria.getLimit());
        }
        if (criteria.getOffset() > 0) {
            sql.append(" OFFSET ").append(criteria.getOffset());
        }
        return jdbc.query(sql.toString(), rowMapper, args.toArray());
    }

    // ---- helpers ----

    private BoundaryRelationship findOne(String sql, Object... args) {
        List<BoundaryRelationship> rows = jdbc.query(sql, rowMapper, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String placeholders(int n) {
        return java.util.Collections.nCopies(n, "?").stream().collect(Collectors.joining(", "));
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String replaceFirst(String src, String target, String replacement) {
        int idx = src.indexOf(target);
        if (idx < 0) {
            return src;
        }
        return src.substring(0, idx) + replacement + src.substring(idx + target.length());
    }}
