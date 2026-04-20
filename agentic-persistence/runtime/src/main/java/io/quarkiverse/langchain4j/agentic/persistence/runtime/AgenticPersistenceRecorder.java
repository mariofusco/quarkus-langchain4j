package io.quarkiverse.langchain4j.agentic.persistence.runtime;

import jakarta.enterprise.inject.spi.CDI;

import dev.langchain4j.agentic.scope.AgenticScopePersister;
import dev.langchain4j.agentic.scope.AgenticScopeStore;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class AgenticPersistenceRecorder {

    public void initializeAgenticScopePersister() {
        AgenticScopeStore store = CDI.current().select(AgenticScopeStore.class).get();
        AgenticScopePersister.setStore(store);
    }
}
