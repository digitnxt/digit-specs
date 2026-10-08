package org.digit.billing.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Final-review pin (finding #2): DB reads trim like shopspring's Scan (HR-1b). */
class DbTest {

    @Test
    void dbReadStripsScaleTwoLikeShopspringScan() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBigDecimal("v")).thenReturn(new BigDecimal("100.50"));
        assertEquals("100.5", Db.dec(rs, "v").toPlainString());

        when(rs.getBigDecimal("v")).thenReturn(new BigDecimal("0.00"));
        assertEquals("0", Db.dec(rs, "v").toPlainString());

        when(rs.getBigDecimal("v")).thenReturn(new BigDecimal("25.75"));
        assertEquals("25.75", Db.dec(rs, "v").toPlainString());

        when(rs.getBigDecimal("v")).thenReturn(null);
        assertNull(Db.dec(rs, "v"));
    }
}
