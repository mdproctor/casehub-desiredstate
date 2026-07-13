# CBR Revise Step — Outcome Feedback Loop

**Issue:** #76
**Date:** 2026-07-12
**Status:** Design

## Problem

The CBR cycle in desiredstate implements Retrieve and Reuse but not Revise. When
`CbrFaultPolicy` or `CbrSituationRecompiler` proposes a configuration, the reconciliation
loop applies it and observes the outcome — but that outcome is never fed back to the case
store. Without Revise, CBR recommendations are static: quality depends entirely on the
initial case corpus.

Two paths produce CBR-originated changes:

1. **Fault path:** `CbrFaultPolicy.onFault()` → `List<GraphMutation>` → mutations applied
   to desired graph in cycle N → nodes provisioned in cycle N+1
2. **Situation path:** `CbrSituationRecompiler.recompile()` → `CompilationResult` → new
   graph applied → reconciled in subsequent cycles

Neither path tracks what it proposed. When execution outcomes arrive in the next cycle,
there is no correlation back to the CBR decision.

## Design

### Outcome Granularity

**Per-node outcomes, observed one cycle later.**

The reconciliation loop already produces per-node `StepOutcome` in `TransitionResult`.
The tracking overhead is identical for binary and per-node granularity — the only
difference is what we report. Per-node enables proportional confidence adjustment:
a configuration that's 90% right retains more confidence than one that's 0% right.

One-cycle observation is sufficient. When CBR-originated nodes are executed in cycle N+1,
the `TransitionResult` provides the outcome immediately. If nodes drift later, that enters
the CBR pipeline as a new fault event — the Revise for the original proposal is already
settled. No multi-cycle convergence tracking needed.

### Architecture

A `CbrProposalTracker` mediates between CBR components and the reconciliation loop:

```
Cycle N (full-graph reconcile):
  detectDrift()     → CbrFaultPolicy may record proposals for drift-triggered faults
  plan() + execute()→ provisions/deprovisions (including drift-triggered CBR nodes)
  matchOutcomes()   → resolves ALL pending proposals (drift + prior-cycle fault)
  faultFeedback()   → CbrFaultPolicy records NEW proposals for execution failures
  emitCycleEvents() → normal events + CBR outcome events from matchOutcomes

Cycle N+1 (full-graph reconcile):
  detectDrift()     → may record new drift proposals
  plan() + execute()→ provisions CBR-originated nodes from cycle N's faultFeedback
  matchOutcomes()   → resolves cycle N fault-feedback proposals + any drift proposals
  faultFeedback()   → may record new proposals if CBR nodes failed again
```

Two proposal recording paths have different observation timing:

- **Drift-triggered proposals** (from `detectDrift()` → `CbrFaultPolicy.onFault()`):
  recorded BEFORE plan/execute in the same cycle. `matchOutcomes()` resolves them in
  the same cycle against that cycle's `TransitionResult`. The outcome is real — the
  proposed nodes were planned and executed in the same cycle.

- **Fault-feedback proposals** (from `faultFeedback()` → `CbrFaultPolicy.onFault()`):
  recorded AFTER `matchOutcomes()` in cycle N. Resolved in cycle N+1's `matchOutcomes()`.

`matchOutcomes()` runs unconditionally — including on empty-plan cycles where desired
matches actual. An empty plan produces an empty `TransitionResult`; CBR-proposed nodes
that are already present in the desired graph are correctly classified as
`ALREADY_PRESENT` (success).

**Type-filtered reconciliation (`reconcileTypes()`):** `matchOutcomes()` is only called
from full-graph `reconcile()`, not from `reconcileTypes()`. Rationale: `matchOutcomes()`
atomically removes all pending proposals for a tenant (`pending.remove(tenancyId)`).
In a type-filtered cycle, proposals for out-of-scope node types would be incorrectly
matched against a partial `TransitionResult`. Full-graph `reconcile()` is triggered by
events (including actual state changes from provisioners reporting back after
type-filtered execution), ensuring proposals are matched against the complete picture.

### SPI Changes (breaking — pre-release)

`FaultPolicy.onFault()` gains a `tenancyId` parameter:

```java
List<GraphMutation> onFault(String tenancyId, FaultEvent event,
                            DesiredStateGraph current, ActualState actual);
```

