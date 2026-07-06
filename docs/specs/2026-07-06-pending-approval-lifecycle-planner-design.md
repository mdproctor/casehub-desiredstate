# PendingApproval Gate, Lifecycle Transitions, and Planner-Backed GoalCompiler

**Issues:** #46, #58, #56
**Date:** 2026-07-06
**Status:** Approved

## Overview

Three connected changes to the desired-state runtime:

1. **#46 — PendingApproval gate in pipeline example:** Provisioner-level approval gate on Gold-tier
   pipeline stages, configurable per-stage via the blueprint. No core API changes.
2. **#58 — Lifecycle transitions:** GoalCompiler SPI evolves to return `CompilationResult` — a
   sealed type expressing single-graph or multi-phase lifecycles with completion conditions. New
   `LifecycleManager` in runtime orchestrates phase transitions.
3. **#56 — Planner-backed GoalCompiler:** New `examples/expansion/` module demonstrating a
   planner (HTN decomposition) behind the GoalCompiler SPI, producing lifecycle phases with
   fault-triggered replanning via SituationRecompiler.

## API Types (api/ module)

### New: CompilationResult

Sealed return type replacing `DesiredStateGraph` from `GoalCompiler.compile()`.

```java
public sealed interface CompilationResult {

    record SingleGraph(DesiredStateGraph graph) implements CompilationResult {}

    record Lifecycle(List<Phase> phases) implements CompilationResult {}

    static CompilationResult single(DesiredStateGraph graph) {
        return new SingleGraph(graph);
    }

    static CompilationResult lifecycle(List<Phase> phases) {
        if (phases.isEmpty()) throw new IllegalArgumentException("Lifecycle must have at least one phase");
        return new Lifecycle(List.copyOf(phases));
    }
}
```

Callers pattern-match on `SingleGraph` or `Lifecycle` explicitly. No `initialGraph()` escape
hatch — sealed interfaces exist for exhaustive matching, and silent lifecycle degradation is
worse than a compile-time signal.

### New: Phase

```java
public record Phase(
    String id,
    DesiredStateGraph graph,
    CompletionCondition completionCondition
) {
    public Phase {
        Objects.requireNonNull(id);
        Objects.requireNonNull(graph);
        Objects.requireNonNull(completionCondition);
    }

    public boolean isTerminal() {
        return completionCondition instanceof CompletionCondition.Never;
    }
}
```

No `successorId` — the `List<Phase>` ordering in `Lifecycle` is the successor sequence. Phase N's
successor is phase N+1. A last phase with `CompletionCondition.never()` reconciles indefinitely
(terminal). A last phase with a satisfiable condition completes the lifecycle — the tenant
continues with unmanaged reconciliation of the final phase's graph.

### New: CompletionCondition

```java
@FunctionalInterface
public interface CompletionCondition {
    boolean isComplete(DesiredStateGraph desired, ActualState actual);

    static CompletionCondition allPresent() {
        return (desired, actual) -> desired.nodes().keySet().stream()
            .allMatch(id -> actual.statuses().getOrDefault(id, NodeStatus.UNKNOWN) == NodeStatus.PRESENT);
    }

    static CompletionCondition never() { return new Never(); }

    record Never() implements CompletionCondition {
        public boolean isComplete(DesiredStateGraph desired, ActualState actual) { return false; }
    }
}
```

`allPresent()` is the standard condition — all nodes PRESENT. `never()` marks terminal phases.
Domain-specific conditions are lambdas or named implementations.

### Modified: GoalCompiler

```java
public interface GoalCompiler<G> {
    CompilationResult compile(G goals, DesiredStateGraphFactory factory);
}
```

Return type changes from `DesiredStateGraph` to `CompilationResult`. Breaking change — all
implementations migrate mechanically: wrap return in `CompilationResult.single(graph)`.

### Modified: SituationRecompiler

```java
public interface SituationRecompiler {
    Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActiveSituation situation,
        DesiredStateGraphFactory factory);
}
```

