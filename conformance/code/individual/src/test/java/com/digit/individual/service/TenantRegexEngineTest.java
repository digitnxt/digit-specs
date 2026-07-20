package com.digit.individual.service;

import com.digit.individual.config.IndividualProperties;
import com.digit.individual.model.Config;
import org.digit.tracer.model.CustomException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies tenant regex validation runs on the RE2 engine (RE2/J), matching Go's {@code regexp}
 * package. RE2 has no backtracking, so lookahead/backreference constructs are rejected at compile
 * time — exactly as Go rejects them on POST /configs, and unlike {@code java.util.regex} which would
 * accept them. validateConfig only touches the regex fields here, so null collaborators are fine.
 */
class TenantRegexEngineTest {

    private final RequestValidator validator = new RequestValidator(null, null, null, new IndividualProperties());

    private static Config mobileRegex(String r) {
        Config c = new Config();
        c.setMobileRegex(r);
        return c;
    }

    private static Config nameRegex(String r) {
        Config c = new Config();
        c.setNameRegex(r);
        return c;
    }

    @Test
    void plainRegex_isAccepted() {
        assertDoesNotThrow(() -> validator.validateConfig(mobileRegex("^[0-9]{6,15}$")));
        assertDoesNotThrow(() -> validator.validateConfig(nameRegex("^[a-zA-Z ]+$")));
    }

    @Test
    void lookahead_isRejected_likeGoRE2() {
        // Accepted by java.util.regex, rejected by RE2/RE2J — must now throw.
        assertThrows(CustomException.class,
                () -> validator.validateConfig(mobileRegex("^(?=.*[0-9]).+$")));
    }

    @Test
    void backreference_isRejected_likeGoRE2() {
        assertThrows(CustomException.class,
                () -> validator.validateConfig(nameRegex("(\\w+)\\s+\\1")));
    }

    /**
     * #11 byte-length parity: length caps count UTF-8 bytes (Go's len()), not UTF-16 units.
     * 171 CJK chars = 513 bytes > 512, so nameRegex must be rejected even though its UTF-16
     * length (171) is well under the cap — validateConfig checks maxLen before compiling.
     */
    @Test
    void lengthCap_countsUtf8Bytes_notChars() {
        String cjk = "中".repeat(171); // 171 chars, 513 UTF-8 bytes
        assertThrows(CustomException.class, () -> validator.validateConfig(nameRegex(cjk)));
        // 170 chars = 510 bytes, under the 512 cap → accepted.
        assertDoesNotThrow(() -> validator.validateConfig(nameRegex("中".repeat(170))));
    }
}