`SituationRecompiler.recompile()` gains a `tenancyId` parameter:

```java
Optional<CompilationResult> recompile(String tenancyId,
    DesiredStateGraph current, ActualState actual,
    ActiveSituation situation, DesiredStateGraphFactory factory);
```

**Rationale:** Both CBR components need to record proposals keyed by tenant. Side-channel
approaches (ThreadLocal, unstamped queues) have concurrency hazards in multi-tenant
systems. The SPI is more correct with tenancyId — any policy or recompiler could
legitimately make tenant-aware decisions.

**Runtime engine changes (breaking):**

`FaultPolicyEngine.evaluate()` gains `tenancyId`:

```java
public List<GraphMutation> evaluate(String tenancyId, FaultEvent event,
                                    DesiredStateGraph current, ActualState actual)
```

Call sites (3 production, 6 test):
- `ReconciliationLoop.TenantLoop.detectDrift()` — tenancyId available as field
- `ReconciliationLoop.TenantLoop.faultFeedback()` — 2 calls, tenancyId available as field
- `FaultPolicyEngineTest` — 6 test calls

`SituationRecompilerEngine.recompile()` gains `tenancyId`:

```java
public Optional<CompilationResult> recompile(String tenancyId,
    DesiredStateGraph current, ActualState actual,
    ActiveSituation situation, DesiredStateGraphFactory factory)
```

Call sites (1 production, 6 test):
- `DesiredStateReplanDispatch.replan()` — tenancyId already extracted from `args`
- `SituationRecompilerEngineTest` — 6 test calls

**Migration:** casehub-ops has FaultPolicy and SituationRecompiler implementations
(13 FaultPolicy, 2 SituationRecompiler). One-line signature change per implementation.

### New Types (api/)

```java
public record CbrProposal(
    String sourceId,
    CbrPath path,
    Set<NodeId> affectedNodeIds,
    Instant timestamp
) {
    public CbrProposal {
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(path);
        affectedNodeIds = Set.copyOf(affectedNodeIds);
        Objects.requireNonNull(timestamp);
    }
}

public enum CbrPath { FAULT, SITUATION }

public record CbrOutcomeData(
    String tenancyId,
    String sourceId,
    CbrPath path,
    Map<String, String> nodeOutcomes,
    int successCount,
    int failureCount,
    int resolvedCount,
    double successRate,
    Instant proposedAt,
    Instant observedAt
) {
    public CbrOutcomeData {
        Objects.requireNonNull(tenancyId);
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(path);
        nodeOutcomes = Map.copyOf(nodeOutcomes);
        Objects.requireNonNull(proposedAt);
        Objects.requireNonNull(observedAt);
    }
}
```

New event type constant in new `CbrEventTypes` class (api/):

```java
public final class CbrEventTypes {
    private CbrEventTypes() {}

    public static final String CBR_OUTCOME = "io.casehub.cbr.outcome";
}
```

This is `io.casehub.cbr.outcome` (not `io.casehub.desiredstate.cbr.outcome`) — a
standard type for all CBR outcomes across the platform. The constant lives in
`CbrEventTypes`, not `DesiredStateEventTypes`, because the `io.casehub.cbr.*` namespace
is platform-level — `DesiredStateEventTypes` exclusively holds `io.casehub.desiredstate.*`
types. The `source` URI (`urn:io.casehub:desiredstate`) distinguishes the producer.
When other modules add Revise, they emit the same event type with their own source.
Neocortex has one consumer for all CBR outcomes.

### CbrProposalTracker (runtime/)