Return type changes from `Optional<DesiredStateGraph>` to `Optional<CompilationResult>`. A
situation-driven replan may produce either a single replacement graph or a full lifecycle.
`NoOpSituationRecompiler` returns `Optional.empty()` (unchanged behavior).

## Runtime Changes

### New: ReconciliationListener

```java
@FunctionalInterface
public interface ReconciliationListener {
    void onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual);
}
```

Registration: `ReconciliationLoop.start()` gains an overload accepting a listener:

```java
public void start(String tenancyId, DesiredStateGraph desired, ReconciliationListener listener)
```

The existing `start(tenancyId, desired)` delegates with a null listener. `LifecycleManager`
always registers its listener (for both `SingleGraph` and `Lifecycle` tenants — see below).

Late binding for tenants started outside `LifecycleManager`:

```java
public void setListener(String tenancyId, ReconciliationListener listener)
```

Sets the listener on an already-running tenant's loop. Used by `LifecycleManager.updateDesired()`
when installing a lifecycle on a tenant that was started via `ReconciliationLoop.start()` directly.

The listener fires **unconditionally** at the end of every reconciliation cycle — including
cycles where the plan is empty and no drift is detected. The current `reconcile()` and
`reconcileTypes()` methods early-return when the plan is empty and no drift/active-problems
exist; the listener call must be placed before those returns (or in a `finally`-equivalent
position after `readActual()`). This is critical because `CompletionCondition.allPresent()` is
satisfied precisely when all nodes are stable and the reconciliation plan is empty — an
early-return that skips the listener would make phase transitions unreachable.

The listener receives the **full** desired graph (from `desiredRef.get()`), not a type-filtered
subset. For type-filtered reconciliation, the actual state passed to the listener is from the
filtered reconciliation — the `CompletionCondition` evaluates against the original phase graph
(see LifecycleManager), so this is sufficient.

The listener call is synchronous within the reconciliation thread — phase transitions happen
before the next cycle starts. No gap where nothing is being reconciled. Concurrent type-filtered
reconciliation threads may call the listener concurrently — the `LifecycleManager` listener is
thread-safe via CAS-based transition (see below).

### New: LifecycleManager

Sits between callers and `ReconciliationLoop`. Orchestrates phase transitions without the loop
knowing about lifecycle.

```java
@ApplicationScoped
public class LifecycleManager {
    private final ReconciliationLoop loop;
    private final ConcurrentHashMap<String, TenantLifecycle> lifecycles = new ConcurrentHashMap<>();

    public void start(String tenancyId, CompilationResult result);
    public void stop(String tenancyId);
    public void updateDesired(String tenancyId, CompilationResult result);
}
```

Behavior:

**`start(tenancyId, result)`:**
- Both variants register the LifecycleManager as listener via
  `loop.start(tenancyId, graph, this::onCycleCompleted)`. For `SingleGraph` tenants the listener
  fires, finds no `TenantLifecycle` entry, and returns immediately — negligible overhead. This
  ensures that if a later `updateDesired()` installs a lifecycle, the listener is already
  registered.
- `SingleGraph`: no lifecycle tracking beyond the listener registration
- `Lifecycle`: creates `TenantLifecycle` entry with phase list and initial index

**`updateDesired(tenancyId, result)`:**
- If tenant has active lifecycle: interrupts lifecycle (removes `TenantLifecycle` entry), then:
  - `SingleGraph`: calls `loop.updateDesired(tenancyId, graph)` — unmanaged
  - `Lifecycle`: atomically replaces the `TenantLifecycle` entry with a new one for the new
    lifecycle, calls `loop.updateDesired(tenancyId, phase1.graph())`
- If no lifecycle:
  - `SingleGraph`: delegates to `loop.updateDesired(tenancyId, graph)`
  - `Lifecycle`: calls `loop.setListener(tenancyId, this::onCycleCompleted)` (registers listener
    if tenant was started outside LifecycleManager), creates `TenantLifecycle` entry, calls
    `loop.updateDesired(tenancyId, phase1.graph())`

