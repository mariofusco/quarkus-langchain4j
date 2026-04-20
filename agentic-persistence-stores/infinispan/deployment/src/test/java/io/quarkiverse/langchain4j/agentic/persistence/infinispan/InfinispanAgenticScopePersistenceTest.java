package io.quarkiverse.langchain4j.agentic.persistence.infinispan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.AgenticScopeOwner;
import dev.langchain4j.agentic.internal.PendingResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.AgenticScopeRegistry;
import dev.langchain4j.agentic.scope.AgenticScopeStore;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import io.quarkus.test.QuarkusUnitTest;

/**
 * End-to-end integration test demonstrating agentic scope persistence and recovery
 * with Infinispan as the backing store.
 *
 * <p>
 * Scenario: an order processing workflow with three sequential steps:
 * <ol>
 * <li><b>ValidateOrder</b> — validates the order data and writes results to scope state</li>
 * <li><b>HumanApproval</b> — a {@link HumanInTheLoop} step that creates a {@link PendingResponse}
 * to pause the workflow waiting for a manager's approval</li>
 * <li><b>FulfillOrder</b> — reads the approval decision and produces the final result</li>
 * </ol>
 *
 * <p>
 * The test demonstrates:
 * <ul>
 * <li>Scope state persisted to Infinispan after each agent step (per-step checkpointing)</li>
 * <li>Workflow paused at HumanInTheLoop with PendingResponse</li>
 * <li>Simulated pod crash (clear all in-memory state)</li>
 * <li>Scope recovered from Infinispan on a "new pod"</li>
 * <li>Human response provided after recovery</li>
 * <li>Workflow resumed from the correct position (only FulfillOrder runs)</li>
 * <li>Scope eviction and cleanup</li>
 * </ul>
 */
public class InfinispanAgenticScopePersistenceTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class));

    /**
     * The workflow interface. Extends {@link AgenticScopeAccess} to allow retrieving
     * and evicting persisted scopes by memoryId. Defined outside the archive and built
     * programmatically — no declarative agent annotations needed, no ChatModel required.
     */
    public interface OrderWorkflow extends AgenticScopeAccess {
        String processOrder(@MemoryId String orderId, @V("order") String orderDetails);
    }

    @Inject
    AgenticScopeStore store;

    @Test
    void full_persistence_and_recovery_lifecycle() throws Exception {

        // ================================================================
        //  BUILD: Create a 3-step sequential workflow programmatically
        // ================================================================

        // Step 1: Validate the order — a pure scope-manipulation agent (no LLM needed)
        AgenticServices.AgenticScopeAction validateOrder = AgenticServices.agentAction(scope -> {
            String order = scope.readState("order", "");
            scope.writeState("validated_order", "VALIDATED: " + order);
            scope.writeState("order_total", 1500);
        });

        // Step 2: Human approval gate — pauses the workflow using PendingResponse
        HumanInTheLoop approvalGate = AgenticServices.humanInTheLoopBuilder()
                .description("Wait for manager approval on large orders")
                .outputKey("approval")
                .responseProvider(scope -> {
                    int total = scope.readState("order_total", 0);
                    if (total > 1000) {
                        return new PendingResponse<>("manager-approval");
                    }
                    return "auto-approved";
                })
                .build();

        // Step 3: Fulfill the order based on the approval decision
        AgenticServices.AgenticScopeAction fulfillOrder = AgenticServices.agentAction(scope -> {
            String validated = scope.readState("validated_order", "");
            String approval = scope.readState("approval", "");
            scope.writeState("result",
                    "Order " + validated + " | Decision: " + approval + " | Status: SHIPPED");
        });

        // Wire the 3 steps into a sequential workflow
        OrderWorkflow workflow = AgenticServices.sequenceBuilder(OrderWorkflow.class)
                .subAgents(validateOrder, approvalGate, fulfillOrder)
                .outputKey("result")
                .build();

        // ================================================================
        //  PHASE 1: Start the workflow — it will block at the approval step
        // ================================================================

        // Launch the workflow on a background thread; it will block on PendingResponse.blockingGet()
        // Capture the Quarkus TCCL and propagate it to the background thread so that
        // ServiceLoader (used by langchain4j internally) resolves classes correctly.
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        CompletableFuture<String> phase1Future = CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setContextClassLoader(tccl);
            return workflow.processOrder("order-42", "1500 widgets for ACME Corp");
        });

        // Wait until the HumanInTheLoop agent has executed and the PendingResponse
        // is visible in the persisted scope state
        awaitPendingResponse(workflow, "order-42", phase1Future);

        // Verify scope state at this point:
        // - ValidateOrder has run (step 1 complete)
        // - HumanApproval has created a PendingResponse (step 2 in progress)
        // - FulfillOrder has NOT run yet (blocked on PendingResponse)
        AgenticScope scopeBeforeCrash = workflow.getAgenticScope("order-42");

        assertThat(scopeBeforeCrash.readState("validated_order", ""))
                .isEqualTo("VALIDATED: 1500 widgets for ACME Corp");
        assertThat(scopeBeforeCrash.readState("order_total", 0))
                .isEqualTo(1500);
        assertThat(scopeBeforeCrash.pendingResponseIds())
                .containsExactly("manager-approval");
        // Planner execution state was checkpointed
        assertThat(scopeBeforeCrash.state().entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith("__planner_state_"))).isTrue();

        // Verify the scope is persisted in Infinispan
        assertThat(store.getAllKeys()).isNotEmpty();

        // ================================================================
        //  PHASE 2: Simulate a pod crash — wipe all in-memory state
        // ================================================================

        // Get the registry and clear its in-memory cache to simulate a full pod restart.
        // The only surviving data is in Infinispan.
        AgenticScopeRegistry registry = ((AgenticScopeOwner) workflow).registry();
        registry.clearInMemory();

        assertThat(registry.getAllAgenticScopeKeysInMemory()).isEmpty();

        // ================================================================
        //  PHASE 3: Recovery — load scope from Infinispan and resume
        // ================================================================

        // On a "new pod", the scope is loaded from Infinispan when accessed
        AgenticScope recoveredScope = workflow.getAgenticScope("order-42");

        // All state from before the crash survived
        assertThat(recoveredScope.readState("validated_order", ""))
                .isEqualTo("VALIDATED: 1500 widgets for ACME Corp");
        assertThat(recoveredScope.readState("order_total", 0))
                .isEqualTo(1500);

        // The PendingResponse was deserialized as a new incomplete future
        assertThat(recoveredScope.pendingResponseIds())
                .containsExactly("manager-approval");

        // Simulate the manager providing their approval (e.g. via a REST endpoint)
        recoveredScope.writeState("approval", "APPROVED by manager Alice");

        // Re-invoke the workflow with the same orderId.
        // The SequentialPlanner restores its cursor from the checkpointed state
        // and skips the already-completed steps (ValidateOrder + HumanApproval).
        // Only FulfillOrder runs.
        String finalResult = workflow.processOrder("order-42", "1500 widgets for ACME Corp");

        assertThat(finalResult).isEqualTo(
                "Order VALIDATED: 1500 widgets for ACME Corp | Decision: APPROVED by manager Alice | Status: SHIPPED");

        // ================================================================
        //  PHASE 4: Cleanup — evict the scope from both memory and Infinispan
        // ================================================================

        workflow.evictAgenticScope("order-42");
        assertThat(store.getAllKeys()).isEmpty();

        // Unblock the Phase 1 thread that is still waiting on the old PendingResponse
        @SuppressWarnings("unchecked")
        PendingResponse<String> oldPending = (PendingResponse<String>) scopeBeforeCrash.state().values().stream()
                .filter(PendingResponse.class::isInstance)
                .findFirst()
                .orElse(null);
        if (oldPending != null) {
            oldPending.complete("cleanup");
        }
        try {
            phase1Future.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Phase 1 result is irrelevant — it was the "crashed" execution
        }
    }

    /**
     * Polls until the HumanInTheLoop agent has executed and the PendingResponse
     * appears in the scope state.
     */
    private void awaitPendingResponse(OrderWorkflow workflow, String orderId,
            CompletableFuture<?> workflowFuture) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (workflowFuture.isDone()) {
                try {
                    workflowFuture.get();
                } catch (Exception e) {
                    throw new AssertionError("Workflow failed before PendingResponse appeared", e);
                }
            }
            try {
                AgenticScope scope = workflow.getAgenticScope(orderId);
                if (scope != null && !scope.pendingResponseIds().isEmpty()) {
                    return;
                }
            } catch (Exception ignored) {
                // Scope may not exist yet
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for PendingResponse to appear in scope state");
    }
}
