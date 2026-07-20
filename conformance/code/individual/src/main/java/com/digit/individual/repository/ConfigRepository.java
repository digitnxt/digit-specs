package com.digit.individual.repository;

import com.digit.individual.model.Config;
import org.digit.tracer.observability.ObservabilityMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * JDBC repository for per-tenant validation config (table individual_config_v3). uniquenesscriteria
 * is a raw jsonb column read/written as JSON text.
 */
@Repository
public class ConfigRepository {

    private static final String T = "individual_config_v3";

    private final JdbcTemplate jdbc;
    private final ObservabilityMetrics metrics;

    public ConfigRepository(JdbcTemplate jdbc, ObservabilityMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    private final RowMapper<Config> mapper = (RowMapper<Config>) (ResultSet rs, int n) -> {
        Config c = new Config();
        c.setId(rs.getLong("id"));
        c.setTenantId(rs.getString("tenantid"));
        c.setMobileRegex(rs.getString("mobileregex"));
        c.setNameRegex(rs.getString("nameregex"));
        c.setUniquenessCriteria(rs.getString("uniquenesscriteria"));
        c.setVersion(rs.getInt("version"));
        c.setCreatedBy(rs.getString("createdBy"));
        c.setModifiedBy(rs.getString("modifiedBy"));
        c.setCreatedTime(rs.getLong("createdTime"));
        c.setModifiedTime(rs.getLong("modifiedTime"));
        c.setRequestId(rs.getString("requestid"));
        return c;
    };

    private static final String COLS = "id, tenantid, mobileregex, nameregex, uniquenesscriteria, version, "
            + "\"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\", requestid";

    public Config getByTenant(String tenantId) {
        boolean ok = true;
        try {
            List<Config> rows = jdbc.query("SELECT " + COLS + " FROM " + T + " WHERE tenantid = ? LIMIT 1",
                    mapper, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", T, ok);
        }
    }

    /**
     * Like {@link #getByTenant} but takes a row lock (SELECT ... FOR UPDATE) so a concurrent upsert
     * for the same tenant serialises behind it. Used only by the upsert read-modify-write to keep the
     * version counter race-free. Must run inside the request transaction (opened by
     * TenantTransactionFilter) so the lock is held across the subsequent update.
     */
    public Config getByTenantForUpdate(String tenantId) {
        boolean ok = true;
        try {
            List<Config> rows = jdbc.query("SELECT " + COLS + " FROM " + T + " WHERE tenantid = ? LIMIT 1 FOR UPDATE",
                    mapper, tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("SELECT", T, ok);
        }
    }

    /** Inserts a new config row; populates the generated id back onto cfg. */
    public void insert(Config cfg) {
        boolean ok = true;
        try {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO " + T + " (tenantid, mobileregex, nameregex, uniquenesscriteria, version, "
                                + "\"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\", requestid) "
                                + "VALUES (?,?,?,?::jsonb,?,?,?,?,?,?)",
                        new String[]{"id"});
                ps.setString(1, cfg.getTenantId());
                ps.setString(2, cfg.getMobileRegex());
                ps.setString(3, cfg.getNameRegex());
                ps.setString(4, cfg.getUniquenessCriteria());
                ps.setInt(5, cfg.getVersion());
                ps.setString(6, cfg.getCreatedBy());
                ps.setString(7, cfg.getModifiedBy());
                ps.setLong(8, cfg.getCreatedTime());
                ps.setLong(9, cfg.getModifiedTime());
                ps.setString(10, cfg.getRequestId());
                return ps;
            }, kh);
            Number key = kh.getKey();
            if (key != null) {
                cfg.setId(key.longValue());
            }
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("INSERT", T, ok);
        }
    }

    /** Overwrites the row identified by cfg.id. Callers preserve immutable audit fields. */
    public void update(Config cfg) {
        boolean ok = true;
        try {
            jdbc.update("UPDATE " + T + " SET tenantid=?, mobileregex=?, nameregex=?, uniquenesscriteria=?::jsonb, "
                            + "version=?, \"createdBy\"=?, \"modifiedBy\"=?, \"createdTime\"=?, \"modifiedTime\"=?, "
                            + "requestid=? WHERE id=?",
                    cfg.getTenantId(), cfg.getMobileRegex(), cfg.getNameRegex(), cfg.getUniquenessCriteria(),
                    cfg.getVersion(), cfg.getCreatedBy(), cfg.getModifiedBy(), cfg.getCreatedTime(),
                    cfg.getModifiedTime(), cfg.getRequestId(), cfg.getId());
        } catch (RuntimeException e) {
            ok = false;
            throw e;
        } finally {
            metrics.recordDbOperation("UPDATE", T, ok);
        }
    }
}
