package io.quarkiverse.langchain4j.agentic.persistence.infinispan;

import static io.quarkus.runtime.annotations.ConfigPhase.BUILD_TIME;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

@ConfigRoot(phase = BUILD_TIME)
@ConfigMapping(prefix = "quarkus.langchain4j.agentic-persistence.infinispan")
public interface InfinispanAgenticScopeStoreBuildTimeConfig {

    /**
     * The name of the Infinispan client to use. These clients are configured by means of the
     * {@code quarkus-infinispan-client} extension. If unspecified, it will use the default Infinispan client.
     */
    Optional<String> clientName();
}
