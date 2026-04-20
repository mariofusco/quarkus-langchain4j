# Quarkus LangChain4j - Agentic Persistence - Infinispan

Infinispan-backed implementation of `AgenticScopeStore` for persisting agentic scope state. Adding this dependency to a Quarkus application enables long-running agentic workflows (e.g., HumanInTheLoop approval flows) to survive pod restarts by storing scope state in an Infinispan cluster.

## Design Principles

### JSON-over-String Storage

The store uses `RemoteCache<String, String>` with `text/plain` media encoding. Scope state is serialized to JSON via the upstream `AgenticScopeSerializer` and stored as plain strings. This avoids ProtoStream marshalling complexity and makes cached data inspectable through the Infinispan console or CLI.

### Automatic Cache Creation

The store creates the Infinispan cache on first use if it doesn't exist (`getOrCreateCache`), using a distributed cache configuration with `text/plain` encoding. No manual cache setup is required in the Infinispan server configuration.

### Quarkus DevServices Integration

In development mode, the extension automatically configures the Infinispan DevServices container image version via `DevServicesConfigBuilderCustomizer`, matching the server version to the client library on the classpath. No additional configuration is needed to get started.

### Synthetic CDI Bean

The `InfinispanAgenticScopeStore` is registered as a synthetic CDI bean typed as `AgenticScopeStore`. This means:
- It is created at runtime init (after the Infinispan `RemoteCacheManager` is available)
- It is application-scoped and unremovable
- It is a `@DefaultBean`, so applications can override it with a custom `AgenticScopeStore` if needed
- Named Infinispan clients are supported via the `client-name` build-time config

## How It Works

### Persistence Lifecycle

When this extension is on the classpath, the core `agentic-persistence` extension detects the `AgenticScopeStoreBuildItem` and activates the wiring. From that point:

1. **After each agent step**: The framework calls `store.save(key, scope)` to checkpoint the current state. If the application crashes mid-workflow, the scope can be recovered up to the last completed agent step.
2. **On scope access**: When `AgenticScopeRegistry.get(memoryId)` doesn't find the scope in memory, it calls `store.load(key)` to restore it from Infinispan.
3. **On eviction**: `store.delete(key)` removes the entry from the Infinispan cache.

### Cache Key Format

Scope entries are keyed as `{agentId}::{memoryId}`, where:
- `agentId` is the fully qualified class name of the root workflow interface
- `memoryId` is the application-provided identifier (e.g., a ticket ID, document ID, or session ID)

### Serialization

All serialization is handled by the upstream `AgenticScopeSerializer`:
- `toJson(scope)` produces a JSON string containing the scope's state map, agent invocations, conversation context, kind, and memoryId
- `fromJson(json)` reconstructs a `DefaultAgenticScope` from the JSON, including proper deserialization of `PendingResponse` objects for HumanInTheLoop recovery

## Module Structure

```
agentic-persistence-stores/infinispan/
  pom.xml                              # Parent POM (packaging=pom)
  deployment/
    pom.xml
    src/main/java/
      InfinispanAgenticScopeStoreProcessor.java      # Bean registration + feature
      InfinispanAgenticScopeStoreBuildTimeConfig.java # Build-time config (client name)
      DevServicesConfigBuilderCustomizer.java         # Dev container image config
    src/main/resources/META-INF/services/
      io.smallrye.config.SmallRyeConfigBuilderCustomizer
    src/test/java/
      InfinispanAgenticScopePersistenceTest.java      # Programmatic API test
      InfinispanDeclarativeAgenticPersistenceTest.java # Declarative API + real LLM
      InfinispanSupervisorAgenticPersistenceTest.java  # Supervisor pattern + real LLM
  runtime/
    pom.xml
    src/main/java/
      InfinispanAgenticScopeStore.java          # AgenticScopeStore implementation
      InfinispanAgenticScopeStoreConfig.java    # Runtime config (cache name, TTL)
      InfinispanAgenticScopeStoreRecorder.java  # Synthetic bean factory
```

