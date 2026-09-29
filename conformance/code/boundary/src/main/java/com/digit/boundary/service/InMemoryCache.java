package com.digit.boundary.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache, mirroring Go {@code pkg/cache.InMemoryCache} (NewInMemoryCache + the
 * ExtendedCache DeleteByPrefix used for search-cache invalidation). Selected when
 * {@code CACHE_TYPE} is not {@code redis}. Values are the serialized JSON strings the services
 * produce, matching Go's cache usage.
 */
public class InMemoryCache implements Cache {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        return store.get(key);
    }

    @Override
    public void set(String key, String value) {
        if (value != null) {
            store.put(key, value);
        }
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public void deleteByPrefix(String prefix) {
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