```java
@ApplicationScoped
public class CbrProposalTracker {

    private final ConcurrentHashMap<String, List<CbrProposal>> pending =
        new ConcurrentHashMap<>();

    public void recordProposal(String tenancyId, CbrProposal proposal) {
        pending.computeIfAbsent(tenancyId, k -> new CopyOnWriteArrayList<>())
            .add(proposal);
    }

    public List<CbrOutcomeData> matchOutcomes(String tenancyId,
            TransitionResult result, DesiredStateGraph currentGraph) {
        List<CbrProposal> proposals = pending.remove(tenancyId);
        if (proposals == null || proposals.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<CbrOutcomeData> outcomes = new ArrayList<>();

        for (CbrProposal proposal : proposals) {
            Map<String, String> nodeOutcomes = new LinkedHashMap<>();
            int success = 0, failure = 0;

            for (NodeId nodeId : proposal.affectedNodeIds()) {
                StepOutcome outcome = result.outcomes().get(nodeId);
                if (outcome == null) {
                    if (!currentGraph.nodes().containsKey(nodeId)) {
                        nodeOutcomes.put(nodeId.value(), "SUPERSEDED");
                    } else {
                        nodeOutcomes.put(nodeId.value(), "ALREADY_PRESENT");
                        success++;
                    }
                } else {
                    switch (outcome) {
                        case StepOutcome.Succeeded s -> {
                            nodeOutcomes.put(nodeId.value(), "SUCCEEDED"); success++;
                        }
                        case StepOutcome.Failed f -> {
                            nodeOutcomes.put(nodeId.value(), "FAILED"); failure++;
                        }
                        case StepOutcome.Skipped s ->
                            nodeOutcomes.put(nodeId.value(), "SKIPPED");
                        case StepOutcome.Rejected r -> {
                            nodeOutcomes.put(nodeId.value(), "REJECTED"); failure++;
                        }
                    }
                }
            }

            int resolved = success + failure;
            if (resolved == 0) continue;
            double successRate = (double) success / resolved;
            outcomes.add(new CbrOutcomeData(
                tenancyId, proposal.sourceId(), proposal.path(),
                nodeOutcomes, success, failure, resolved, successRate,
                proposal.timestamp(), now));
        }
        return outcomes;
    }

    public void clearTenant(String tenancyId) {
        pending.remove(tenancyId);
    }
}
```

### Integration Points

**CbrFaultPolicy** — after selecting the best adapted configuration, records the proposal.
Empty mutation sets are not recorded — zero mutations means the selected configuration
is identical to the current graph, so there is nothing to track:

```java
Set<NodeId> affectedNodeIds = GraphDiff.computeMutations(current, selected.graph())
    .stream()
    .map(GraphDiff::targetNodeId)
    .filter(Objects::nonNull)
    .collect(Collectors.toSet());

if (!affectedNodeIds.isEmpty()) {
    tracker.recordProposal(tenancyId, new CbrProposal(
        selected.sourceId(), CbrPath.FAULT, affectedNodeIds, Instant.now()));
}
```

**CbrSituationRecompiler** — same pattern with the same empty-set guard:

```java
Set<NodeId> affectedNodeIds = GraphDiff.computeMutations(current, selected.graph())
    .stream()
    .map(GraphDiff::targetNodeId)
    .filter(Objects::nonNull)
    .collect(Collectors.toSet());

if (!affectedNodeIds.isEmpty()) {
    tracker.recordProposal(tenancyId, new CbrProposal(
        selected.sourceId(), CbrPath.SITUATION, affectedNodeIds, Instant.now()));
}
```

**ReconciliationLoop.TenantLoop.reconcile()** — `matchOutcomes()` runs unconditionally,
including on empty-plan cycles:

```java
desired = detectDrift(desired, actual, driftedNodes);

TransitionPlan plan = plan(desired, actual);

if (plan.isEmpty()) {
    TransitionResult emptyResult = new TransitionResult(Map.of());
    List<CbrOutcomeData> cbrOutcomes = cbrTracker.matchOutcomes(
        tenancyId, emptyResult, desired);
    if (!driftedNodes.isEmpty() || !activeProblems.isEmpty()) {
        emitCycleEvents(desired, plan, emptyResult, actual, driftedNodes);
    }
    emitCbrOutcomeEvents(cbrOutcomes);
    return;
}

TransitionResult result = execute(plan, tenancyId);

List<CbrOutcomeData> cbrOutcomes = cbrTracker.matchOutcomes(
    tenancyId, result, desired);

faultFeedback(desired, plan, result, actual);

emitCycleEvents(desired, plan, result, actual, driftedNodes);
emitCbrOutcomeEvents(cbrOutcomes);
```

Note: `reconcileTypes()` does NOT call `matchOutcomes()`. See Architecture section
for rationale.

**ReconciliationLoop.TenantLoop.stop()** — clean up:

```java
cbrTracker.clearTenant(tenancyId);
```

### GraphDiff Enhancement

