package com.digit.individual.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Keyed HMAC-SHA256 hashing of mobile numbers, hex-encoded. Mirrors Go common.HashMobileNumber.
 *
 * <p>It is a deterministic "blind index": the same (secret, mobile) always yields the same hash, so it
 * powers indexed exact-match search and the uniqueness check — while the secret pepper (held in config,
 * never stored in the DB) prevents an attacker who obtains the hash column from brute-forcing the small
 * mobile-number keyspace back to plaintext. A plain digest would be trivially reversible for phone
 * numbers; HMAC with a secret key is not. An empty mobile yields an empty hash (no index value).
 */
public final class HashUtil {
    private HashUtil() {}

    public static String hashMobileNumber(byte[] secret, String mobile) {
        if (mobile == null || mobile.isEmpty()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // An empty key is permitted (Vault-off deployments). Java's SecretKeySpec rejects a
            // zero-length key, so a single zero byte stands in for "no pepper". This stays
            // byte-compatible with Go's empty-key hash: HMAC zero-pads the key to the 64-byte block
            // size, so an empty key and a one-zero-byte key both pad to the same 64 zero bytes and
            // yield identical output — the two services' hashes match in every mode.
            byte[] key = secret.length == 0 ? new byte[]{0} : secret;
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(mobile.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }
}