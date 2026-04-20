# Quarkus LangChain4j - Agentic Persistence

This extension provides the core Quarkus integration for persisting agentic scope state across application restarts. It bridges the LangChain4j Agentic persistence SPI (`AgenticScopeStore`, `AgenticScopePersister`) with the Quarkus CDI container, enabling long-running agentic workflows to survive pod restarts in Kubernetes environments.

## Design Principles

### Store-Agnostic Core

This module contains **no storage implementation**. It defines the wiring contract between Quarkus and the LangChain4j Agentic persistence SPI, leaving the choice of backing store (Infinispan, Redis, JDBC, etc.) to separate provider modules. This separation follows the same pattern used by `quarkus-langchain4j` embedding stores: a core extension handles CDI integration, while store-specific modules provide the `AgenticScopeStore` implementation.

### Build-Time Conditional Wiring

The extension uses a **marker build item** (`AgenticScopeStoreBuildItem`) to detect whether a store provider is present at build time. If no provider is on the classpath, the persistence infrastructure is not initialized and agentic scopes operate as ephemeral in-memory objects. This means applications that don't need persistence pay zero overhead.

### Single Activation Point

The entire runtime wiring consists of a single call: `AgenticScopePersister.setStore(store)`. Once set, the upstream LangChain4j Agentic framework handles the full persistence lifecycle automatically:

- **Save**: Scope state is flushed to the store at the end of each root call via `DefaultAgenticScope.rootCallEnded()` and after each agent step (per-step checkpointing)
- **Load**: When a scope is requested by `memoryId`, the `AgenticScopeRegistry` checks its in-memory map first, then falls back to `store.load(key)`
- **Delete**: `AgenticScopeRegistry.evict(memoryId)` removes the scope from both in-memory cache and the persistent store

## Architecture

```
+------------------------------+       +--------------------------------+
|  agentic-persistence         |       |  agentic-persistence-stores/   |
|  (this module)               |       |  infinispan/  (or redis, etc.) |
|                              |       |                                |
|  Deployment:                 |       |  Deployment:                   |
|    AgenticPersistenceProcessor|  <--  |    Produces                    |
|      - detects store via     |       |    AgenticScopeStoreBuildItem  |
|        AgenticScopeStoreBuild|       |                                |
|        Item (optional)       |       |  Runtime:                      |
|      - calls recorder if    |       |    InfinispanAgenticScopeStore |
|        present               |       |    implements AgenticScopeStore|
|                              |       +--------------------------------+
|  Runtime:                    |
|    AgenticPersistenceRecorder|
|      - CDI.select(           |
|          AgenticScopeStore)  |
|      - AgenticScopePersister |
|        .setStore(store)      |
+------------------------------+
```

## Module Structure

```
agentic-persistence/
  pom.xml                         # Parent POM (packaging=pom)
  deployment/
    pom.xml
    src/main/java/
      AgenticPersistenceProcessor.java   # Build step: conditional store wiring
      AgenticScopeStoreBuildItem.java     # Marker build item
  runtime/
    pom.xml
    src/main/java/
      AgenticPersistenceRecorder.java    # Runtime: CDI lookup + setStore()
```

## How It Works

### Build Time

`AgenticPersistenceProcessor` defines two build steps:

1. **Feature registration** - Registers the `langchain4j-agentic-persistence` feature so it appears in Quarkus startup logs.
2. **Store wiring** (`@Record(RUNTIME_INIT)`) - Accepts an `Optional<AgenticScopeStoreBuildItem>`. If a store provider produced this build item, the recorder is invoked. If not, nothing happens.

### Runtime

`AgenticPersistenceRecorder.initializeAgenticScopePersister()` performs a CDI lookup for the `AgenticScopeStore` bean (registered by the store provider) and passes it to `AgenticScopePersister.setStore()`. From this point on, all `PERSISTENT`-kind agentic scopes are automatically saved and loaded through this store.

## What Gets Persisted

The upstream `AgenticScopeSerializer` handles serialization of the full `DefaultAgenticScope` state:

| Field | Persisted | Notes |
|---|---|---|
| `memoryId` | Yes | Scope identifier (e.g., a ticket ID or session ID) |
| `state` (Map) | Yes | Agent outputs, shared workflow state, planner cursors |
| `agentInvocations` (List) | Yes | Execution history |
| `context` (List) | Yes | Conversation messages |
| `kind` (enum) | Yes | `EPHEMERAL` / `REGISTERED` / `PERSISTENT` |
| `agents` (transient) | No | Runtime agent instances, reconstructed from CDI |
| `lock` (transient) | No | Reconstructed based on `Kind` |

## Recovery Scenarios

| Scenario | Supported | Mechanism |
|---|---|---|
| Pod restart between separate invocations | Yes | Scope loaded from store on next `registry.get(memoryId)` |
| State accumulated across agent steps | Yes | Per-step checkpointing saves after each agent completes |
| HumanInTheLoop with PendingResponse | Yes | PendingResponse serialized; on recovery, workflow resumes from checkpointed planner position |
| Multi-turn stateful conversations | Yes | Conversation context saved in scope state |

## Usage

This module is not used directly. Add a store provider dependency (e.g., `quarkus-langchain4j-agentic-persistence-infinispan`) to your project. The core wiring activates automatically when the provider is present.

## Implementing a New Store Provider

To add a new backing store (e.g., Redis, JDBC), create a module that:

1. **Runtime**: Implements `dev.langchain4j.agentic.scope.AgenticScopeStore` with the four operations (`save`, `load`, `delete`, `getAllKeys`). Serialization is handled by `AgenticScopeSerializer.toJson()` / `fromJson()` -- the store only needs to persist JSON strings keyed by `AgenticScopeKey(agentId, memoryId)`.
2. **Deployment**: Registers the implementation as a CDI synthetic bean typed as `AgenticScopeStore`, and produces an `AgenticScopeStoreBuildItem` to activate the wiring.

See the Infinispan implementation in `agentic-persistence-stores/infinispan/` for a complete reference.
