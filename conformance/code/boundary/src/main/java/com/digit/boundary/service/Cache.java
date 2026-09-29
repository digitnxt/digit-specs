package com.digit.boundary.service;

/**
 * Cache abstraction mirroring Go {@code pkg/cache} (the {@code Cache} + {@code ExtendedCache}
 * interfaces). Implementations: {@link InMemoryCache} (Go NewInMemoryCache) and
 * {@link RedisCache} (Go NewRedisCache, go-redis backed). The active implementation is selected by
 * {@code CACHE_TYPE} ({@code redis} | in-memory), exactly as in Go {@code cmd/server/main.go}.
 *
 * <p>Values are the serialized JSON strings the services produce; this matches Go, whose Redis cache
 * only accepts string values and stores them with a 24h TTL (in-memory has no expiry, as in Go).
 * Keys follow Go's format, e.g. {@code <tenant>:boundary:search:<codes>}.
 */
public interface Cache {

    /** Returns the cached value or {@code null} on miss (Go returns ("key not found") error -> null). */
    String get(String key);

    /** Stores the value (Redis: 24h TTL; in-memory: no expiry). No-op when {@code value} is null. */
    void set(String key, String value);

    void delete(String key);

    /** Removes every key with the given prefix (Go ExtendedCache.DeleteByPrefix, SCAN {@code prefix*}). */
    void deleteByPrefix(String prefix);
}