`GraphDiff.targetNodeId()` — consolidates the existing `FaultPolicyEngine.getTargetNodeId()`
private method into `GraphDiff` as a package-private static utility. `FaultPolicyEngine`
delegates to `GraphDiff.targetNodeId()`, eliminating the duplication. `GraphDiff` remains
package-private (`final class`, no `public` modifier) — all callers (`CbrFaultPolicy`,
`CbrSituationRecompiler`, `FaultPolicyEngine`) are in the same `runtime/` package:

```java
static NodeId targetNodeId(GraphMutation mutation) {
    return switch (mutation) {
        case GraphMutation.AddNode add -> add.node().id();
        case GraphMutation.RemoveNode remove -> remove.id();
        case GraphMutation.UpdateNode update -> update.id();
        case GraphMutation.AddDependency ignored -> null;
        case GraphMutation.RemoveDependency ignored -> null;
    };
}
```

### CloudEvent Emission

New method on `ReconciliationEventEmitter`:

```java
public CloudEvent cbrOutcome(CbrOutcomeData data) {
    return base(CbrEventTypes.CBR_OUTCOME)
        .withSubject(data.sourceId())
        .withExtension("tenancyid", data.tenancyId())
        .withExtension("cbrpath", data.path().name().toLowerCase())
        .withExtension("successrate", String.valueOf(data.successRate()))
        .withData("application/json", serialize(data))
        .build();
}
```

### Edge Cases

**Superseded nodes:** If the desired graph is updated between cycles (user calls
`updateDesired()`), CBR-originated nodes may be removed before execution. These are
reported as `SUPERSEDED` and excluded from success/failure counts. The proposal is
still resolved — the case store receives a record that the configuration was never
fully applied.

**Multiple proposals per cycle:** Multiple faults in one cycle may each trigger
CbrFaultPolicy independently. Each call produces its own proposal with its own
affected nodeIds. The tracker holds all proposals for the tenant and matches each
independently. Overlapping nodeIds across proposals are possible — each proposal
gets the outcome for its specific nodes.

**Empty plan cycles:** If the reconciliation cycle produces an empty plan (desired
matches actual), `matchOutcomes()` is still called with an empty `TransitionResult`.
CBR-proposed nodes present in the desired graph are classified as `ALREADY_PRESENT`
(success). Nodes removed from the graph are classified as `SUPERSEDED`. This is the
common success path: CBR proposes nodes in cycle N → nodes provisioned in cycle N →
plan is empty in cycle N+1 → proposals resolved as `ALREADY_PRESENT`.

**Tenant cleanup:** When a TenantLoop stops, `clearTenant()` removes any unresolved
proposals. No outcome event is emitted — "no outcome = no evidence = no confidence
change" is the correct CBR behavior for proposals that were never evaluated.

### Neocortex Revise SPI (separate issue)

The desiredstate side emits `io.casehub.cbr.outcome` CloudEvents. Neocortex needs a
consumer and an outcome recording mechanism.

**New method on `CbrCaseMemoryStore`:**

```java
void recordOutcome(String sourceId, CbrOutcome outcome);
```

**New type:**

```java
public record CbrOutcome(
    Outcome result,
    double successRate,
    String detail,
    Instant observedAt
) {}

public enum Outcome { SUCCESS, PARTIAL, FAILURE }
```

**Outcome classification from successRate:**
- `SUCCESS` — successRate = 1.0 (all resolved nodes succeeded)
- `FAILURE` — successRate = 0.0 (all resolved nodes failed)
- `PARTIAL` — 0.0 < successRate < 1.0

These are exact boundary thresholds, not configurable. The Outcome enum classifies the
observed result for operational queries. The EMA formula uses `successRate` directly
for confidence adjustment — the Outcome enum does not affect the calculation.

**Confidence adjustment:** Standard exponential moving average:

```
newConfidence = (1 - learningRate) * oldConfidence + learningRate * successRate
```

Where `learningRate` is configurable (default 0.2). This pulls confidence toward the
observed success rate over time:
- SUCCESS (rate=1.0) → confidence increases toward 1.0
- PARTIAL (rate=0.7) → moderate adjustment toward 0.7
- FAILURE (rate=0.0) → confidence decreases (but never to zero in one step)

