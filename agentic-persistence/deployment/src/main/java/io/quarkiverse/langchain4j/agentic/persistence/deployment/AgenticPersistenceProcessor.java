package io.quarkiverse.langchain4j.agentic.persistence.deployment;

import java.util.Optional;

import io.quarkiverse.langchain4j.agentic.persistence.runtime.AgenticPersistenceRecorder;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class AgenticPersistenceProcessor {

    private static final String FEATURE = "langchain4j-agentic-persistence";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void wireAgenticScopeStore(AgenticPersistenceRecorder recorder,
            Optional<AgenticScopeStoreBuildItem> storeItem) {
        if (storeItem.isPresent()) {
            recorder.initializeAgenticScopePersister();
        }
    }
}
