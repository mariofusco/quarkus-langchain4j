package io.quarkiverse.langchain4j.agentic.persistence.infinispan.runtime;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.commons.configuration.StringConfiguration;

import dev.langchain4j.agentic.scope.AgenticScopeKey;
import dev.langchain4j.agentic.scope.AgenticScopeSerializer;
import dev.langchain4j.agentic.scope.AgenticScopeStore;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;

public class InfinispanAgenticScopeStore implements AgenticScopeStore {

    private static final String CACHE_CONFIG = """
            <distributed-cache name="%s">
              <encoding media-type="text/plain"/>
            </distributed-cache>
            """;

    private final RemoteCache<String, String> cache;
    private final Long ttlSeconds;

    public InfinispanAgenticScopeStore(RemoteCacheManager cacheManager, String cacheName, Long ttlSeconds) {
        this.cache = cacheManager.administration()
                .getOrCreateCache(cacheName, new StringConfiguration(String.format(CACHE_CONFIG, cacheName)));
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public boolean save(AgenticScopeKey key, DefaultAgenticScope scope) {
        String cacheKey = toCacheKey(key);
        String json = AgenticScopeSerializer.toJson(scope);
        if (ttlSeconds != null) {
            cache.put(cacheKey, json, ttlSeconds, TimeUnit.SECONDS);
        } else {
            cache.put(cacheKey, json);
        }
        return true;
    }

    @Override
    public Optional<DefaultAgenticScope> load(AgenticScopeKey key) {
        String json = cache.get(toCacheKey(key));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(AgenticScopeSerializer.fromJson(json));
    }

    @Override
    public boolean delete(AgenticScopeKey key) {
        return cache.remove(toCacheKey(key)) != null;
    }

    @Override
    public Set<AgenticScopeKey> getAllKeys() {
        return cache.keySet().stream()
                .map(InfinispanAgenticScopeStore::fromCacheKey)
                .collect(Collectors.toSet());
    }

    private static String toCacheKey(AgenticScopeKey key) {
        return key.agentId() + "::" + key.memoryId();
    }

    private static AgenticScopeKey fromCacheKey(String cacheKey) {
        int separator = cacheKey.indexOf("::");
        if (separator < 0) {
            return new AgenticScopeKey(cacheKey, "");
        }
        return new AgenticScopeKey(cacheKey.substring(0, separator), cacheKey.substring(separator + 2));
    }
}
