package org.digit.idgen.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.digit.idgen.model.TemplateConfig;
import org.digit.idgen.model.TemplateRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TemplateRepository {

    private static final String COLUMNS =
            "id, tenantid, templatecode, version, config, \"createdTime\", \"createdBy\", "
                    + "\"modifiedTime\", \"modifiedBy\", requestid";

    /**
     * Mapper for the config JSONB column only — deliberately NOT a Spring bean:
     * a JsonMapper bean would make Boot's auto-configured HTTP mapper back off
     * and silently take over MVC serialization. Nulls are serialized (Go marshaled
     * every field, {@code "padding": null} included); unknown fields tolerated.
     */
    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // real Go-era rows hold pre-validation scopes like "daily"; Go's string
            // type read them fine — without this, one legacy row 400s a whole search
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private final JdbcClient jdbc;
    private final RowMapper<TemplateRow> rowMapper = (rs, rowNum) -> map(rs);

    public TemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private TemplateRow map(ResultSet rs) throws SQLException {
        return new TemplateRow(
                rs.getObject("id", UUID.class),
                rs.getString("tenantid"),
                rs.getString("templatecode"),
                rs.getInt("version"),
                MAPPER.readValue(rs.getString("config"), TemplateConfig.class),
                rs.getLong("createdTime"),
                rs.getString("createdBy"),
                rs.getLong("modifiedTime"),
                rs.getString("modifiedBy"),
                rs.getString("requestid"));
    }

    public boolean existsByCode(String tenantId, String templateCode) {
        return countVersions(tenantId, templateCode) > 0;
    }

    public Optional<TemplateRow> findLatest(String tenantId, String templateCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM idgen_templates "
                        + "WHERE tenantid = ? AND templatecode = ? ORDER BY version DESC LIMIT 1")
                .params(tenantId, templateCode)
                .query(rowMapper)
                .optional();
    }

    public Optional<TemplateRow> findByVersion(String tenantId, String templateCode, int version) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM idgen_templates "
                        + "WHERE tenantid = ? AND templatecode = ? AND version = ?")
                .params(tenantId, templateCode, version)
                .query(rowMapper)
                .optional();
    }

    public List<TemplateRow> findByIds(String tenantId, List<UUID> ids) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM idgen_templates "
                        + "WHERE tenantid = :tenantId AND id IN (:ids)")
                .param("tenantId", tenantId)
                .param("ids", ids)
                .query(rowMapper)
                .list();
    }

    /** Latest version of every template in the tenant (same correlated subquery as Go). */
    public List<TemplateRow> findAllLatest(String tenantId, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM idgen_templates WHERE tenantid = ? AND version = ("
                        + "SELECT MAX(t2.version) FROM idgen_templates t2 "
                        + "WHERE t2.tenantid = idgen_templates.tenantid "
                        + "AND t2.templatecode = idgen_templates.templatecode) "
                        + "ORDER BY templatecode ASC LIMIT ? OFFSET ?")
                .params(tenantId, limit, offset)
                .query(rowMapper)
                .list();
    }

    public void insert(TemplateRow row) {
        jdbc.sql("INSERT INTO idgen_templates (id, tenantid, templatecode, version, config, "
                        + "\"createdTime\", \"createdBy\", \"modifiedTime\", \"modifiedBy\", requestid) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)")
                .params(row.id(), row.tenantId(), row.templateCode(), row.version(),
                        MAPPER.writeValueAsString(row.config()),
                        row.createdTime(), row.createdBy(), row.modifiedTime(), row.modifiedBy(),
                        row.requestId())
                .update();
    }

    public void delete(String tenantId, String templateCode, int version) {
        jdbc.sql("DELETE FROM idgen_templates WHERE tenantid = ? AND templatecode = ? AND version = ?")
                .params(tenantId, templateCode, version)
                .update();
    }

    public long countVersions(String tenantId, String templateCode) {
        return jdbc.sql("SELECT count(*) FROM idgen_templates WHERE tenantid = ? AND templatecode = ?")
                .params(tenantId, templateCode)
                .query(Long.class)
                .single();
    }
}
