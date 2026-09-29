package com.digit.boundary.service;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed cache, mirroring Go {@code pkg/cache.RedisCache} (go-redis). Selected when
 * {@code CACHE_TYPE=redis} (the Go default). Behaviour is matched to go-redis:
 *
 * <ul>
 *   <li><b>Set</b>: {@code SETEX key 86400 value} — 24h TTL, mirroring Go's
 *       {@code client.Set(ctx, key, val, 24*time.Hour)} (same policy as localization). Value is the
 *       raw JSON string the services produce; Lettuce's {@code StringCodec} writes plain UTF-8,
 *       byte-identical to go-redis storing a Go {@code string}.</li>
 *   <li><b>Get</b>: {@code GET key}; a Redis nil reply is a miss and returns {@code null}
 *       (Go returns the "key not found" error, which the services treat as a miss).</li>
 *   <li><b>Delete</b>: {@code DEL key}.</li>
 *   <li><b>DeleteByPrefix</b>: SCAN with {@code MATCH prefix*} COUNT 100 (Go uses SCAN, not KEYS),
 *       then DEL of all matched keys.</li>
 * </ul>
 *
 * Uses {@link io.lettuce.core} (Lettuce) as the client, matching go-redis behaviour.
 */
public class RedisCache implements Cache, AutoCloseable {

    /** 24h TTL, mirrors Go's {@code keyExpiration = 24 * time.Hour} — bounds worst-case staleness. */
    private static final long TTL_SECONDS = 24 * 60 * 60L;
    private static final int SCAN_COUNT = 100;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    /**
     * @param addr     Redis address in {@code host:port} form (Go CACHE_REDIS_ADDR)
     * @param password Redis password, empty/null for no auth (Go CACHE_REDIS_PASSWORD)
     * @param db       Redis database index (Go CACHE_REDIS_DB)
     */
    public RedisCache(String addr, String password, int db) {
        RedisURI.Builder uri = RedisURI.Builder.redis(host(addr), port(addr)).withDatabase(db);
        if (password != null && !password.isEmpty()) {
            uri.withPassword(password.toCharArray());
        }
        this.client = RedisClient.create(uri.build());
        this.connection = client.connect();
        this.commands = connection.sync();
    }

    @Override
    public String get(String key) {
        return commands.get(key); // null on Redis nil -> treated as a cache miss
    }

    @Override
    public void set(String key, String value) {
        if (value == null) {
            return;
        }
        // go-redis: client.Set(ctx, key, value, 24*time.Hour) -> SET with 24h expiry.
        commands.setex(key, TTL_SECONDS, value);
    }

    @Override
    public void delete(String key) {
        commands.del(key);
    }

    @Override
    public void deleteByPrefix(String prefix) {
        deletePattern(prefix + "*");
    }

    /**
     * Deletes every key matching the given glob pattern via SCAN {@code MATCH pattern} then DEL,
     * mirroring Go {@code RedisCache.DeletePattern}. Used for scoped search-cache invalidation
     * (e.g. {@code <tenant>:boundary:search:*<code>*}).
     */
    public void deletePattern(String pattern) {
        List<String> keys = new ArrayList<>();
        ScanArgs args = ScanArgs.Builder.matches(pattern).limit(SCAN_COUNT);
        ScanCursor cursor = ScanCursor.INITIAL;
        do {
            KeyScanCursor<String> result = commands.scan(cursor, args);
            keys.addAll(result.getKeys());
            cursor = result;
        } while (!cursor.isFinished());

        if (!keys.isEmpty()) {
            commands.del(keys.toArray(new String[0]));
        }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    private static String host(String addr) {
        if (addr == null || addr.isEmpty()) {
            return "localhost";
        }
        int idx = addr.lastIndexOf(':');
        return idx > 0 ? addr.substring(0, idx) : addr;
    }

    private static int port(String addr) {
        if (addr == null) {
            return 6379;
        }
        int idx = addr.lastIndexOf(':');
        if (idx < 0 || idx == addr.length() - 1) {
            return 6379;
        }
        try {
            return Integer.parseInt(addr.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 6379;
        }
    }
}
