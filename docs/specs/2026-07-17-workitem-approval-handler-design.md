# WorkItemPendingApprovalHandler Design

**Date:** 2026-07-17
**Issues:** #81, #82
**Status:** Approved

## Summary

Implement `WorkItemPendingApprovalHandler` in the `work-adapter` module — a WorkItem-backed
`PendingApprovalHandler` that delegates approval lifecycle to casehub-work's `WorkItemCreator` SPI.
Close #82 (casehub-ops DesiredNode constructor migration) as already completed.

## Context

The `PendingApprovalHandler` SPI mediates between the transition executor and an external
approval system. `NoOpPendingApprovalHandler` (`@DefaultBean` in runtime) returns Failed on
`recordPending` — a misconfiguration signal. casehub-ops has its own `OpsPendingApprovalHandler`
(PlanStore-backed, in-memory). No generic WorkItem-backed implementation exists yet.

work#281 (WorkItemRef payload field) and work#282 (WorkItemCreator.obsoleteByCallerRef)
shipped — the blocking dependencies are resolved.

## Issue #82 — Already Done

All casehub-ops GoalCompilers already use the 4-arg `DesiredNode` constructor with explicit
`HumanGating`. Verified across all five compilers:

| GoalCompiler | HumanGating | File |
|---|---|---|
| DeploymentGoalCompiler | `NONE` | `ops/deployment/src/.../DeploymentGoalCompiler.java` |
| ApplicationGoalCompiler | `NONE` | `ops/app/src/.../goal/ApplicationGoalCompiler.java` |
| InfraGoalCompiler | `NONE` | `ops/infra/src/.../InfraGoalCompiler.java` |
| IoTGoalCompiler | `ALL` (physical devices), `NONE` (config) | `ops/iot/src/.../IoTGoalCompiler.java` |
| ComplianceGoalCompiler | Dynamic (`ALL` if `requiresHumanReview()`) | `ops/compliance/src/.../ComplianceGoalCompiler.java` |

The ops build passes clean (0 errors). Close #82 with no code changes.

## Design

### Class: WorkItemPendingApprovalHandler

**Module:** `work-adapter` (`casehub-desiredstate-work`)
**Package:** `io.casehub.desiredstate.work`
**Annotations:** `@ApplicationScoped` (classpath-activated, displaces `NoOpPendingApprovalHandler`)
**Dependencies:** `WorkItemCreator` (injected)
**State:** None — stateless delegator. Thread-safe by construction.

### CallerRef Convention

```
desiredstate-approval:<tenancyId>:<nodeId>:<action>
```

Hyphen-namespaced (`desiredstate-approval`) to distinguish from the human handler's
`desiredstate:<tenancyId>:...` prefix. TenancyId first enables prefix-based tenancy queries.
Includes tenancyId because `findByCallerRef` does not scope by tenancy.

### Status Mapping

`check()` calls `WorkItemCreator.findByCallerRef(callerRef)`:

| WorkItemStatus | ApprovalCheckResult | Action |
|---|---|---|
| Not found | `None` | — |
| PENDING, ASSIGNED, IN_PROGRESS, DELEGATED, SUSPENDED | `Pending(planRef)` | — |
| COMPLETED | `Approved(PlanApproval)` | — (no self-cleaning; idempotent across cycles) |
| REJECTED, CANCELLED, EXPIRED, FAULTED, ESCALATED | `Rejected(planRef, reason)` | — |
| OBSOLETE | `None` | — |

`check()` does not call `obsoleteByCallerRef` on COMPLETED. The COMPLETED WorkItem persists
so that `check()` returns `Approved` idempotently across reconciliation cycles. If the
provisioner fails after receiving approval, the next cycle retries with the same approval
rather than forcing re-approval for a transient failure. Cleanup happens naturally:
`recordPending` calls `obsoleteByCallerRef` if the provisioner ever requests a new approval,
and successful provisioning transitions the node out of the plan (no further `check()` calls).

**Re-provisioning with stale approval:** Because the COMPLETED WorkItem persists after
successful provisioning, a node that re-enters the TransitionPlan (goal change, drift,
different provisioning context) will receive the old `PlanApproval` with its original
`planReference`. The `check()` signature — `check(node, action, tenancyId)` — carries no
planReference, so the handler cannot detect staleness. Provisioners must validate
`planReference` currency when `context.hasApproval()` returns true: compare
`context.approval().planReference()` against the current provisioning context and return
`PendingApproval` with a fresh planReference if the approval is stale. This triggers
`recordPending`, which obsoletes the old WorkItem and creates a new one for re-approval.

`check()` uses `findByCallerRef` (not `findActiveByCallerRef`) because `findActiveByCallerRef`
filters out COMPLETED and REJECTED items, making it impossible to detect approval or rejection.