## Configuration

### Build-Time

| Property | Default | Description |
|---|---|---|
| `quarkus.langchain4j.agentic-persistence.infinispan.client-name` | _(default client)_ | Name of the Infinispan client to use, as configured by `quarkus-infinispan-client`. If unset, the default Infinispan client is used. |

### Runtime

| Property | Default | Description |
|---|---|---|
| `quarkus.langchain4j.agentic-persistence.infinispan.cache-name` | `agentic-scope-cache` | Name of the Infinispan cache for storing scope state. Created automatically if it doesn't exist. |
| `quarkus.langchain4j.agentic-persistence.infinispan.ttl-seconds` | _(no expiry)_ | Optional time-to-live for cache entries in seconds. Entries expire after this duration. Useful for preventing unbounded cache growth. |

## Usage

### Dependency

Add the Infinispan agentic persistence store to your project:

```xml
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-agentic-persistence-infinispan</artifactId>
</dependency>
```

This transitively pulls in `quarkus-langchain4j-agentic-persistence` (the core wiring) and `quarkus-infinispan-client`.

### Minimal Configuration

In development mode, Infinispan DevServices starts a container automatically. No configuration is required beyond the dependency.

For production, configure the Infinispan connection:

```properties
quarkus.infinispan-client.hosts=infinispan.example.com:11222
quarkus.infinispan-client.username=admin
quarkus.infinispan-client.password=secret
```

### Defining a Persistent Workflow

Any workflow that uses `@MemoryId` and extends `AgenticScopeAccess` automatically gets persistence:

```java
public interface OrderWorkflow extends AgenticScopeAccess {

    @SequenceAgent(outputKey = "result", subAgents = {
        Validator.class,
        Approver.class,     // @HumanInTheLoop
        Processor.class
    })
    String processOrder(@MemoryId String orderId, @V("order") String order);
}
```

### Recovery After Restart

When the application restarts (e.g., after a pod reschedule), invoke the workflow with the same `memoryId`. The framework detects the persisted scope in Infinispan, restores the planner cursor from checkpointed state, skips already-completed agents, and resumes from where it left off:

```java
// On the new pod, re-invoke with the same orderId.
// If Validator and Approver already completed before the crash,
// only Processor runs.
String result = workflow.processOrder("order-123", orderData);
```

## Test Coverage

The module includes three end-to-end integration tests:

1. **`InfinispanAgenticScopePersistenceTest`** -- Programmatic API: tests `save`, `load`, `delete`, and `getAllKeys` against a real Infinispan container via DevServices. Verifies round-trip serialization and scope state integrity.

2. **`InfinispanDeclarativeAgenticPersistenceTest`** -- Declarative API with real LLM: a Document Review Pipeline (`Summarizer` -> `RiskAssessor` -> `ComplianceApprover` -> `Archiver`) that uses qwen2.5:7b via Ollama. Tests scope persistence through a simulated pod crash, recovery from Infinispan, HumanInTheLoop with `PendingResponse`, and workflow resumption from the correct planner position.

3. **`InfinispanSupervisorAgenticPersistenceTest`** -- Supervisor pattern with real LLM: a Customer Support Pipeline where a `@SupervisorAgent` autonomously routes requests to specialist sub-agents (`BillingSpecialist` or `TechnicalSpecialist`) via LLM-driven decisions. Tests multi-turn LLM interaction, scope persistence across crash/recovery, and HumanInTheLoop manager approval.

## Kubernetes Considerations

- **Distributed cache**: Infinispan cluster mode ensures all pods see the same persisted scopes
- **Session affinity**: Recommended for performance (in-memory cache hit on the same pod), but not required for correctness
- **TTL/cleanup**: Configure `ttl-seconds` to prevent unbounded cache growth from abandoned workflows
- **Health**: Infinispan client health is covered by the `quarkus-infinispan-client` health checks
