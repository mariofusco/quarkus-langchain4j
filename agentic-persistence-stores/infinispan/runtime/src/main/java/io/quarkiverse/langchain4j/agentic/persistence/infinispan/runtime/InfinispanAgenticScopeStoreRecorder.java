package io.quarkiverse.langchain4j.agentic.persistence.infinispan.runtime;

import java.util.function.Function;

import org.infinispan.client.hotrod.RemoteCacheManager;

import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.infinispan.client.InfinispanClientName;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class InfinispanAgenticScopeStoreRecorder {

    private final RuntimeValue<InfinispanAgenticScopeStoreConfig> runtimeConfig;

    public InfinispanAgenticScopeStoreRecorder(RuntimeValue<InfinispanAgenticScopeStoreConfig> runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    public Function<SyntheticCreationalContext<InfinispanAgenticScopeStore>, InfinispanAgenticScopeStore> storeFunction(
            String clientName) {
        return new Function<>() {
            @Override
            public InfinispanAgenticScopeStore apply(SyntheticCreationalContext<InfinispanAgenticScopeStore> context) {
                RemoteCacheManager cacheManager;
                if (clientName == null) {
                    cacheManager = context.getInjectedReference(RemoteCacheManager.class);
                } else {
                    cacheManager = context.getInjectedReference(RemoteCacheManager.class,
                            new InfinispanClientName.Literal(clientName));
                }
                InfinispanAgenticScopeStoreConfig config = runtimeConfig.getValue();
                return new InfinispanAgenticScopeStore(
                        cacheManager,
                        config.cacheName(),
                        config.ttlSeconds().orElse(null));
            }
        };
    }
}
