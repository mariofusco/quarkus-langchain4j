package io.quarkiverse.langchain4j.agentic.persistence.infinispan;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.declarative.HumanInTheLoop;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.internal.PendingResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.AgenticScopeStore;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkus.test.QuarkusUnitTest;

/**
 * End-to-end integration test demonstrating agentic scope persistence and recovery
 * with Infinispan as the backing store, using the <b>declarative annotations-based API</b>
 * and <b>real AI-backed agents</b> (qwen2.5:7b via local Ollama).
 *
 * <p>
 * Scenario: a <b>Document Review Pipeline</b> for a compliance department.
 * <ol>
 * <li><b>Summarizer</b> — an AI agent that condenses a submitted document into a concise summary</li>
 * <li><b>RiskAssessor</b> — an AI agent that evaluates the summary and assigns a risk level
 * (HIGH / MEDIUM / LOW) with a brief justification</li>
 * <li><b>ComplianceApprover</b> — a {@link HumanInTheLoop} step that auto-approves LOW-risk
 * documents and creates a {@link PendingResponse} for MEDIUM / HIGH-risk ones,
 * pausing the workflow until a compliance officer reviews</li>
 * <li><b>Archiver</b> — a non-AI agent that assembles the final archival record</li>
 * </ol>
 *
 * <p>
 * The test demonstrates:
 * <ul>
 * <li>Declarative workflow composition with {@code @SequenceAgent}, {@code @Agent},
 * {@code @HumanInTheLoop}, and {@code @UserMessage}</li>
 * <li>Real LLM interaction (two separate AI agents talking to qwen2.5:7b on Ollama)</li>
 * <li>Scope state persisted to Infinispan after each agent step (per-step checkpointing)</li>
 * <li>Workflow paused at HumanInTheLoop with PendingResponse</li>
 * <li>Simulated pod crash (clear all in-memory state)</li>
 * <li>Scope recovered from Infinispan on a "new pod"</li>
 * <li>Human response provided after recovery</li>
 * <li>Workflow resumed from the correct position (only Archiver runs)</li>
 * <li>Scope eviction and cleanup</li>
 * </ul>
 */
public class InfinispanDeclarativeAgenticPersistenceTest {

