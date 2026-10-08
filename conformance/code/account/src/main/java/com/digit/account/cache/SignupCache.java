package com.digit.account.cache;

import com.digit.account.model.TenantCreateRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

/**
 * Buffers a {@link TenantCreateRequest} between OTP issuance (initiate / resend) and OTP
 * verification, keyed by the OTP {@code referenceId} with a TTL matching the OTP lifetime.
 * Mirrors Go internal/cache/redis_client.go.
 *
 * <p>Key format, value serialization, TTL and operations are byte-for-byte aligned with the Go
 * implementation:
 * <ul>
 *   <li>key  = {@code "signup:" + referenceId} (Go {@code fmt.Sprintf("signup:%s", requestID)})</li>
 *   <li>value = {@code json.Marshal(*TenantCreateRequest)} with Go {@code omitempty} semantics
 *       reproduced via a dedicated {@code NON_EMPTY} ObjectMapper</li>
 *   <li>TTL  = the OTP {@code expiresIn} in seconds, applied with {@code SET key val EX ttl}
 *       (Go {@code client.Set(ctx, key, data, ttl)})</li>
 * </ul>
 *
 * <p>Like Go ({@code cache.NewRedisClient} pings within a 5s timeout and {@code main.go}
 * {@code log.Fatal}s on failure), the connection is established eagerly at startup via
 * {@link #connect()} and an unreachable Redis fails the boot.
 */
public class SignupCache {

    private final RedisURI redisUri;
    private final ObjectMapper objectMapper;

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    public SignupCache(String address, String password, int db, ObjectMapper objectMapper) {
        String host = address;
        int port = 6379;
        int idx = address.lastIndexOf(':');
        if (idx >= 0) {
            host = address.substring(0, idx);
            try {
                port = Integer.parseInt(address.substring(idx + 1));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        RedisURI.Builder b = RedisURI.builder().withHost(host).withPort(port).withDatabase(db)
                .withTimeout(Duration.ofSeconds(5));
        if (password != null && !password.isEmpty()) {
            b.withPassword(password.toCharArray());
        }
        this.redisUri = b.build();
        // Dedicated mapper so the stored payload matches Go's json.Marshal of *TenantCreateRequest,
        // whose fields are all `omitempty` (password is a string+omitempty; phone/address/city/state/
        // pincode are *string; additionalAttributes is a map). NON_EMPTY drops null, empty string and
        // empty map, reproducing Go's omission so the serialized bytes are identical.
        this.objectMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    /**
     * Eagerly opens the connection and PINGs, mirroring Go cache.NewRedisClient. Throws when Redis
     * is unreachable so the caller can fail startup exactly like Go's {@code log.Fatal}.
     */
    public void connect() {
        synchronized (this) {
            if (connection == null) {
                RedisClient c = RedisClient.create(redisUri);
                try {
                    StatefulRedisConnection<String, String> conn = c.connect();
                    conn.sync().ping();
                    this.client = c;
                    this.connection = conn;
                } catch (RuntimeException e) {
                    try {
                        c.shutdown();
                    } catch (RuntimeException ignored) {
                        // best-effort cleanup
                    }
                    throw new RuntimeException("failed to connect to Redis: " + cause(e), e);
                }
            }
        }
    }

    private RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> conn = connection;
        if (conn == null) {
            connect();
            conn = connection;
        }
        return conn.sync();
    }

    private static String key(String referenceId) {
        return "signup:" + referenceId;
    }

    /** Stores the payload with the given TTL (seconds). Mirrors StoreSignupPayload. */
    public void store(String referenceId, TenantCreateRequest payload, long ttlSeconds) {
        String data;
        try {
            data = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("failed to marshal payload: " + cause(e), e);
        }
        try {
            if (ttlSeconds > 0) {
                // SET key value EX ttl — mirrors go-redis client.Set(ctx, key, data, ttl).
                commands().set(key(referenceId), data, SetArgs.Builder.ex(ttlSeconds));
            } else {
                commands().set(key(referenceId), data);
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to store payload in Redis: " + cause(e), e);
        }
    }

    /** Retrieves the payload, or null when it is absent or expired; throws only when Redis fails. */
    public TenantCreateRequest get(String referenceId) {
        String data;
        try {
            data = commands().get(key(referenceId));
        } catch (Exception e) {
            throw new RuntimeException("failed to retrieve payload from Redis: " + cause(e), e);
        }
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.readValue(data, TenantCreateRequest.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to unmarshal payload: " + cause(e), e);
        }
    }

    public void delete(String referenceId) {
        try {
            commands().del(key(referenceId));
        } catch (Exception e) {
            throw new RuntimeException("failed to delete payload from Redis: " + cause(e), e);
        }
    }

    public void close() {
        StatefulRedisConnection<String, String> conn = connection;
        if (conn != null) {
            conn.close();
        }
        RedisClient c = client;
        if (c != null) {
            c.shutdown();
        }
    }

    /**
     * Returns a non-empty message for an exception, mirroring Go where a wrapped error
     * ({@code %v}) is never the empty string. Falls back to the type name when the JVM leaves
     * {@code getMessage()} null (e.g. some ConnectException paths) so callers never embed "null".
     */
    private static String cause(Throwable t) {
        if (t == null) {
            return "";
        }
        String m = t.getMessage();
        if (m != null && !m.isEmpty()) {
            return m;
        }
        return t.getClass().getName();
    }
}