**PlanApproval** from COMPLETED WorkItem:
- `planReference` — from `WorkItemRef.payload` (round-tripped via work#281)
- `approvedBy` — from `WorkItemRef.assigneeId`, with fallback to `"system"` when null
  (a WorkItem may reach COMPLETED without personal assignment, e.g. admin batch completion)
- `approvedAt` — `Instant.now()` (completion timestamp not available on WorkItemRef;
  records poll time, not actual approval time — bounded by reconciliation interval;
  file casehub-work issue for `completedAt` on WorkItemRef)

**Rejection reason:** Includes status and outcome for diagnostics —
`"approval <status> for node <nodeId>"`, extended with `": <outcome>"` when
`WorkItemRef.outcome()` is non-null. Different terminal statuses (CANCELLED vs EXPIRED vs
ESCALATED) carry meaningfully different semantics that provisioners and fault policies can
differentiate.

### Method Flows

**`check(node, action, tenancyId)`**
1. Build callerRef from tenancyId+node+action
2. `findByCallerRef(callerRef)` — if empty, return `None`
3. Map status per table above

**`recordPending(node, action, tenancyId, planReference)`**
1. Build callerRef
2. `obsoleteByCallerRef(callerRef)` — clear any stale prior WorkItem
3. Create WorkItem:
   - `title`: `"Approve <action>: <nodeId>"`
   - `callerRef`: derived key
   - `payload`: planReference
   - `tenancyId`: passed through
   - `types`: `["desiredstate-approval"]`
   - `permittedOutcomes`: `[new Outcome("approve", "Approve", null), new Outcome("reject", "Reject", null)]`
4. Return `StepOutcome.Skipped("pending approval: " + planReference)`

**WorkItem visibility:** The handler does not set `candidateGroups`, `candidateUsers`, or `scope`
on the create request. With `tenancyId` set, casehub-work scopes the WorkItem to that tenancy —
any user with work-management permissions within the tenancy can see and claim the approval
WorkItem. This is the correct default for most approval scenarios. If finer-grained routing
is needed (e.g. specific approver groups), the provisioner API would need to carry routing
hints in `ProvisionResult.PendingApproval` — a future SPI extension, not this handler's concern.

**`acknowledgeRejection(node, action, tenancyId)`**
1. Build callerRef
2. `obsoleteByCallerRef(callerRef)` — clears REJECTED WorkItem, prevents permanent
   blocking (a REJECTED WorkItem with a given callerRef would otherwise block
   `findByCallerRef` from ever returning a status that allows re-approval)

### Gotchas

- **REJECTED WorkItem blocks callerRef permanently.** A REJECTED WorkItem persists under
  the callerRef. Without `obsoleteByCallerRef` in `acknowledgeRejection()`, the next
  `check()` always returns `Rejected`, making re-approval impossible even after the fault
  policy clears the rejection. `acknowledgeRejection()` clears the REJECTED WorkItem
  so `recordPending()` can create a fresh one if the provisioner requests approval again.

- **reject() reason not stored on WorkItem.resolution.** casehub-work's `reject()` writes
  the reason to audit events only — `WorkItemRef.resolution` does not contain the rejection
  reason. The handler uses a generic reason string derived from the WorkItem status and outcome.

### CDI Activation

Same pattern as `CaseTransitionExecutor`: `@ApplicationScoped` in an adapter module,
displaces `@DefaultBean` in runtime by classpath presence. Domain modules bringing their
own handler (e.g., ops's `OpsPendingApprovalHandler`) simply don't include `work-adapter`
on the classpath.

## Scope of Changes

| File | Change |
|---|---|
| `work-adapter/src/main/java/.../work/WorkItemPendingApprovalHandler.java` | New — handler implementation |
| `work-adapter/src/test/java/.../work/WorkItemPendingApprovalHandlerTest.java` | New — 8 test cases |
| `work-adapter/pom.xml` | Update description: "WorkItem-backed PendingApprovalHandler — creates approval WorkItems via the WorkItemCreator SPI. Displaces NoOpPendingApprovalHandler by classpath presence when casehub-work is deployed." |
| `ARC42STORIES.MD` §9.4 Layer L7 | Check off WorkItemPendingApprovalHandler, update callerRef format, remove "blocked on work#281/282" note |

## Test Plan

Mock `WorkItemCreator` (simple in-memory impl in the test class):

1. `check_noWorkItem_returnsNone`
2. `check_activeWorkItem_returnsPending`
3. `check_completedWorkItem_returnsApprovedAndDoesNotObsolete`
4. `check_completedWorkItem_nullAssignee_usesSystemFallback`
5. `check_rejectedWorkItem_returnsRejectedWithOutcome`
6. `check_obsoleteWorkItem_returnsNone`
7. `recordPending_createsWorkItemWithCorrectCallerRefAndPayload`
8. `acknowledgeRejection_obsoletesCallerRef`
