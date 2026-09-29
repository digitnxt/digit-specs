package com.digit.boundary.repository;

import com.digit.boundary.model.AuditDetails;
import com.digit.boundary.model.Boundary;
import com.digit.boundary.model.BoundaryRequest;
import com.digit.boundary.model.BoundarySearchCriteria;
import com.digit.boundary.observability.BusinessMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JDBC repository for boundary_v1. Mirrors Go internal/repository/boundary_repository_impl.go
 * (GORM) using plain SQL: the {@code boundary_v1} table with explicit {@code tenantid} filtering.
 */
@Repository
public class BoundaryRepository {

    private static final String TABLE = "boundary_v1";
    private static final int BATCH_SIZE = 1000;
    /**
     * Derives the PostGIS geom column from the GeoJSON geometry string (SRID 4326 / WGS84).
     * A null geometry yields a null geom (ST_GeomFromGeoJSON/ST_SetSRID are null-safe).
     */
    private static final String GEOM_FROM_GEOJSON = "ST_SetSRID(ST_GeomFromGeoJSON(?::jsonb), 4326)";
    private static final String SELECT_COLS =
            "id, tenantid, code, geometry, additionalattributes, requestid, "
                    + "\"createdTime\", \"createdBy\", \"modifiedTime\", \"modifiedBy\"";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final BusinessMetrics metrics;

    public BoundaryRepository(JdbcTemplate jdbc, JsonMapper jsonMapper, BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
    }

    private final RowMapper<Boundary> rowMapper = (RowMapper<Boundary>) (ResultSet rs, int rowNum) -> {
        Boundary b = new Boundary();
        b.setId(rs.getString("id"));
        b.setTenantId(rs.getString("tenantid"));
        b.setCode(rs.getString("code"));
        b.setGeometry(parseJson(rs.getString("geometry")));
        b.setAdditionalAttributes(parseJson(rs.getString("additionalattributes")));
        b.setRequestId(rs.getString("requestid"));
        AuditDetails ad = new AuditDetails();
        ad.setCreatedBy(rs.getString("createdBy"));
        ad.setCreatedTime(rs.getLong("createdTime"));
        ad.setModifiedBy(rs.getString("modifiedBy"));
        ad.setModifiedTime(rs.getLong("modifiedTime"));
        b.setAuditDetails(ad);
        return b;
    };

    private JsonNode parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse boundary jsonb", e);
        }
    }

    private String writeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    /** Batched insert mirroring Go batchSize=1000 GORM Create. */
    public void create(BoundaryRequest request) {
        boolean ok = true;
        try {
            List<Boundary> all = request.getBoundary();
            for (int i = 0; i < all.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, all.size());
                List<Boundary> batch = all.subList(i, end);
                List<Object[]> args = new ArrayList<>();
                for (Boundary b : batch) {
                    AuditDetails ad = b.getAuditDetails();
                    String geoJson = writeJson(b.getGeometry());
                    args.add(new Object[]{
                            b.getId(), b.getTenantId(), b.getCode(),
                            geoJson, writeJson(b.getAdditionalAttributes()),
                            b.getRequestId(),
                            ad == null ? 0L : ad.getCreatedTime(),
                            ad == null ? null : ad.getCreatedBy(),
                            ad == null ? 0L : ad.getModifiedTime(),
                            ad == null ? null : ad.getModifiedBy(),
                            geoJson
                    });
                }
                jdbc.batchUpdate(
                        "INSERT INTO " + TABLE + " (id, tenantid, code, geometry, additionalattributes,"
                                + " requestid, \"createdTime\", \"createdBy\", \"modifiedTime\", \"modifiedBy\", geom)"
                                + " VALUES (?::uuid, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, " + GEOM_FROM_GEOJSON + ")",
                        args);
            }
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("INSERT", "boundary", ok);
        }
    }

    /** Search by tenant + optional codes/limit/offset. Mirrors Go Search. */
    public List<Boundary> search(BoundarySearchCriteria criteria) {
        boolean ok = true;
        try {
            StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE tenantid = ?");
            List<Object> args = new ArrayList<>();
            args.add(criteria.getTenantId());
            if (criteria.getCodes() != null && !criteria.getCodes().isEmpty()) {
                String ph = criteria.getCodes().stream().map(x -> "?").collect(Collectors.joining(", "));
                sql.append(" AND code IN (").append(ph).append(")");
                args.addAll(criteria.getCodes());
            }
            if (criteria.getLatitude() != null && criteria.getLongitude() != null) {
                // Point-in-polygon on the PostGIS column; rows with geom NULL
                // (pre-geo records, never backfilled) simply never match.
                // ST_MakePoint takes (x, y) = (longitude, latitude).
                sql.append(" AND ST_Contains(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326))");
                args.add(criteria.getLongitude());
                args.add(criteria.getLatitude());
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
            metrics.recordDbOperation("SELECT", "boundary", ok);
        }
    }

    /** Updates the mutable columns for each boundary. Mirrors Go Update (map-based Updates). */
    public void update(BoundaryRequest request) {
        boolean ok = true;
        try {
            for (Boundary b : request.getBoundary()) {
                AuditDetails ad = b.getAuditDetails();
                String geoJson = writeJson(b.getGeometry());
                jdbc.update(
                        "UPDATE " + TABLE + " SET code = ?, geometry = ?::jsonb, additionalattributes = ?::jsonb,"
                                + " requestid = ?, \"modifiedBy\" = ?, \"modifiedTime\" = ?,"
                                + " geom = " + GEOM_FROM_GEOJSON
                                + " WHERE id = ?::uuid AND tenantid = ?",
                        b.getCode(), geoJson, writeJson(b.getAdditionalAttributes()),
                        b.getRequestId(),
                        ad == null ? null : ad.getModifiedBy(),
                        ad == null ? 0L : ad.getModifiedTime(),
                        geoJson,
                        b.getId(), b.getTenantId());
            }
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("UPDATE", "boundary", ok);
        }
    }

    /** Fetch by id + tenant, or null if not found. Mirrors Go GetByID. */
    public Boundary getById(String id, String tenantId) {
        boolean ok = true;
        try {
            List<Boundary> rows = jdbc.query(
                    "SELECT " + SELECT_COLS + " FROM " + TABLE + " WHERE id = ?::uuid AND tenantid = ?",
                    rowMapper, id, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", "boundary", ok);
        }
    }

    /** True if a boundary with this (tenant, code) exists. Mirrors Go ExistsByCode. */
    public boolean existsByCode(String tenantId, String code) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE tenantid = ? AND code = ?",
                Long.class, tenantId, code);
        return count != null && count > 0;
    }}
