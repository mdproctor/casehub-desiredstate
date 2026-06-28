# PendingApprovalHandler SPI — Design Spec

**Issue:** #14 — ReconciliationLoop PendingApproval workflow
**Date:** 2026-06-28
**Status:** Draft

## Problem

The reconciliation loop has no concept of in-flight transitions. When a provisioner returns `ProvisionResult.PendingApproval(nodeId, planReference)`, `SimpleTransitionExecutor` maps it to `StepOutcome.Skipped` — a dead end. No WorkItem is created, no approval is tracked, and no mechanism exists to re-call the provisioner with approval context once a human approves.

On the next reconciliation cycle, `ActualStateAdapter` reports the node as ABSENT (it was never provisioned), `TransitionPlanner` creates a new PROVISION step, and the provisioner is called again — with no knowledge that it already requested approval.

Three pieces are missing:

1. **State tracking** — something must know "we already asked for approval on this node"
2. **Approval flow** — something must create a WorkItem and detect completion
3. **Re-provision context** — `ProvisionContext` must carry `PlanApproval` so the provisioner proceeds

## Approach

New `PendingApprovalHandler` SPI, parallel to `HumanNodeHandler` but with different calling semantics:

- `HumanNodeHandler` **replaces** the provisioner — called instead of `provisioner.provision()`. The human provisions the node externally.
- `PendingApprovalHandler` **wraps** the provisioner — called before (to check approval status) and after (to record pending approval). The machine provisions after human approval.

`NodeStatus` stays pure — PRESENT, ABSENT, DRIFTED, UNKNOWN remain environment observations. Approval is workflow state, not environment state, and is tracked by the handler.

## New API Types

### PlanApproval

```java
package io.casehub.desiredstate.api;

public record PlanApproval(String planReference, String approvedBy, Instant approvedAt) {
    public PlanApproval {
        Objects.requireNonNull(planReference);
        Objects.requireNonNull(approvedBy);
        Objects.requireNonNull(approvedAt);
    }
}
```

### ApprovalCheckResult

```java
package io.casehub.desiredstate.api;

public sealed interface ApprovalCheckResult {
    record None() implements ApprovalCheckResult {}
    record Pending(String planReference) implements ApprovalCheckResult {}
    record Approved(PlanApproval approval) implements ApprovalCheckResult {}
    record Rejected(String planReference, String reason) implements ApprovalCheckResult {}
}
```

### PendingApprovalHandler

```java
package io.casehub.desiredstate.api;

public interface PendingApprovalHandler {
    ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId);
    StepOutcome recordPending(DesiredNode node, StepAction action, String tenancyId, String planReference);
}
```

Takes `DesiredNode` + `StepAction` + `tenancyId` rather than full context types. The handler only needs identity info for lookup/creation.

## Changes to Existing API Types

### ProvisionContext

Gains optional `PlanApproval`:

```java
public record ProvisionContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {
    public ProvisionContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public ProvisionContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public ProvisionContext withApproval(PlanApproval approval) {
        return new ProvisionContext(this.tenancyId, this.graph, approval);
    }
}
```

### DeprovisionContext

Same treatment:

```java
public record DeprovisionContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {
    public DeprovisionContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public DeprovisionContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public DeprovisionContext withApproval(PlanApproval approval) {
        return new DeprovisionContext(this.tenancyId, this.graph, approval);
    }
}
```

### FaultType

Add `APPROVAL_REJECTED`:

```java
public enum FaultType {
    NODE_DESTROYED,
    NODE_DEGRADED,
    PROVISION_FAILED,
    DEPROVISION_FAILED,
    HUMAN_NODE_TIMEOUT,
    DEPENDENCY_UNAVAILABLE,
    APPROVAL_REJECTED
}
```

### StepOutcome

Add optional `FaultType` to `Failed` for explicit fault classification:

```java
public sealed interface StepOutcome {
    record Succeeded() implements StepOutcome {}
    record Failed(String reason, FaultType faultType) implements StepOutcome {
        public Failed(String reason) { this(reason, null); }
    }
    record Skipped(String reason) implements StepOutcome {}
}
```

## Runtime Changes

### NoOpPendingApprovalHandler

`@DefaultBean @ApplicationScoped` in `runtime/`. Always returns `None` — system functions without work-adapter.

```java
@DefaultBean
@ApplicationScoped
public class NoOpPendingApprovalHandler implements PendingApprovalHandler {

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        return new ApprovalCheckResult.None();
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action,
                                      String tenancyId, String planReference) {
        return new StepOutcome.Skipped(
            "pending approval: " + planReference + " — no PendingApprovalHandler configured");
    }
}
```

### SimpleTransitionExecutor

Modified `executeProvision()` flow:

1. If `node.requiresHuman()` → delegate to `humanNodeHandler` (unchanged)
2. `check = pendingApprovalHandler.check(node, PROVISION, tenancyId)`
3. Switch on check result:
   - `None` → create `ProvisionContext(tenancyId, graph)`, call provisioner
   - `Pending` → return `Skipped("pending approval: " + planRef)`
   - `Approved` → create `ProvisionContext(tenancyId, graph, approval)`, call provisioner
   - `Rejected` → return `Failed("approval rejected: " + reason, APPROVAL_REJECTED)`
