package org.digit.idgen.repo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.digit.idgen.model.SequenceScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sequence storage — two mechanisms by scope (as in Go):
 * GLOBAL uses a real Postgres sequence (created at template create, never reset);
 * DAILY/MONTHLY/YEARLY use an atomic upsert counter row per scope window in
 * idgen_sequence_resets (reset is lazy: a new window key is simply a new row).
 */
@Repository
public class SequenceRepository {

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YEAR_KEY = DateTimeFormatter.ofPattern("yyyy");

    private final JdbcClient jdbc;

    public SequenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String scopeKey(SequenceScope scope, LocalDate today) {
        return switch (scope) {
            case DAILY -> today.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case MONTHLY -> today.format(MONTH_KEY);
            case YEARLY -> today.format(YEAR_KEY);
            case GLOBAL -> "";
        };
    }

    // ── template lifecycle ────────────────────────────────────────────────────

    /** seqName comes from SequenceNames (hex chars only) — safe to inline in DDL. */
    public void createSequence(String seqName, int start) {
        jdbc.sql("CREATE SEQUENCE IF NOT EXISTS " + seqName
                + " START WITH " + start + " INCREMENT BY 1 NO CYCLE").update();
    }

    public void insertLookup(String seqName, String tenantId, String templateCode, String requestId) {
        jdbc.sql("INSERT INTO idgen_sequence_lookup (id, seqname, tenantid, templatecode, requestid) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                .params(UUID.randomUUID(), seqName, tenantId, templateCode, requestId)
                .update();
    }

    public void dropSequence(String seqName) {
        jdbc.sql("DROP SEQUENCE IF EXISTS " + seqName).update();
    }

    public void deleteLookup(String tenantId, String templateCode) {
        jdbc.sql("DELETE FROM idgen_sequence_lookup WHERE tenantid = ? AND templatecode = ?")
                .params(tenantId, templateCode)
                .update();
    }

    public void deleteResets(String tenantId, String templateCode) {
        jdbc.sql("DELETE FROM idgen_sequence_resets WHERE tenantid = ? AND templatecode = ?")
                .params(tenantId, templateCode)
                .update();
    }

    // ── value allocation ──────────────────────────────────────────────────────

    public long nextValue(String seqName) {
        return jdbc.sql("SELECT nextval('" + seqName + "')").query(Long.class).single();
    }

    public List<Long> nextValues(String seqName, int count) {
        return jdbc.sql("SELECT nextval('" + seqName + "') FROM generate_series(1, ?)")
                .param(count)
                .query(Long.class)
                .list();
    }

    /**
     * Reserves {@code count} contiguous values in the scope window (single atomic
     * statement). {@code lastvalue} holds the highest value dispensed for the window;
     * a fresh window's first block is [start .. start+count-1], honouring {@code start}
     * exactly (as GLOBAL does). NB: this diverges from the Go service, whose RETURNING
     * subtracts count-1 and so dispenses start+1 first — a Go bug not reproduced here.
     * No cutover migration ships with this fix: a window already advanced under the Go
     * convention re-dispenses its last value once.
     */
    public long reserveScoped(String tenantId, String templateCode, String scopeKey, int start, int count) {
        return jdbc.sql("""
                        INSERT INTO idgen_sequence_resets (id, tenantid, templatecode, scopekey, lastvalue)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (tenantid, templatecode, scopekey)
                        DO UPDATE SET lastvalue = idgen_sequence_resets.lastvalue + ?
                        RETURNING lastvalue - ? + 1 AS first_value
                        """)
                .params(UUID.randomUUID(), tenantId, templateCode, scopeKey,
                        (long) start + count - 1, count, count)
                .query(Long.class)
                .single();
    }
}
