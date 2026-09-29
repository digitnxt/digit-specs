package com.digit.account.repository;

import org.digit.tracer.model.CustomException;
import org.springframework.http.HttpStatus;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Translates a raw Postgres unique-constraint violation (SQLSTATE 23505) into a business
 * {@link CustomException} ({@code DUPLICATE_RECORD}), matching on the constraint name exactly like
 * Go's translatePgError. Anything else (genuine infra failure) is returned unchanged so it
 * propagates to the tracer's generic 500 handler.
 */
public final class PgErrors {
    private PgErrors() {}

    /** Duplicate-tenant-code message (mirrors validator.ErrDuplicateTenantCode). */
    static final String DUPLICATE_TENANT_CODE_MSG = "Tenant with this code already exists";
    /** Duplicate-config-key message (mirrors validator.ErrDuplicateConfigKey). */
    static final String DUPLICATE_CONFIG_KEY_MSG =
            "A configuration with this configKey already exists for the tenant";

    public static RuntimeException translate(RuntimeException err) {
        if (err == null) {
            return null;
        }
        Throwable t = err;
        while (t != null) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("23505".equals(state)) {
                    String constraint = constraintName(sql);
                    String cn = constraint == null ? "" : constraint.toLowerCase(Locale.ROOT);
                    if (cn.contains("code")) {
                        return new CustomException("DUPLICATE_RECORD", DUPLICATE_TENANT_CODE_MSG,
                                HttpStatus.CONFLICT);
                    }
                    if (cn.contains("config") && cn.contains("key")) {
                        return new CustomException("DUPLICATE_RECORD", DUPLICATE_CONFIG_KEY_MSG,
                                HttpStatus.CONFLICT);
                    }
                }
                return err;
            }
            t = t.getCause();
        }
        return err;
    }

    private static String constraintName(SQLException sql) {
        if (sql instanceof PSQLException pg) {
            ServerErrorMessage sem = pg.getServerErrorMessage();
            if (sem != null) {
                return sem.getConstraint();
            }
        }
        return null;
    }
}
