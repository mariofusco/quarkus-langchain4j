package io.quarkiverse.langchain4j.agentic.persistence.infinispan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;

import dev.langchain4j.agentic.scope.AgenticScopeStore;
import io.quarkiverse.langchain4j.agentic.persistence.deployment.AgenticScopeStoreBuildItem;
import io.quarkiverse.langchain4j.agentic.persistence.infinispan.runtime.InfinispanAgenticScopeStore;
import io.quarkiverse.langchain4j.agentic.persistence.infinispan.runtime.InfinispanAgenticScopeStoreRecorder;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.infinispan.client.InfinispanClientName;
import io.quarkus.infinispan.client.deployment.InfinispanClientNameBuildItem;

public class InfinispanAgenticScopeStoreProcessor {

    public static final DotName INFINISPAN_AGENTIC_SCOPE_STORE = DotName.createSimple(InfinispanAgenticScopeStore.class);

    private static final String FEATURE = "langchain4j-agentic-persistence-infinispan";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    public InfinispanClientNameBuildItem requestInfinispanClient(
            InfinispanAgenticScopeStoreBuildTimeConfig config) {
        return new InfinispanClientNameBuildItem(config.clientName().orElse("<default>"));
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    public void createBean(
            BuildProducer<SyntheticBeanBuildItem> beanProducer,
            BuildProducer<AgenticScopeStoreBuildItem> storeProducer,
            InfinispanAgenticScopeStoreRecorder recorder,
            InfinispanAgenticScopeStoreBuildTimeConfig buildTimeConfig) {
        String clientName = buildTimeConfig.clientName().orElse(null);
        AnnotationInstance infinispanClientQualifier;
        if (clientName == null) {
            infinispanClientQualifier = AnnotationInstance.builder(Default.class).build();
        } else {
            infinispanClientQualifier = AnnotationInstance.builder(InfinispanClientName.class)
                    .add("value", clientName)
                    .build();
        }

        beanProducer.produce(SyntheticBeanBuildItem
                .configure(INFINISPAN_AGENTIC_SCOPE_STORE)
                .types(ClassType.create(AgenticScopeStore.class))
                .setRuntimeInit()
                .defaultBean()
                .unremovable()
                .scope(ApplicationScoped.class)
                .addInjectionPoint(ClassType.create(DotName.createSimple(RemoteCacheManager.class)),
                        infinispanClientQualifier)
                .createWith(recorder.storeFunction(clientName))
                .done());
        storeProducer.produce(new AgenticScopeStoreBuildItem());
    }
}
