package com.digit.account.repository;

import com.digit.account.model.TenantConfigEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository for {@code tenant_config_v1} (v3 key/value shape). Mirrors Go
 * internal/repository/tenant_config_repository_gorm.go.
 */
@Repository
public class TenantConfigRepository {

    private static final String TABLE = "tenant_config_v1";
    private static final String COLS =
            "id, tenantid, configkey, configvalue, description, isactive, version, "
                    + "createdby, modifiedby, createdtime, modifiedtime, requestid";

    private final JdbcTemplate jdbc;

    public TenantConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<TenantConfigEntity> rowMapper = (rs, n) -> {
        TenantConfigEntity c = new TenantConfigEntity();
        c.setId(rs.getString("id"));
        c.setTenantId(rs.getString("tenantid"));
        c.setConfigKey(rs.getString("configkey"));
        c.setConfigValue(rs.getString("configvalue"));
        c.setDescription(rs.getString("description"));
        c.setActive(rs.getBoolean("isactive"));
        c.setVersion(rs.getInt("version"));
        c.setCreatedBy(rs.getString("createdby"));
        c.setModifiedBy(rs.getString("modifiedby"));
        c.setCreatedTime(rs.getLong("createdtime"));
        c.setModifiedTime(rs.getLong("modifiedtime"));
        c.setRequestId(rs.getString("requestid"));
        return c;
    };

    public void create(TenantConfigEntity c) {
        try {
            jdbc.update("INSERT INTO " + TABLE + " (" + COLS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    c.getId(), c.getTenantId(), c.getConfigKey(), c.getConfigValue(), c.getDescription(),
                    c.isActive(), c.getVersion(), c.getCreatedBy(), c.getModifiedBy(),
                    c.getCreatedTime(), c.getModifiedTime(), c.getRequestId());
        } catch (RuntimeException e) {
            throw PgErrors.translate(e);
        }
    }

    public TenantConfigEntity getById(String id) {
        return takeOne("SELECT " + COLS + " FROM " + TABLE + " WHERE id = ? LIMIT 1",
                new Object[]{id});
    }

    public TenantConfigEntity getByKey(String tenantCode, String configKey) {
        return takeOne("SELECT " + COLS + " FROM " + TABLE
                        + " WHERE tenantid = ? AND configkey = ? LIMIT 1",
                new Object[]{tenantCode, configKey});
    }

    private TenantConfigEntity takeOne(String sql, Object[] args) {
        List<TenantConfigEntity> rows = jdbc.query(sql, rowMapper, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static class Page {
        public final List<TenantConfigEntity> rows;
        public final long total;
        public Page(List<TenantConfigEntity> rows, long total) { this.rows = rows; this.total = total; }
    }

    public Page list(String tenantCode, String configKey, Boolean isActive, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        String t = tenantCode == null ? "" : tenantCode.strip();
        if (!t.isEmpty()) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("tenantid = ?");
            args.add(t);
        }
        String k = configKey == null ? "" : configKey.strip();
        if (!k.isEmpty()) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("configkey = ?");
            args.add(k);
        }
        // Filtered in SQL so COUNT(*) and the returned page describe the same set.
        if (isActive != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("isactive = ?");
            args.add(isActive);
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<TenantConfigEntity> rows = jdbc.query(
                "SELECT " + COLS + " FROM " + TABLE + where
                        + " ORDER BY \"createdtime\" DESC LIMIT ? OFFSET ?",
                rowMapper, pageArgs.toArray());
        return new Page(rows, totalCount);
    }

    public void update(TenantConfigEntity c) {
        try {
            jdbc.update("UPDATE " + TABLE + " SET tenantid = ?, configkey = ?, configvalue = ?, "
                            + "description = ?, isactive = ?, version = ?, createdby = ?, modifiedby = ?, "
                            + "createdtime = ?, modifiedtime = ?, requestid = ? WHERE id = ?",
                    c.getTenantId(), c.getConfigKey(), c.getConfigValue(), c.getDescription(),
                    c.isActive(), c.getVersion(), c.getCreatedBy(), c.getModifiedBy(),
                    c.getCreatedTime(), c.getModifiedTime(), c.getRequestId(), c.getId());
        } catch (RuntimeException e) {
            throw PgErrors.translate(e);
        }
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    /** Removes every config row scoped to the tenant code (delete cascade). */
    public void deleteByTenant(String tenantCode) {
        jdbc.update("DELETE FROM " + TABLE + " WHERE tenantid = ?", tenantCode);
    }

}