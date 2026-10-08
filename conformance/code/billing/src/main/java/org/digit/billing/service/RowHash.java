package org.digit.billing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.digit.billing.model.Json;

/**
 * Audit row hash: SHA-256 hex of the row's canonical JSON. Same format as Go
 * (64-hex); values intentionally differ from Go's (different JSON shapes) —
 * checkpoint-approved non-parity, nothing reads these hashes.
 */
public final class RowHash {

    private RowHash() {
    }

    public static String of(Object row) {
        try {
            byte[] json = Json.MAPPER.writeValueAsBytes(row);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
