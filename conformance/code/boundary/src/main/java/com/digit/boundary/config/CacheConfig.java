package com.digit.boundary.config;

import com.digit.boundary.service.Cache;
import com.digit.boundary.service.InMemoryCache;
import com.digit.boundary.service.RedisCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the cache backend, mirroring Go {@code cmd/server/main.go}:
 * {@code CACHE_TYPE=redis} -> {@link RedisCache} (the Go default), anything else -> {@link InMemoryCache}.
 * Config comes from the same env vars Go uses (CACHE_TYPE, CACHE_REDIS_ADDR/PASSWORD/DB).
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    // Default (inferred) destroy method: Spring calls AutoCloseable.close() on RedisCache at
    // shutdown and skips it for InMemoryCache (which is not AutoCloseable).
    @Bean
    public Cache cache(BoundaryProperties props) {
        BoundaryProperties.CacheCfg cfg = props.getCache();
        if ("redis".equals(cfg.getType())) {
            BoundaryProperties.CacheRedis r = cfg.getRedis();
            log.info("Using Redis cache: addr={}", r.getAddress());
            return new RedisCache(r.getAddress(), r.getPassword(), r.getDb());
        }
        log.info("Using in-memory cache");
        return new InMemoryCache();
    }
}
