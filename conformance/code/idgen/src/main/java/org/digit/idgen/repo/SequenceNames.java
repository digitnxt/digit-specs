package org.digit.idgen.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic Postgres sequence names: {@code seq_v1_<sha1hex("tenant:code")>}.
 * Must match the Go implementation byte-for-byte — existing databases hold
 * sequences under these names. SHA1 is used as a collision-free name encoder,
 * not for security.
 */
public final class SequenceNames {

    public static String of(String tenantId, String templateCode) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((tenantId + ":" + templateCode).getBytes(StandardCharsets.UTF_8));
            return "seq_v1_" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private SequenceNames() {
    }
}
