package org.digit.billing.config;

import javax.sql.DataSource;

import com.digit.tenant.migration.config.TenantMigrationProperties;
import com.digit.tenant.migration.validators.TenantIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Points {@code search_path} at a tenant's schema from a thread the tenant-migration filter never
 * runs on — i.e. the pub/sub consumer threads.
 *
 * <p>The library's filter covers request threads only. A consumer takes its tenant from the message
 * body and gets a connection carrying the default {@code search_path}, so with per-tenant schemas
 * enabled a bulk bill job would read and write {@code public} while the API served the tenant's
 * schema. Silent, and wrong in both directions.
 *
 * <p>Deliberately applied <em>inside</em> each transaction rather than by wrapping the handler in one.
 * {@code SET LOCAL} is scoped to the current transaction, so it has to be the first statement of the
 * transaction it applies to — and doing it per transaction is what lets
 * {@code processBulkBillJob} keep its three independent commits. Expiring a bill that is genuinely
 * past its expiry is correct on its own merit; it should not be undone because a later, unrelated
 * generation step failed.
 *
 * <p>This is not duplicating something the library already provides: tenant-migration performs the
 * equivalent {@code SET LOCAL} only inside {@code TenantTransactionFilter}'s private request
 * handling, and exposes no callable form of it. What it does expose — {@link TenantIds} for
 * validation and quoting, and {@link TenantMigrationProperties} for the enabled flag — is reused
 * here rather than reimplemented, so the library stays the single source of truth for both the
 * tenant-id rules and the switch. If tenant-migration ever grows a supported hook for non-request
 * threads, this class should be deleted in favour of it.
 */
@Component
public class TenantSchema {

    private final JdbcTemplate jdbc;
    private final TenantMigrationProperties props;

    public TenantSchema(DataSource dataSource, TenantMigrationProperties props) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.props = props;
    }

    /**
     * Must be called as the first statement inside an open transaction; {@code SET LOCAL} outside one
     * is silently discarded by Postgres.
     *
     * <p>No-op when per-tenant schemas are off, because everything then lives in {@code public},
     * which is already where the connection points.
     *
     * @param tenantId taken from the message body — untrusted, so it is validated before it becomes
     *                 a schema name, exactly as the library does for the request path
     */
    public void applyTo(String tenantId) {
        if (!props.isEnabled()) {
            return;
        }
        jdbc.execute("SET LOCAL search_path TO " + TenantIds.quoteIdent(TenantIds.validate(tenantId)));
    }
}
