package com.hedgefund.ai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * In-memory cache for Financial Datasets API responses.
 * Equivalent to the Python Cache class.
 */
@Component
public class ApiCache {

    private final Cache<String, List<?>> cache;

    public ApiCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(Duration.ofHours(1))
                .build();
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> get(String key) {
        return (List<T>) cache.getIfPresent(key);
    }

    public <T> void put(String key, List<T> data) {
        cache.put(key, data);
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}

