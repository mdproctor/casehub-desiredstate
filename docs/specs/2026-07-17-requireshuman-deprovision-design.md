# requiresHuman Gating for Deprovision

**Issue:** #54
**Date:** 2026-07-17
**Status:** Approved

## Problem

`requiresHuman` on `DesiredNode` is a routing signal — it says "consult the HumanNodeHandler
for this node's lifecycle." Currently it only routes provision. Deprovision bypasses it entirely,
hitting the NodeProvisioner directly.

This means:
- `SimpleTransitionExecutor.executeDeprovision()` never checks `requiresHuman`
- `CaseTransitionExecutor.buildCaseDefinition()` sends all removals through the prune workflow
  regardless of `requiresHuman`
- `HumanNodeHandler` has no `onDeprovision` method
- `WorkItemHumanNodeHandler` can't create deprovision WorkItems
- The deprovision span is missing the `desiredstate.requires.human` telemetry attribute

## Design Principle

`requiresHuman` is a routing signal, not a policy decision. The flag gates whether the
HumanNodeHandler is consulted. The handler decides what each action means for the domain.
The flag should route both provision and deprovision symmetrically.

**Supersedes prior decision.** The 2026-06-26 WorkItem Human Node Handler design spec
established "deprovision is always automated" and deferred `onDeprovision` as a future
default method extension. Issue #54 identified the concrete failure: `requiresHuman` nodes
reaching `NodeProvisioner.deprovision()` produces `DeprovisionResult.Failed` for
provisioners that can't automate deprovision (e.g., `PhysicalDeviceSpec`), triggering
fault feedback on every reconciliation cycle. The "routing signal" principle corrects
this — `requiresHuman` should gate both directions symmetrically.

## Changes

### 1. HumanNodeHandler (api/)

Add `onDeprovision` as a default method returning `Skipped`. This matches the issue #54
request and the prior spec's guidance ("can be extended later with a default method").
Implementations that need human-gated deprovision override it.

```java
public interface HumanNodeHandler {
    StepOutcome onProvision(DesiredNode node, ProvisionContext context);

    default StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
        return new StepOutcome.Skipped("deprovision not handled");
    }
}
```

This preserves the SAM type (functional interface) — test lambdas for `onProvision`
continue to work. No `MockHumanNodeHandler` needed.

### 2. SimpleTransitionExecutor (runtime/)

`executeDeprovision` gains the `requiresHuman` check before the approval check, symmetric
with `executeProvision`. Precedence: requiresHuman > PendingApproval > provisioner.

Also adds the `desiredstate.requires.human` span attribute (provision already has it).

Removes the code comment `// no requiresHuman for deprovision` which documents the
superseded design decision.

### 3. NoOpHumanNodeHandler (runtime/)

Overrides `onDeprovision` returning `Skipped("requires human — no HumanNodeHandler configured")`.
Same message as provision — the signal is misconfiguration. The default method message
("deprovision not handled") is for configured handlers that don't handle deprovision;
the NoOp message signals that no handler is configured at all.

### 4. WorkItemHumanNodeHandler (work-adapter/)

**Note:** #72 questions whether work-adapter should exist at all (vs engine's
`HumanTaskTarget`). These changes are correct under the current architecture and will
be revisited if #72 concludes differently.

**CallerRef format change:** `desiredstate:<tenancyId>:<nodeId>` becomes
`desiredstate:<tenancyId>:<nodeId>:<action>`. Action is appended as a suffix — natural
evolution from the old format and consistent with the approval handler's callerRef
convention (`desiredstate-approval:{tenancyId}:{nodeId}:{action}`). Prevents collision
when a node has both provision and deprovision WorkItems in flight.

**Clean-break change.** No migration strategy — no production data exists. Any active
WorkItems with the old format (`desiredstate:<tenancyId>:<nodeId>`) become orphaned
after upgrade. `findActiveByCallerRef` will not match them; they will expire or be
manually closed.

**onDeprovision:** Overrides the default method. Same pattern as provision — check for
active WorkItem, create if needed, return Skipped. WorkItem type:
`desiredstate-deprovision` (provision uses `desiredstate-provision`).

### 5. CaseTransitionExecutor (engine-adapter/)

**Dual-path clarification:** CTE does not call `HumanNodeHandler.onDeprovision()` — just
as it does not call `HumanNodeHandler.onProvision()`. CTE handles human nodes through its
own `humanTask` binding mechanism, which uses the engine's HITL infrastructure to create
WorkItems. The `onDeprovision` method (§1) is only invoked by `SimpleTransitionExecutor`.

`buildCaseDefinition` separates human removals from automated removals, matching how it
already separates human additions. Automated removals go through the prune workflow as before.

**Binding name convention:** Action-namespaced for consistency across all identifiers.
Provision bindings renamed from `"human-<nodeId>"` to `"human-provision-<nodeId>"`.
Deprovision bindings use `"human-deprovision-<nodeId>"`.

`buildOptimisticResult` updated — human removals return `Skipped("routed to WorkItem")`
instead of `Succeeded`.

## Documentation Updates

The following javadoc and comments become incorrect and must be updated:

1. **`DesiredNode` record javadoc** (api/) — "whether provisioning requires human approval"
   → "whether this node's lifecycle actions require human handling"
2. **`HumanNodeHandler` interface javadoc** (api/) — "Handles provisioning of nodes marked
   with requiresHuman = true. Called by TransitionExecutor when encountering a human node
   during the provision phase." → "Handles lifecycle actions for nodes marked with
   requiresHuman = true. Called by SimpleTransitionExecutor for both provision and
   deprovision phases."
3. **`SimpleTransitionExecutor.executeDeprovision` code comment** (runtime/) — remove
   `// Check for prior approval state before calling provisioner (no requiresHuman for
   deprovision)` — the parenthetical documents the superseded decision and becomes
   factually wrong.

## Scope

| Module | Files | Nature |
|--------|-------|--------|
| api/ | 1 | SPI default method addition + javadoc |
| runtime/ | 3 | Executor gate + NoOp impl + tests |
| work-adapter/ | 1 | WorkItem handler + tests |
| engine-adapter/ | 1 | Case definition builder + tests |

Scale: S. Complexity: Low.

## Out of Scope

- **#79** — Splitting `requiresHuman` into per-action flags (`requiresHumanProvision` /
  `requiresHumanDeprovision`). The handler already provides per-action control — the flag
  is the routing signal, the handler is the decision-maker.
- **#80** — Lifecycle coordination between provision and deprovision WorkItems (e.g.,
  cancelling a provision WorkItem when deprovision is requested). Separate concern — the
  callerRef format ensures distinct callerRefs but coordination is not addressed.