With learningRate=0.2 and oldConfidence=0.8:
- rate=1.0 → 0.84 (gradual increase)
- rate=0.5 → 0.74 (moderate decrease)
- rate=0.0 → 0.64 (significant decrease)

**Reactive parity:** `ReactiveCbrCaseMemoryStore` gets the same method returning
`Uni<Void>`.

**sourceId contract:** `sourceId` in the outcome event equals the `caseId` passed to
`CbrCaseMemoryStore.store()`. The bridge implementation (`ConfigurationRetriever`)
must set `RetrievedConfiguration.sourceId` to the store's `caseId`. This is the
contract that allows the outcome consumer to look up the correct case.

**Qdrant implementation:** Looks up the case by sourceId (equal to caseId), updates
the `outcome` payload field and recalculates the `confidence` payload field.

### Testing Strategy

**CbrProposalTrackerTest:**
- Record proposal, match outcomes → correct CbrOutcomeData produced
- No pending proposals → matchOutcomes returns empty
- Multiple proposals for same tenant → each matched independently
- Superseded nodes (removed from graph between cycles) → reported correctly
- All nodes SKIPPED/SUPERSEDED (resolvedCount=0) → no CbrOutcomeData emitted
- clearTenant removes pending proposals
- Concurrent access from multiple tenants → no interference

**CbrFaultPolicy integration (updated tests):**
- After onFault selects a configuration → proposal recorded in tracker
- No candidates found → no proposal recorded
- Candidates filtered by confidence → no proposal recorded
- Selected configuration identical to current graph (empty mutations) → no proposal recorded
- tenancyId threaded correctly

**CbrSituationRecompiler integration (updated tests):**
- After recompile selects a configuration → proposal recorded with correct delta nodeIds
- Graph delta computed correctly (added, removed, updated nodes)

**ReconciliationLoop integration (updated tests):**
- CBR fault in cycle N → outcome matched in cycle N+1 → CloudEvent emitted
- CBR situation recompilation → outcome matched → CloudEvent emitted
- Superseded nodes → correct event emitted
- Multiple CBR proposals across cycles → each resolved independently
- Empty plan cycle → pending proposals resolved as ALREADY_PRESENT
- Drift-triggered proposal → matched in same cycle (not deferred)
- reconcileTypes() does not call matchOutcomes → proposals remain pending
- TenantLoop.stop() → pending proposals cleared

**ReconciliationEventEmitter:**
- cbrOutcome produces correct CloudEvent with type, subject, extensions, data

## Scope

**In scope (#76 — desiredstate):**
- SPI changes: tenancyId on FaultPolicy, SituationRecompiler
- New types: CbrProposal, CbrPath, CbrOutcomeData
- CbrProposalTracker runtime bean
- CbrFaultPolicy/CbrSituationRecompiler proposal recording
- ReconciliationLoop integration (matchOutcomes, event emission)
- GraphDiff.targetNodeId() utility
- ReconciliationEventEmitter.cbrOutcome()
- All tests

**Separate issue (neocortex):**
- CbrCaseMemoryStore.recordOutcome() SPI method
- CbrOutcome type
- Qdrant implementation of outcome recording
- Confidence adjustment logic
- CloudEvent consumer for `io.casehub.cbr.outcome`

**Separate issue (casehub-ops):**
- FaultPolicy implementations: add tenancyId parameter
- SituationRecompiler implementations: add tenancyId parameter

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Outcome granularity | Per-node | Zero cost over binary, enables proportional confidence |
| Observation window | One cycle | TransitionResult is immediate. Drift is a new fault event |
| Tracking mechanism | In-memory mediator | Same-JVM per-tenant affinity. No persistence needed |
| tenancyId threading | SPI parameter | Explicit > implicit. Pre-release, breaking changes are free |
| CloudEvent type | `io.casehub.cbr.outcome` | Platform-standard, not desiredstate-specific. Source URI distinguishes producer |
| Confidence adjustment | Exponential moving average | Bidirectional: success increases, failure decreases, converges to observed rate |
| Event type placement | `CbrEventTypes` (new class) | `io.casehub.cbr.*` namespace is platform-level, not `io.casehub.desiredstate.*` |
| Matching scope | Full-graph `reconcile()` only | Atomic removal prevents incorrect matching against type-filtered views |
