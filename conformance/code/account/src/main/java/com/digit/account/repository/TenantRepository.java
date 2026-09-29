package com.digit.account.repository;

import com.digit.account.model.TenantEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC repository for {@code tenant_v1}. Mirrors Go internal/repository/tenant_repository_gorm.go.
 * Column names are the lowercase form Postgres folded unquoted identifiers to in the migrations.
 */
@Repository
public class TenantRepository {

    private static final String TABLE = "tenant_v1";
    private static final String COLS =
            "id, code, name, email, phone, address, city, state, pincode, country, firstloginurl, "
                    + "additionalattributes, isactive, passwordgenerated, version, createdby, "
                    + "modifiedby, createdtime, modifiedtime, requestid";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TenantRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<TenantEntity> rowMapper = (rs, n) -> {
        TenantEntity t = new TenantEntity();
        t.setId(rs.getString("id"));
        t.setCode(rs.getString("code"));
        t.setName(rs.getString("name"));
        t.setEmail(rs.getString("email"));
        t.setPhone(rs.getString("phone"));
        t.setAddress(rs.getString("address"));
        t.setCity(rs.getString("city"));
        t.setState(rs.getString("state"));
        t.setPincode(rs.getString("pincode"));
        t.setCountry(rs.getString("country"));
        t.setFirstLoginUrls(parseUrlList(rs.getString("firstloginurl")));
        t.setAdditionalAttributes(parseJson(rs.getString("additionalattributes")));
        t.setActive(rs.getBoolean("isactive"));
        t.setPasswordGenerated(rs.getBoolean("passwordgenerated"));
        t.setVersion(rs.getInt("version"));
        t.setCreatedBy(rs.getString("createdby"));
        t.setModifiedBy(rs.getString("modifiedby"));
        t.setCreatedTime(rs.getLong("createdtime"));
        t.setModifiedTime(rs.getLong("modifiedtime"));
        t.setRequestId(rs.getString("requestid"));
        return t;
    };

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse additionalattributes JSON", e);
        }
    }

    private String writeJson(Map<String, Object> m) {
        // GORM JSONMap.Value: nil → "{}".
        if (m == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize additionalattributes JSON", e);
        }
    }

    /**
     * firstloginurl holds a JSON array. Rows written before it became a list hold a bare URL, so a
     * value that does not parse as JSON is read as a single-element list rather than failing — the
     * migration backfills those, but a row restored from an older dump must still load.
     */
    private Map<String, String> parseUrlList(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            // Older rows hold a bare URL or a JSON array, written before the links were labelled.
            // Read them under positional keys rather than failing, so a row from before this change
            // still loads and still returns the links it was emailed.
            try {
                List<String> legacy = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                Map<String, String> out = new LinkedHashMap<>();
                for (int i = 0; i < legacy.size(); i++) {
                    out.put("link" + (i + 1), legacy.get(i));
                }
                return out;
            } catch (Exception ignored) {
                return Map.of("link1", json);
            }
        }
    }

    /** Null and empty both store as SQL NULL: absent means nothing was emailed, not "an empty list". */
    private String writeUrlList(Map<String, String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize firstloginurl JSON", e);
        }
    }

    /** Inserts a new tenant. Unique violations are translated to a DUPLICATE_RECORD CustomException. */
    public void create(TenantEntity t) {
        try {
            jdbc.update("INSERT INTO " + TABLE + " (" + COLS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)",
                    t.getId(), t.getCode(), t.getName(), t.getEmail(), t.getPhone(), t.getAddress(),
                    t.getCity(), t.getState(), t.getPincode(), t.getCountry(), writeUrlList(t.getFirstLoginUrls()),
                    writeJson(t.getAdditionalAttributes()),
                    t.isActive(), t.isPasswordGenerated(), t.getVersion(), t.getCreatedBy(),
                    t.getModifiedBy(), t.getCreatedTime(), t.getModifiedTime(), t.getRequestId());
        } catch (RuntimeException e) {
            throw PgErrors.translate(e);
        }
    }

    /** Returns the entity, or null if not found (Go "nil, nil on not found"). */
    public TenantEntity getById(String id) {
        return takeOne("SELECT " + COLS + " FROM " + TABLE + " WHERE id = ? LIMIT 1", id);
    }

    public TenantEntity getByCode(String code) {
        return takeOne("SELECT " + COLS + " FROM " + TABLE + " WHERE code = ? LIMIT 1", code);
    }

    public TenantEntity getByEmail(String email) {
        return takeOne("SELECT " + COLS + " FROM " + TABLE + " WHERE email = ? LIMIT 1", email);
    }

    private TenantEntity takeOne(String sql, Object arg) {
        List<TenantEntity> rows = jdbc.query(sql, rowMapper, arg);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Escapes LIKE metacharacters so a filter containing % or _ still matches literally. */
    private static String likeLiteral(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static class Page {
        public final List<TenantEntity> rows;
        public final long total;
        public Page(List<TenantEntity> rows, long total) { this.rows = rows; this.total = total; }
    }

    /**
     * Exact match on code, exact case-insensitive match on email, case-insensitive substring
     * match on name; count + page ordered by createdtime DESC. Email is intentionally not a
     * substring match: it is not unique (one admin may own several tenants), so an exact filter
     * answers "which tenants does this admin own?" without matching a whole domain at once.
     */
    public Page list(String code, String name, String email, Boolean isActive, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        String c = code == null ? "" : code.strip();
        if (!c.isEmpty()) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("code = ?");
            args.add(c);
        }
        String n = name == null ? "" : name.strip();
        if (!n.isEmpty()) {
            where.append(where.isEmpty() ? " WHERE " : " AND ")
                    .append("LOWER(name) LIKE LOWER(?) ESCAPE E'\\\\'");
            args.add("%" + likeLiteral(n) + "%");
        }
        String em = email == null ? "" : email.strip();
        if (!em.isEmpty()) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("LOWER(email) = LOWER(?)");
            args.add(em);
        }
        // Filtering in SQL rather than after the fact keeps COUNT(*) and the page in agreement; a
        // post-query filter would leave totalCount and hasMore describing rows the caller never saw.
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
        List<TenantEntity> rows = jdbc.query(
                "SELECT " + COLS + " FROM " + TABLE + where
                        + " ORDER BY \"createdtime\" DESC LIMIT ? OFFSET ?",
                rowMapper, pageArgs.toArray());
        return new Page(rows, totalCount);
    }

    /** Full-row update by PK (GORM Save). Unique violations translated. */
    public void update(TenantEntity t) {
        try {
            jdbc.update("UPDATE " + TABLE + " SET code = ?, name = ?, email = ?, phone = ?, "
                            + "address = ?, city = ?, state = ?, pincode = ?, country = ?, "
                            + "firstloginurl = ?, additionalattributes = ?::jsonb, "
                            + "isactive = ?, passwordgenerated = ?, version = ?, createdby = ?, modifiedby = ?, "
                            + "createdtime = ?, modifiedtime = ?, requestid = ? WHERE id = ?",
                    t.getCode(), t.getName(), t.getEmail(), t.getPhone(), t.getAddress(), t.getCity(),
                    t.getState(), t.getPincode(), t.getCountry(), writeUrlList(t.getFirstLoginUrls()),
                    writeJson(t.getAdditionalAttributes()), t.isActive(),
                    t.isPasswordGenerated(), t.getVersion(), t.getCreatedBy(), t.getModifiedBy(),
                    t.getCreatedTime(), t.getModifiedTime(), t.getRequestId(), t.getId());
        } catch (RuntimeException e) {
            throw PgErrors.translate(e);
        }
    }

    /** Hard-deletes by primary key. */
    public void delete(String id) {
        jdbc.update("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }
}