**`stop(tenancyId)`:** removes any `TenantLifecycle` entry, delegates to `loop.stop()`.

`TenantLifecycle` is an immutable record holding the phase list and current phase index. State
transitions use `ConcurrentHashMap.replace(tenancyId, current, next)` for atomic replacement —
no mutable phase index. After a successful CAS on the graph ref, the lifecycle entry is
atomically replaced with a new `TenantLifecycle` at the next phase index. If `replace()` fails
(concurrent replan removed the entry), the lifecycle was interrupted — correct behavior.

### Phase transition via CAS

`ReconciliationLoop` gains a CAS method:

```java
public boolean compareAndSetDesired(String tenancyId,
    DesiredStateGraph expected, DesiredStateGraph newDesired)
```

Delegates to `TenantLoop.desiredRef.compareAndSet(expected, newDesired)`. Returns true if the
swap succeeded; false if the current graph no longer matches `expected`.

The LifecycleManager's listener callback:

1. Evaluates `CompletionCondition.isComplete()` against the **original phase graph**
   (`Phase.graph()`), not the current desired (which may have fault mutations). This ensures
   fault-generated nodes (e.g., AI_REVIEW, HUMAN_REVIEW) in the mutated graph do not block
   phase completion — only the original compiled nodes are checked.
2. If complete and more phases remain: CAS the desired ref from the current graph to the next
   phase's graph. If CAS fails (concurrent replan or fault mutation changed the ref), no
   transition — the completion condition is re-evaluated on the next reconciliation cycle.
3. If complete and this is the last phase: lifecycle completes. The `TenantLifecycle` entry is
   removed. The tenant continues with unmanaged reconciliation of the current graph. No index
   overflow — both `allPresent()` and `never()` are valid on the last phase.

### Concurrent safety with SituationRecompiler

`DesiredStateReplanDispatch` injects `LifecycleManager` (not `ReconciliationLoop` directly)
and calls `lifecycleManager.updateDesired(tenancyId, result)`. This routes all replan-driven
graph updates through the LifecycleManager, which handles lifecycle interruption correctly.

For lifecycle-managed tenants, two concurrent update paths exist:
- **Lifecycle transition (CAS):** LifecycleManager listener evaluates completion → CAS to next
  phase. Fails safely if graph was changed concurrently.
- **External replan:** `DesiredStateReplanDispatch` → `LifecycleManager.updateDesired()` →
  lifecycle interrupted, new graph/lifecycle installed.

The CAS prevents the race where a lifecycle transition overwrites a concurrent replan. If a
replan changes the graph between the listener callback and the CAS attempt, CAS fails and the
transition is skipped. On the next cycle, the LifecycleManager detects the graph no longer
matches the expected phase and interrupts the lifecycle.

`ReconciliationLoop.start()` and `updateDesired()` remain public — `ReconciliationLoop` is the
foundation layer. `LifecycleManager` is the preferred entry point for lifecycle-aware callers.
Non-lifecycle callers may use `ReconciliationLoop` directly.

### What ReconciliationLoop does NOT gain

- No knowledge of phases, lifecycles, or completion
- New public methods: listener-accepting `start()` overload, `setListener()`,
  `compareAndSetDesired()`
- `start()`, `stop()`, `updateDesired()` existing APIs unchanged
- The loop remains a single-concern reconciler

## #46 — Pipeline PendingApproval Gate

### Blueprint changes

`PipelineBlueprint.builder()` gains `approvalRequired` on transformer and sink entries (optional
boolean, defaults to false).

### Spec changes

`TransformerSpec` and `SinkSpec` gain `boolean approvalRequired` field. Existing constructors
remain as convenience (approvalRequired=false).

```java
public record TransformerSpec(
    List<String> aggregations, List<String> reshapeRules,
    String outputFormat, boolean approvalRequired
) implements NodeSpec {
    public TransformerSpec(List<String> aggregations, List<String> reshapeRules, String outputFormat) {
        this(aggregations, reshapeRules, outputFormat, false);
    }
}
```