4. If provisioner returns `PendingApproval` → call `handler.recordPending()`

`executeDeprovision()` follows the same pattern with `StepAction.DEPROVISION` and `DeprovisionContext`.

### ReconciliationLoop.faultFeedback()

Uses `StepOutcome.Failed.faultType()` when non-null. Falls back to the existing classification (PROVISION_FAILED / DEPROVISION_FAILED based on removal-set membership) when null.

## Work-Adapter Changes

### WorkItemPendingApprovalHandler

`@ApplicationScoped` in `work-adapter/`, displaces `NoOpPendingApprovalHandler` by classpath presence.

**CallerRef convention:** `desiredstate-approval:<tenancyId>:<nodeId>:<action>` — distinct from HumanNodeHandler's `desiredstate:` prefix.

**check() logic:**
1. `findActiveByCallerRef(callerRef)` → if active WorkItem exists → return `Pending`
2. `findByCallerRef(callerRef)` → if terminal WorkItem exists:
   - COMPLETED with "approved" outcome → return `Approved(PlanApproval)`
   - REJECTED → return `Rejected`
   - EXPIRED / CANCELLED → return `None` (fresh start — provisioner may request approval again)
3. No WorkItem found → return `None`

**recordPending() logic:**
1. Idempotent check: `findActiveByCallerRef()` — if active, return Skipped with existing ID
2. Create WorkItem via `WorkItemCreator.create()`:
   - Title: `"Approve: <action> <nodeId>"`
   - Category: `desiredstate-approval`
   - CallerRef: `desiredstate-approval:<tenancyId>:<nodeId>:<action>`
   - Priority: HIGH
   - PermittedOutcomes: Approve, Reject
   - Payload: planReference (for round-trip)
3. Return `Skipped("pending approval: WorkItem " + created.id())`

**PlanApproval population:**
- `planReference` — extracted from WorkItem payload (round-tripped from provisioner)
- `approvedBy` — `WorkItemRef.assigneeId()` (the person who completed the WorkItem)
- `approvedAt` — `Instant.now()` (observation time — WorkItemRef carries no completion timestamp)

## Testing Module

### MockPendingApprovalHandler

Programmable mock in `testing/`:

- `programCheck(NodeId, StepAction, ApprovalCheckResult)` — program check results
- `check()` — returns programmed result or `None`
- `recordPending()` — records the call, returns Skipped
- `recorded()` — returns list of recorded pending approvals

## CaseTransitionExecutor

No changes. CaseTransitionExecutor delegates to casehub-engine which has its own HITL infrastructure (HumanTaskTarget bindings, WorkItemLifecycleAdapter). PendingApproval within case workflows is an engine concern. The SPI is in api/ and available to any executor, but CaseTransitionExecutor has no reason to use it.

## Reconciliation Cycle Walkthrough

**Cycle 1 — Provisioner discovers approval needed:**
ActualStateAdapter reports ABSENT → planner creates PROVISION step → handler.check() returns None → provisioner returns PendingApproval → handler.recordPending() creates WorkItem → returns Skipped.

**Cycles 2..N — Waiting:**
ActualStateAdapter reports ABSENT → planner creates PROVISION step → handler.check() returns Pending → returns Skipped immediately. Provisioner not called.

**Cycle N+1 — Approved:**
handler.check() returns Approved → context enriched with PlanApproval → provisioner called with approval context → provisioner proceeds → Success. Next cycle: PRESENT, converged.

**Alternative — Rejected:**
handler.check() returns Rejected → Failed with APPROVAL_REJECTED → faultFeedback creates FaultEvent → FaultPolicyEngine evaluates → domain policy decides response.

**Alternative — Expired/Cancelled:**
handler.check() returns None (terminal non-decision) → provisioner called fresh → may return PendingApproval again → new WorkItem cycle.

## Design Decisions

1. **Separate SPI from HumanNodeHandler** — different calling semantics (wrapping vs replacement). Shared infrastructure (WorkItemCreator, callerRef) stays at the implementation level in work-adapter/.

2. **NodeStatus unchanged** — PENDING_APPROVAL is workflow state, not environment state. Adding it would be a category error that muddies the ActualState abstraction.

3. **FaultType.APPROVAL_REJECTED** — rejection is semantically different from technical failure. Domain fault policies should distinguish "provisioner couldn't" from "human said no."

4. **StepOutcome.Failed gains optional FaultType** — explicit fault classification rather than convention-based reason-string parsing.

5. **Handler takes (DesiredNode, StepAction, tenancyId)** — minimal parameter set. Handler doesn't need the full graph or context, just identity info for lookup/creation.

6. **Observation time for approvedAt** — WorkItemRef has no completedAt field. Using Instant.now() is pragmatically correct: the reconciliation loop observed the approval at this instant. Exact human-click time is audit data (in casehub-work's own records), not reconciliation data.

## Out-of-Scope

- **PLATFORM.md update** — tracked as #45
- **Pipeline example enhancement** — tracked as #46
- **WorkItemRef timestamp enrichment** — casehub-work concern, not desiredstate
- **CaseTransitionExecutor real outcome tracking** — separate follow-up (noted in CaseTransitionExecutor code)
