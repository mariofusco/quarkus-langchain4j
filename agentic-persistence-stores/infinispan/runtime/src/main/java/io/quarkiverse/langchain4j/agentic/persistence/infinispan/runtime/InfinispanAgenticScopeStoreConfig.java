package io.quarkiverse.langchain4j.agentic.persistence.infinispan.runtime;

import static io.quarkus.runtime.annotations.ConfigPhase.RUN_TIME;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration of the Infinispan agentic scope store.
 */
@ConfigRoot(phase = RUN_TIME)
@ConfigMapping(prefix = "quarkus.langchain4j.agentic-persistence.infinispan")
public interface InfinispanAgenticScopeStoreConfig {

    /**
     * Name of the Infinispan cache used to persist agentic scope state.
     * If this cache doesn't exist, it will be created.
     */
    @WithDefault("agentic-scope-cache")
    String cacheName();

    /**
     * Optional time-to-live for cached entries, in seconds.
     * When set, entries expire after this duration. No expiry by default.
     */
    Optional<Long> ttlSeconds();
}