Same pattern for `SinkSpec`.

### Provisioner change

In `PipelineProvisioner.dispatchToBackend()`, before dispatching to the backend:

```java
if (node.spec() instanceof TransformerSpec ts && ts.approvalRequired() && !context.hasApproval()) {
    return new ProvisionResult.PendingApproval(node.id(), "gold-tier:" + node.id().value());
}
if (node.spec() instanceof SinkSpec ss && ss.approvalRequired() && !context.hasApproval()) {
    return new ProvisionResult.PendingApproval(node.id(), "gold-tier:" + node.id().value());
}
```

On re-entry (approval granted), `context.hasApproval()` is true — gate skipped, backend executes.

### What does NOT change

- No core API changes
- `SimpleTransitionExecutor` already handles the full PendingApproval protocol
- Three-tier fault escalation is orthogonal (faults after provisioning; approval before)

### Tests

- `goldTierApproval_pendingThenApproved` — full cycle with `MockPendingApprovalHandler`: provision
  → PendingApproval → recordPending → program Approved → re-provision → Success
- `goldTierApproval_rejected` — handler returns Rejected → StepOutcome.Rejected, acknowledgeRejection called
- `goldTierApproval_defaultOff` — standard blueprint, no approval interaction

## #56 — Expansion Example (Planner-Backed GoalCompiler)

### New module: examples/expansion/

Maven artifact: `casehub-desiredstate-example-expansion`.
Package: `io.casehub.desiredstate.example.expansion`.

### Domain types

**ExpansionGoal** — goal declaration with location, required structures, and defense posture.

**ExpansionNodeTypes:**

| Constant | Phase | Value |
|----------|-------|-------|
| PROBE | build | `"probe"` |
| NEXUS | build | `"nexus"` |
| PYLON | build | `"pylon"` |
| CANNON | build | `"cannon"` |
| PATROL | defend | `"patrol"` |
| MONITOR | defend | `"monitor"` |
| RESPONSE | defend | `"response"` |

Build phase: PROBE → NEXUS → PYLON → CANNON (linear dependency chain).
Defend phase: PATROL + MONITOR → RESPONSE (fan-in).

### ExpansionGoalCompiler

Implements `GoalCompiler<ExpansionGoal>`. Returns `CompilationResult.lifecycle()` with two phases:

1. **build** — construction graph, `CompletionCondition.allPresent()`
2. **defend** — defense graph, `CompletionCondition.never()` (terminal — reconciles indefinitely)

The `compileBuildPhase` method is a hand-coded HTN decomposition: "establish base" decomposes
into subtasks based on `requiredStructures`. Demonstrates that a planner sits behind the
GoalCompiler interface — the runtime doesn't know planning happened.

### Carry-forward

Phase transition replaces the graph — prior-phase nodes are NOT automatically carried forward
for reconciliation. This is intentional: each phase has a focused graph expressing exactly what
should be reconciled during that phase.

**GoalCompilers that need continued reconciliation of prior-phase artifacts include those nodes
in subsequent phase graphs.** The defense phase graph includes a nexus node (identical spec to
the build phase) so the nexus continues to be reconciled during defense. If the nexus goes down,
the reconciliation loop detects it and re-provisions it — no RAS roundtrip needed for
infrastructure the phase explicitly declares.

Nodes that are NOT in the defense graph (probe, pylon, cannon) are intentionally unreconciled —
they served a build-time purpose and don't need continuous maintenance.

This pattern keeps the carry-forward decision in the GoalCompiler (which has domain knowledge
about what needs continued maintenance) rather than in the runtime (which would need arbitrary
carry-forward rules). The `ActualStateAdapter` contract is unchanged — it reports statuses
for nodes in the desired graph, which now includes any carried-forward nodes.

### Fault-triggered replanning

