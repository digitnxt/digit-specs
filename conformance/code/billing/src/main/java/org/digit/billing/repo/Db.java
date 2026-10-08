package org.digit.billing.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * DB-read helpers shared by the row mappers. HR-1 (Phase 6 byte-diff finding):
 * shopspring's Scan trims trailing zeros — Go renders a numeric(18,2) 100.50 as
 * "100.5" — so Java strips at read too. Values produced by arithmetic keep
 * their natural scale in both stacks.
 */
final class Db {

    private Db() {
    }

    static BigDecimal dec(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.stripTrailingZeros();
    }
}