    @RegisterExtension
    static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(
                            DocumentReviewWorkflow.class,
                            Summarizer.class,
                            RiskAssessor.class,
                            ComplianceApprover.class,
                            Archiver.class))
            .overrideConfigKey("quarkus.langchain4j.ollama.base-url", "http://localhost:11434")
            .overrideConfigKey("quarkus.langchain4j.ollama.chat-model.model-id", "qwen2.5:7b")
            .overrideRuntimeConfigKey("quarkus.langchain4j.ollama.timeout", "120s")
            .overrideRuntimeConfigKey("quarkus.langchain4j.ollama.log-requests", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.ollama.log-responses", "true")
            .overrideConfigKey("quarkus.langchain4j.devservices.enabled", "false");

    // ================================================================
    //  WORKFLOW DEFINITION — declarative annotations
    // ================================================================

    /**
     * Top-level workflow interface. Extends {@link AgenticScopeAccess} to allow
     * retrieving and evicting persisted scopes by memoryId.
     */
    public interface DocumentReviewWorkflow extends AgenticScopeAccess {

        @SequenceAgent(outputKey = "archive_record", subAgents = {
                Summarizer.class,
                RiskAssessor.class,
                ComplianceApprover.class,
                Archiver.class
        })
        String reviewDocument(@MemoryId String docId, String document);
    }

    /**
     * AI agent: condenses the document into a 2-3 sentence summary.
     * Uses qwen2.5:7b via the Quarkus Ollama extension (CDI-provided ChatModel).
     */
    public interface Summarizer {

        @UserMessage("""
                You are a document summarizer working for a corporate compliance department.
                Summarize the following document in exactly 2-3 sentences.
                Focus on the key facts, financial figures, and any decisions or commitments made.
                Return only the summary and nothing else.

                Document: {{document}}
                """)
        @Agent(description = "Summarize a document for compliance review", outputKey = "summary")
        String summarize(String document);
    }

    /**
     * AI agent: reads the summary and assesses the risk level.
     * Returns one of HIGH, MEDIUM, or LOW followed by a brief justification.
     */
    public interface RiskAssessor {

        @UserMessage("""
                You are a risk assessment expert in a corporate compliance department.
                Based on the following document summary, assess the risk level.

                Respond in exactly this format (no extra text):
                LEVEL: <HIGH or MEDIUM or LOW>
                REASON: <one sentence justification>

                Summary: {{summary}}
                """)
        @Agent(description = "Assess the risk level of a document", outputKey = "riskAssessment")
        String assessRisk(String summary);
    }

    /**
     * HumanInTheLoop step: auto-approves LOW-risk documents; for anything else,
     * creates a {@link PendingResponse} that pauses the workflow until a
     * compliance officer provides their decision.
     */
    public interface ComplianceApprover {

        @HumanInTheLoop(description = "Wait for compliance officer review on elevated-risk documents",
                outputKey = "complianceDecision")
        static Object requestComplianceReview(AgenticScope scope) {
            String riskAssessment = scope.readState("riskAssessment", "UNKNOWN");
            // Parse the risk level from the LLM response
            String upperAssessment = riskAssessment.toUpperCase();
            if (upperAssessment.contains("LOW") && !upperAssessment.contains("MEDIUM")
                    && !upperAssessment.contains("HIGH")) {
                return "AUTO-APPROVED (low risk)";
            }
            // Elevated risk — pause and wait for human review
            return new PendingResponse<>("compliance-review");
        }
    }

    /**
     * Non-AI agent: assembles the final archival record from all accumulated
     * scope state (summary, risk assessment, and compliance decision).
     */
    public static class Archiver {

        @Agent(value = "Produce the final archival record", outputKey = "archive_record")
        public static String archive(
                String summary,
                String riskAssessment,
                Object complianceDecision) {
            return "ARCHIVED | Summary: " + summary
                    + " | Risk: " + riskAssessment
                    + " | Compliance: " + complianceDecision;
        }
    }

    // ================================================================
    //  TEST
    // ================================================================

    /**
     * A document describing a large financial transaction — designed to
     * trigger MEDIUM or HIGH risk from the LLM so the HumanInTheLoop fires.
     */
    private static final String RISKY_DOCUMENT = """
            MEMORANDUM OF UNDERSTANDING

            Between: Acme Corporation and GlobalTech Industries
            Date: April 15, 2026
            Subject: Acquisition of controlling stake

            Acme Corporation agrees to acquire a 51% controlling stake in GlobalTech Industries
            for a total consideration of $4.2 billion USD. The transaction involves the transfer
            of intellectual property rights covering 47 patents in the autonomous vehicle domain,
            cross-border regulatory filings in 12 jurisdictions, and the assumption of $800 million
            in existing debt obligations. Closing is contingent on antitrust approval from the
            European Commission and the U.S. Federal Trade Commission. A break-up fee of
            $350 million applies if either party withdraws after signing.
            """;

    @Inject
    DocumentReviewWorkflow workflow;

    @Inject
    AgenticScopeStore store;

    @Test
    void declarative_workflow_persistence_and_recovery_with_real_llm() throws Exception {

        // ================================================================
        //  PHASE 1: Start the workflow — AI agents run, then block at approval
        // ================================================================

        // Launch on a background thread so we can interact while it's blocked
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        CompletableFuture<String> phase1Future = CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setContextClassLoader(tccl);
            return workflow.reviewDocument("doc-review-001", RISKY_DOCUMENT);
        });

        // Wait until the PendingResponse appears in the scope state
        awaitPendingResponse(workflow, "doc-review-001", phase1Future);

        // ================================================================
        //  VERIFY: Scope state after AI agents ran and before human review
        // ================================================================
        AgenticScope scopeBeforeCrash = workflow.getAgenticScope("doc-review-001");
        String summary = scopeBeforeCrash.readState("summary", "");
        String riskAssessment = scopeBeforeCrash.readState("riskAssessment", "");

        verifyScopeBeforeCrash(summary, riskAssessment, scopeBeforeCrash);

        // ================================================================
        //  PHASE 2: Simulate a pod crash — wipe all in-memory state
        // ================================================================

        simulateCrash();

        // ================================================================
        //  PHASE 3: Recovery — load scope from Infinispan and resume
        // ================================================================

        // On a "new pod", the scope is loaded from Infinispan when accessed
        AgenticScope recoveredScope = workflow.getAgenticScope("doc-review-001");

        verifyRecoveredScope(recoveredScope, summary, riskAssessment);

        // Simulate the compliance officer providing their decision
        recoveredScope.writeState("complianceDecision",
                "APPROVED by compliance officer J. Smith — risk mitigated by antitrust counsel review");

        // Re-invoke the workflow with the same docId.
        // The SequentialPlanner restores its cursor from the checkpointed state
        // and skips the already-completed steps (Summarizer + RiskAssessor + ComplianceApprover).
        // Only Archiver runs.
        String finalResult = workflow.reviewDocument("doc-review-001", RISKY_DOCUMENT);
        verifyFinalResult(finalResult, summary, riskAssessment);

        // ================================================================
        //  PHASE 4: Cleanup — evict the scope from both memory and Infinispan
        // ================================================================
        workflow.evictAgenticScope("doc-review-001");
        assertThat(store.getAllKeys())
                .as("Infinispan should be empty after eviction")
                .isEmpty();

        // Unblock the Phase 1 thread that is still waiting on the old PendingResponse.
        // Use reflection because PendingResponse is loaded by the deployment classloader
        // while the test class uses a different classloader.
        for (Object value : scopeBeforeCrash.state().values()) {
            if (value.getClass().getName().endsWith("PendingResponse")) {
                Method completeMethod = value.getClass().getMethod("complete", Object.class);
                completeMethod.invoke(value, "cleanup");
                break;
            }
        }
        try {
            phase1Future.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Phase 1 result is irrelevant — it was the "crashed" execution
        }
    }

    private static void verifyFinalResult(String finalResult, String summary, String riskAssessment) {
        assertThat(finalResult)
                .as("Final result should contain the summary")
                .contains(summary);
        assertThat(finalResult)
                .as("Final result should contain the risk assessment")
                .contains(riskAssessment);
        assertThat(finalResult)
                .as("Final result should contain the compliance decision")
                .contains("APPROVED by compliance officer J. Smith");
        assertThat(finalResult)
                .as("Final result should start with ARCHIVED")
                .startsWith("ARCHIVED");
    }

    private static void verifyRecoveredScope(AgenticScope recoveredScope, String summary, String riskAssessment) {
        // All AI-generated state from before the crash survived
        assertThat(recoveredScope.readState("summary", ""))
                .as("Summary should survive pod crash via Infinispan")
                .isEqualTo(summary);
        assertThat(recoveredScope.readState("riskAssessment", ""))
                .as("Risk assessment should survive pod crash via Infinispan")
                .isEqualTo(riskAssessment);

        // The PendingResponse was deserialized as a new incomplete future
        assertThat(recoveredScope.pendingResponseIds())
                .as("PendingResponse should be recovered from Infinispan")
                .containsExactly("compliance-review");
    }

    private void simulateCrash() throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        // Unwrap the Arc CDI client proxy to get the underlying JDK dynamic proxy
        // that implements AgenticScopeOwner. Direct casting fails because the
        // test classloader and deployment classloader load different copies of
        // the AgenticScopeOwner interface.

        Object unwrapped = workflow;
        for (Class<?> iface : workflow.getClass().getInterfaces()) {
            if (iface.getName().equals("io.quarkus.arc.ClientProxy")) {
                Method arcContextualInstance = iface.getMethod("arc_contextualInstance");
                unwrapped = arcContextualInstance.invoke(workflow);
                break;
            }
        }
        Method registryMethod = unwrapped.getClass().getMethod("registry");
        Object registry = registryMethod.invoke(unwrapped);
        Method clearMethod = registry.getClass().getMethod("clearInMemory");
        clearMethod.invoke(registry);

        Method getAllKeysMethod = registry.getClass().getMethod("getAllAgenticScopeKeysInMemory");
        Set<?> inMemoryKeys = (Set<?>) getAllKeysMethod.invoke(registry);
        assertThat(inMemoryKeys)
                .as("In-memory cache should be empty after simulated crash")
                .isEmpty();
    }

    private void verifyScopeBeforeCrash(String summary, String riskAssessment, AgenticScope scopeBeforeCrash) {
        // Summarizer output — the LLM produced a real summary
        assertThat(summary)
                .as("Summarizer should have produced a non-empty summary from the LLM")
                .isNotEmpty();
        // The summary should mention key facts from the document
        assertThat(summary.toLowerCase())
                .as("Summary should reference the acquisition")
                .containsAnyOf("acme", "globaltech", "acquisition", "billion", "stake");

        // RiskAssessor output — the LLM assessed the risk
        assertThat(riskAssessment)
                .as("RiskAssessor should have produced a non-empty risk assessment from the LLM")
                .isNotEmpty();
        // Given the $4.2B acquisition with antitrust concerns, the LLM should flag elevated risk
        assertThat(riskAssessment.toUpperCase())
                .as("Risk assessment should contain a level indicator")
                .containsAnyOf("HIGH", "MEDIUM");

        // ComplianceApprover created a PendingResponse (elevated risk)
        assertThat(scopeBeforeCrash.pendingResponseIds())
                .as("ComplianceApprover should have created a PendingResponse")
                .containsExactly("compliance-review");

        // Planner execution state was checkpointed
        assertThat(scopeBeforeCrash.state().entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith("__planner_state_")))
                .as("Planner cursor should be checkpointed in scope state")
                .isTrue();

        // Verify the scope is persisted in Infinispan
        assertThat(store.getAllKeys())
                .as("Scope should be persisted in Infinispan")
                .isNotEmpty();
    }

    /**
     * Polls until the HumanInTheLoop agent has executed and the PendingResponse
     * appears in the scope state. Fails fast if the workflow future completes
     * (which means it either succeeded unexpectedly or threw an error).
     */
    private void awaitPendingResponse(DocumentReviewWorkflow workflow, String docId,
            CompletableFuture<?> workflowFuture) throws InterruptedException {
        // 120s timeout — the LLM calls may take a while
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (workflowFuture.isDone()) {
                try {
                    workflowFuture.get();
                    throw new AssertionError(
                            "Workflow completed without blocking at HumanInTheLoop — "
                                    + "the LLM may have returned a LOW risk assessment");
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AssertionError("Workflow failed before PendingResponse appeared",
                            e.getCause());
                }
            }
            try {
                AgenticScope scope = workflow.getAgenticScope(docId);
                if (scope != null && !scope.pendingResponseIds().isEmpty()) {
                    return;
                }
            } catch (Exception ignored) {
                // Scope may not exist yet
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Timed out waiting for PendingResponse to appear in scope state "
                + "(120s) — the LLM may be too slow or unreachable");
    }
}