- `ExpansionFaultPolicy` — handles simple cases (retry on transient failures)
- Persistent faults → CloudEvents → RAS Ganglia → `ActiveSituation`
- `ExpansionSituationRecompiler` implements `SituationRecompiler` — calls
  `ExpansionGoalCompiler.compile()` with revised goals (e.g., different defense posture)
- Returns `Optional<CompilationResult>` — may produce a single replacement graph or a new
  lifecycle with revised phases
- `LifecycleManager.updateDesired()` installs the result and handles lifecycle interruption

Full cycle: fault → RAS situation → GoalCompiler replan → `CompilationResult` → lifecycle
manager installs.

### Two notice-and-react systems: complementary, not conflicting

- **Loop detects, policy decides:** drift noticed by the loop, FaultPolicy returns mutations
  for simple responses
- **RAS escalates, planner replans:** persistent patterns detected by RAS trigger full
  recompilation via SituationRecompiler. The planner sees the whole picture
- **No conflict:** the loop never does planning; the planner never does reconciliation

### Tests

1. `buildPhase_completesAndTransitionsToDefend` — full lifecycle through both phases
2. `defendPhase_reconcilesContinuously` — terminal phase handles drift via re-provisioning
3. `carryForward_defendPhaseReconcilesBuildArtifacts` — defense graph includes nexus node;
   nexus destruction during defense triggers re-provisioning via normal reconciliation
4. `faultTriggeredReplan_producesNewGraph` — SituationRecompiler produces revised graph
5. `singlePhaseBackwardCompat` — `CompilationResult.single()` works identically to old behavior

## Migration

### In-repo GoalCompiler implementations

All wrap return in `CompilationResult.single(graph)`:
- `PipelineGoalCompiler` (examples/pipeline)
- `DungeonGoalCompiler` (examples/dungeon)
- `AttackGoalCompiler`, `DefenseGoalCompiler`, `DistributionGoalCompiler` (examples/spatial)

### Cross-repo (casehub-ops)

Same mechanical migration. File tracking issues:
- `ComplianceGoalCompiler`
- `DeploymentGoalCompiler`
- `InfraGoalCompiler`
- `IoTGoalCompiler`

### Callers of GoalCompiler.compile()

Pattern-match on `SingleGraph` or `Lifecycle` explicitly:
- `SingleGraph`: use `graph()` directly, pass to `ReconciliationLoop.start()` or
  `LifecycleManager.start()`
- `Lifecycle`: use `LifecycleManager.start()` for lifecycle-aware orchestration

### Callers of ReconciliationLoop.start() / updateDesired()

Callers that currently use `ReconciliationLoop` directly should evaluate whether lifecycle
support is needed:

**In-repo:**
- `DesiredStateReplanDispatch` (engine-adapter) — migrates to `LifecycleManager.updateDesired()`
  to support lifecycle-aware replanning

**Cross-repo (casehub-ops):**
- `ApplicationLifecycleService` (casehub-ops app) — has deferred TODO comments referencing
  `reconciliationLoop.start()` and `reconciliationLoop.updateDesired()` (lines 61, 77). When
  uncommented, these should target `LifecycleManager`, not `ReconciliationLoop` directly.

### SituationRecompiler implementations

Return type changes from `Optional<DesiredStateGraph>` to `Optional<CompilationResult>`.
Mechanical: wrap return in `Optional.of(CompilationResult.single(graph))`.

**In-repo:**
- `NoOpSituationRecompiler` (runtime) — returns `Optional.empty()`, unchanged
- `ExpansionSituationRecompiler` (examples/expansion) — wraps in `CompilationResult`

**Cross-repo:** No known casehub-ops implementations (NoOp default displaces).

### Module dependencies for examples/expansion/

- `casehub-desiredstate-api` (compile)
- `casehub-desiredstate` runtime (test)
- `casehub-desiredstate-testing` (test)
- `casehub-ras-api` (compile — for `ActiveSituation` in `SituationRecompiler`)
