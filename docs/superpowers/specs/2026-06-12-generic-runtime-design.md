# Generic Desired-State Runtime — Design Spec

**Date:** 2026-06-12
**Epic:** #1 — Generic Runtime
**Status:** Design
**Repo:** casehubio/casehub-desiredstate

---

## 1. Overview

A domain-agnostic desired-state management runtime. Declares intent as a dependency graph, plans transitions (pruning before growing), executes via generated Serverless Workflows within cases, and continuously reconciles actual state against desired state via an event-driven loop with periodic re-sync.

Domains plug in via SPIs. The runtime knows about graphs, nodes, edges, planners, reconciliation loops, and fault policy primitives. It knows nothing about Kubernetes, IoT, agents, or infrastructure.

---

## 2. Module Structure

```
casehub-desiredstate-parent
├── api/                    casehub-desiredstate-api          io.casehub.desiredstate.api
├── runtime/                casehub-desiredstate              io.casehub.desiredstate.runtime
├── testing/                casehub-desiredstate-testing      io.casehub.desiredstate.testing
├── engine-adapter/         casehub-desiredstate-engine       io.casehub.desiredstate.engine
└── examples/
    └── dungeon/            casehub-desiredstate-example-dungeon  io.casehub.desiredstate.example.dungeon
```

### api/

Pure Java. SPIs and domain types. Dependencies: `casehub-platform-api`, Mutiny (provided), CDI annotations (provided). No `casehub-work-api` — WorkItem creation is an execution concern handled by engine-adapter/, not an SPI concern.

### runtime/

Quarkus library. `TransitionPlanner`, `ReconciliationLoop`, `FaultPolicyEngine`, default `DesiredStateGraph` implementation (`@DefaultBean`), `SimpleTransitionExecutor` (`@DefaultBean`). Dependencies: `casehub-desiredstate-api`, `casehub-platform-api`, `casehub-platform`, `quarkus-arc`, `quarkus-vertx`.

### testing/

Mock SPI implementations for consumers writing their own tests. `MockActualStateAdapter`, `MockNodeProvisioner`, `CannedEventSource`. Dependencies: `casehub-desiredstate-api`, `casehub-platform-testing`.

### engine-adapter/

Orchestration-tier bridge. `CaseTransitionExecutor` (`@ApplicationScoped`, displaces `SimpleTransitionExecutor @DefaultBean` by classpath presence). Translates `TransitionPlan` into a case with two `Worker(Workflow)` phases (prune, grow), executes via `CaseHubRuntime`. Dependencies: `casehub-desiredstate-api`, `casehub-engine-api`, `casehub-engine-common`, `casehub-engine-flow`, `casehub-work-api`.

### examples/dungeon/

"Nefarious Dungeons" — a self-contained example teaching all SPIs. Implements `GoalCompiler`, `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`, `EventSource` for a dungeon-management domain. Includes a 2D tile visualizer (SSE-driven, vanilla HTML/CSS/JS). Has its own tests proving the example works end-to-end. Dependencies: `casehub-desiredstate-api`, `casehub-desiredstate`.

---

## 3. Core Types (api/)

### Value Types

```java
interface NodeSpec {}  // marker — domains implement with typed records

record NodeId(String value) {}
record NodeType(String value) {}
record Dependency(NodeId from, NodeId to) {}  // "from" depends on "to"

record DesiredNode(
    NodeId id,
    NodeType type,
    NodeSpec spec,
    boolean requiresHuman
) {}
```

### DesiredStateGraph (SPI)

Interface — the backing data structure is pluggable. Default implementation in runtime/ uses immutable Java records + `Map.copyOf()` + dual adjacency maps (forward deps + reverse deps), inspired by Clojure's loom adjacency-map pattern.

```java
interface DesiredStateGraph {
    // Query
    Map<NodeId, DesiredNode> nodes();
    Set<Dependency> dependencies();
    Set<NodeId> dependenciesOf(NodeId node);
    Set<NodeId> dependentsOf(NodeId node);
    Set<NodeId> roots();
    Set<NodeId> leaves();
    int version();
    boolean isEmpty();

    // Mutation — returns new version
    DesiredStateGraph withNode(DesiredNode node);
    DesiredStateGraph withoutNode(NodeId id);
    DesiredStateGraph withDependency(Dependency dep);
    DesiredStateGraph withoutDependency(Dependency dep);
    DesiredStateGraph withMutation(GraphMutation mutation);

    // Alga-inspired composition
    DesiredStateGraph overlay(DesiredStateGraph other);   // union — shared NodeIds must have equal specs
    DesiredStateGraph connect(DesiredStateGraph other);   // all leaves of this → all roots of other
}
```

Immutability guarantees: every mutation method returns a new graph with an incremented version. Safe for concurrent reads during reconciliation. The default implementation copies the backing maps — at 10-200 nodes this takes microseconds. Alternative implementations (Bifurcan, Vavr, database-backed) can be provided as `@Alternative @Priority(1)` following the persistence-backend CDI priority pattern.

### DesiredStateGraphFactory (SPI)

```java
interface DesiredStateGraphFactory {
    DesiredStateGraph empty();
    DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps);
}
```

### Transition Types

```java
record OrderedStep(DesiredNode node, StepAction action) {}
enum StepAction { PROVISION, DEPROVISION }

record TransitionPlan(
    List<OrderedStep> removals,
    List<OrderedStep> additions,
    DesiredStateGraph before,
    DesiredStateGraph after
) {}

record TransitionResult(Map<NodeId, StepOutcome> outcomes) {}

sealed interface StepOutcome {
    record Succeeded() implements StepOutcome {}
    record Failed(String reason) implements StepOutcome {}
    record Skipped(String reason) implements StepOutcome {}
}
```

### Reconciliation Types

```java
record ActualState(Map<NodeId, NodeStatus> statuses) {}
enum NodeStatus { PRESENT, ABSENT, DEGRADED, UNKNOWN }

record ReconciliationResult(
    Set<NodeId> resolved,
    Set<NodeId> drifted,
    Set<NodeId> faulted,
    List<GraphMutation> mutations
) {}
```

### Graph Mutations

```java
sealed interface GraphMutation {
    record AddNode(DesiredNode node) implements GraphMutation {}
    record RemoveNode(NodeId id) implements GraphMutation {}
    record UpdateNode(NodeId id, NodeSpec newSpec) implements GraphMutation {}
    record AddDependency(Dependency dep) implements GraphMutation {}
    record RemoveDependency(Dependency dep) implements GraphMutation {}
}
```

### Fault Types

```java
record FaultEvent(NodeId node, FaultType type, String detail) {}

enum FaultType {
    NODE_DESTROYED,
    NODE_DEGRADED,
    PROVISION_FAILED,
    DEPROVISION_FAILED,
    HUMAN_NODE_TIMEOUT,
    DEPENDENCY_UNAVAILABLE
}

record StateEvent(NodeId node, NodeStatus newStatus, String detail) {}
```

### Provisioner Types

```java
record ProvisionContext(String tenancyId, DesiredStateGraph graph) {}
record DeprovisionContext(String tenancyId, DesiredStateGraph graph) {}

sealed interface ProvisionResult {
    record Success() implements ProvisionResult {}
    record Failed(String reason) implements ProvisionResult {}
}

sealed interface DeprovisionResult {
    record Success() implements DeprovisionResult {}
    record Failed(String reason) implements DeprovisionResult {}
}
```

---

## 4. SPIs (api/)

```java
interface GoalCompiler<G> {
    DesiredStateGraph compile(G goals, DesiredStateGraphFactory factory);
}

interface ActualStateAdapter {
    ActualState readActual(DesiredStateGraph desired);
}

interface NodeProvisioner {
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}

interface ReactiveNodeProvisioner {
    Uni<ProvisionResult> provision(DesiredNode node, ProvisionContext context);
    Uni<DeprovisionResult> deprovision(DesiredNode node, DeprovisionContext context);
}

interface FaultPolicy {
    List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current);
}

interface EventSource {
    Multi<StateEvent> stream();
}

interface TransitionExecutor {
    Uni<TransitionResult> execute(TransitionPlan plan);
}
```

### SPI Design Choices

- **`GoalCompiler<G>` takes `DesiredStateGraphFactory`** — decoupled from the graph backing store.
- **`NodeProvisioner` + `ReactiveNodeProvisioner`** — blocking and reactive parity. Both are SPIs in api/ — domain implementations provide one or both. The runtime detects which is available via CDI `Instance<>` lookup. No build-time gating needed — these are SPIs, not service beans. The `reactive-service-build-gating` pattern applies to extension service beans, not consumer-implemented SPIs.
- **`FaultPolicy` returns `List<GraphMutation>`** — all matching policies run (CDI `Instance<FaultPolicy>`, ordered by `@Priority`). Mutations are merged; conflicts (two policies mutating the same node differently) are flagged as `ConflictingMutationException`.
- **`TransitionExecutor` is the execution SPI** — `SimpleTransitionExecutor` (`@DefaultBean`) for testing/lightweight. `CaseTransitionExecutor` (`@ApplicationScoped` in engine-adapter/) for case-backed execution with durability, tracing, and visualization.
- **`Dependency(from, to)` means "from depends on to"** — "to" must be provisioned before "from". Roots-first for additions, leaves-first for removals.

---

## 5. Runtime Components (runtime/)

### Default DesiredStateGraph Implementation

```java
@DefaultBean
@ApplicationScoped
class DefaultDesiredStateGraphFactory implements DesiredStateGraphFactory { ... }
```

Returns `ImmutableDesiredStateGraph` — a record with:
- `Map<NodeId, DesiredNode> nodes` — `Map.copyOf()`
- `Map<NodeId, Set<NodeId>> forwardEdges` — node → its dependencies
- `Map<NodeId, Set<NodeId>> reverseEdges` — node → its dependents
- `int version` — incremented on each mutation

Cycle detection on every `withDependency()` call — throws `CyclicDependencyException(List<NodeId> cycle)`. Dangling dependency check — `withDependency()` throws `DanglingDependencyException` if either node is absent.

### TransitionPlanner

```java
@ApplicationScoped
class TransitionPlanner {
    TransitionPlan plan(DesiredStateGraph desired, ActualState actual);
}
```

1. Diff desired nodes against actual statuses — identify adds, removes, updates.
2. Updated nodes (present but spec changed) → removal + addition (deprovision old, provision new).
3. Removals topologically sorted leaves-first (dependents before dependencies).
4. Additions topologically sorted roots-first (dependencies before dependents).
5. Returns `TransitionPlan` carrying `before` and `after` graphs for diffing/rollback.

### ReconciliationLoop

```java
@ApplicationScoped
class ReconciliationLoop {
    void start(String tenancyId, DesiredStateGraph desired);
    void stop(String tenancyId);
    void updateDesired(String tenancyId, DesiredStateGraph newDesired);
}
```

Per-tenant instance. Two trigger paths:

**Event-driven:** subscribes to `EventSource.stream()`. Each event triggers diff → plan → execute. Debounced within a configurable window (default 1s, `casehub.desiredstate.debounce-window`).

**Periodic re-sync:** configurable interval (default 5m, `casehub.desiredstate.resync-interval`). Calls `ActualStateAdapter.readActual()` to catch silent drift. Uses Vert.x timers.

**State machine per cycle:**

```
IDLE → DIFFING → PLANNING → EXECUTING → RECONCILED
                                      → FAULTED → (FaultPolicy) → PLANNING
```

**Goal update:** `updateDesired()` swaps the desired graph. In-flight execution completes against the old version; the new version takes effect on the next cycle.

### FaultPolicyEngine

```java
@ApplicationScoped
class FaultPolicyEngine {
    List<GraphMutation> evaluate(FaultEvent event, DesiredStateGraph current);
}
```

Discovers all `FaultPolicy` beans via CDI `Instance<FaultPolicy>`, ordered by `@Priority`. Runs all matching policies. Merges mutations. Detects conflicts — two mutations targeting the same `NodeId` with different operations throw `ConflictingMutationException`.

### SimpleTransitionExecutor

```java
@DefaultBean
@ApplicationScoped
class SimpleTransitionExecutor implements TransitionExecutor {
    Uni<TransitionResult> execute(TransitionPlan plan);
}
```

Walks removals then additions in order, calling `NodeProvisioner` for each step. Human nodes (`requiresHuman == true`) are skipped. No durability, no visualization — testing/lightweight fallback.

---

## 6. Engine Adapter (engine-adapter/)

### CaseTransitionExecutor

```java
@ApplicationScoped
class CaseTransitionExecutor implements TransitionExecutor {
    Uni<TransitionResult> execute(TransitionPlan plan);
}
```

Displaces `SimpleTransitionExecutor @DefaultBean` by classpath presence.

**Execution flow:**

1. Generate a case definition with two sequential workers.
2. **Prune worker** — `Worker(Workflow)`. Generated Serverless Workflow definition from `plan.removals()`. Each step uses `call: casehub:dispatch` to invoke `NodeProvisioner.deprovision()` via `DesiredStateWorkerFunction`. Independent removals at the same topological level are parallel branches.
3. **Grow worker** — `Worker(Workflow)`. Generated Serverless Workflow from `plan.additions()`. Human nodes (`requiresHuman == true`) use `humanTask` binding — `NodeSpec` fields carry the human-readable instructions as WorkItem payload. Independent additions are parallel branches.
4. Start the case via `CaseHubRuntime`.
5. Observe `CaseLifecycleEvent` for completion.
6. Map case outcomes to `TransitionResult`.

**What the engine provides for free:**
- Case-level tracking and reporting
- Durability (crash recovery — case resumes where it left off)
- OTel tracing per workflow step
- Visualization (case plan with task statuses)
- Trust-weighted agent routing (if casehub-engine-ledger on classpath)
- Parallel execution of independent nodes within each phase

### DesiredStateWorkerFunction

Bridge worker that wraps `NodeProvisioner` as a casehub-engine worker. Each generated workflow step dispatches to this function with the `DesiredNode` as input. The function calls `provision()` or `deprovision()` and returns the result.

---

## 7. Error Model

| Error | When | Thrown by |
|---|---|---|
| `CyclicDependencyException(List<NodeId> cycle)` | Edge addition creates a cycle | `DesiredStateGraph.withDependency()` |
| `DanglingDependencyException(NodeId from, NodeId missingTo)` | Edge references absent node | `DesiredStateGraph.withDependency()` |
| `ConflictingMutationException(NodeId, GraphMutation a, GraphMutation b)` | Two fault policies return incompatible mutations for the same node | `FaultPolicyEngine.evaluate()` |
| `ProvisionResult.Failed(reason)` | Node provisioning fails | `NodeProvisioner.provision()` |
| `DeprovisionResult.Failed(reason)` | Node deprovisioning fails | `NodeProvisioner.deprovision()` |
| `FaultEvent(node, HUMAN_NODE_TIMEOUT)` | Human node WorkItem exceeds SLA | Engine adapter observes SLA breach |

Failed provisioner calls produce `FaultEvent`s fed back into `FaultPolicyEngine`. The domain's `FaultPolicy` decides how to respond — retry, replace, escalate.

---

## 8. Multi-Tenancy

Per-tenant `ReconciliationLoop` instances. Each tenant gets its own loop, its own `DesiredStateGraph`, its own event stream. A fault in one tenant's graph cannot affect another. `ProvisionContext` and `DeprovisionContext` carry `tenancyId`.

---

## 9. Example: Nefarious Dungeons

A self-contained example module teaching all SPIs via a dungeon-management domain.

### Domain Model

**Node types (rooms):**
- Dungeon Heart (root), Lair, Hatchery, Feeding Hall, Treasury
- Training Pit, Library, Workshop, Dark Temple, Torture Chamber, Prison, Crypt
- Tunnels (connecting infrastructure), Traps, Doors

**Creatures (nodes with room dependencies):**

| Creature | Required Room(s) |
|---|---|
| Skeleton | Crypt |
| Troll | Workshop |
| Dark Wizard | Library |
| Ogre | Feeding Hall |
| Shadow Assassin | Training Pit |
| Necromancer | Crypt + Library |
| Demon | Dark Temple |
| Wraith | Prison |

**Workers:** `GoblinProvisioner` — digs tunnels and builds rooms.

**Faults:** Hero raids (`HeroRaidFaultPolicy`), cave-ins, creature revolts, treasury looting.

**Human nodes:** "Recruit a dragon" (rare creature, manual negotiation), "Interrogate captured Paladin" (intel gathering), "Overseer approval to open new wing" (oversight gate).

### Simulated World

```java
class DungeonWorld {
    Map<NodeId, RoomState> rooms;        // BUILT, DESTROYED, DEGRADED
    Map<NodeId, CreatureState> creatures; // PRESENT, FLED, DEAD
    Map<NodeId, TrapState> traps;        // ARMED, TRIGGERED, DESTROYED
}
```

- `DungeonActualStateAdapter` reads from `DungeonWorld`
- `GoblinProvisioner` mutates `DungeonWorld`
- `DungeonEventSource` is a controllable event stream — tests push hero raids, cave-ins, revolts

### Visualizer

2D tile grid served by Quarkus. SSE endpoint streams `DungeonWorld` state changes to the browser. Vanilla HTML/CSS/JS — no framework. Tiles colored by room type, creature icons overlay, visual events for building/destruction/arrival. Lower priority than the dungeon logic and tests.

### Test Scenarios

- Build a dungeon (Heart → Tunnels → rooms → creatures) — validates roots-first grow ordering
- Hero raid destroys Library — validates fault policy, creature cascade (wizards/necromancers flee), rebuild
- Prune Torture Chamber — validates leaves-first removal (Wraiths leave first)
- Necromancer multi-dependency (Crypt + Library) — validates DAG ordering
- "Recruit a dragon" human node — validates WorkItem generation and completion
- Rapid hero raids — validates debounce
- Treasury looted → creature revolt cascade — validates multi-fault composition

**SPI contract tests:** If any SPI acquires default methods, add anonymous-implementation contract tests per `spi-default-method-contract-test` protocol — compiler error is the RED state.

---

## 10. Dependency Changes from Bootstrap

| Module | Remove | Add |
|---|---|---|
| api/ | `casehub-work-api` | — |
| runtime/ | `casehub-engine-flow` | — |
| engine-adapter/ (new) | — | `casehub-engine-api`, `casehub-engine-common`, `casehub-engine-flow`, `casehub-work-api` |
| examples/dungeon/ (new) | — | `casehub-desiredstate-api`, `casehub-desiredstate` |

---

## 11. Deferred — Captured as Issues

| Issue | Title | Scale | Complexity |
|---|---|---|---|
| #22 | Unified tracing across casehub-engine and quarkus-flow | S | Med |
| #23 | CBR integration for desired-state evolution | M | High |
| #24 | State-vector abstraction for continuous/spatial domains (QuarkMind) | L | High |
| #4 | Goal DSL format (domain concern, not core runtime) | M | High |
| #6 | Partial reconciliation — mid-flight goal updates (basic: next-cycle semantics for v1) | S | Med |
| #7 | Desired state versioning — Git-backed vs database | M | Med |
| #9 | Scale limits in goal declarations | S | Med |
| #25 | Desired-state as alternative case planning model for casehub-engine | L | High |
| parent#233 | Goal as first-class platform concept — unifying cases, desired-state, and tasks | XL | High |

---

## 12. Relationship to casehub-engine

Desired-state and casehub-engine are complementary, not competing. They differ fundamentally in execution model:

**Desired-state (DAG):** All nodes are live simultaneously. The reconciliation loop continuously maintains the entire graph as an invariant. A node that drifts is pulled back. There is no terminal state — the loop runs indefinitely.

**Cases (fishbone):** Stages progress left-to-right. Completed stages are behind you. The case terminates. It is an episodic trajectory toward completion.

Both orchestrate workers, but toward different ends:
- A case drives workers toward **completion** — a terminal state
- Desired-state drives workers toward **convergence** — a maintained invariant

**How they compose:** Each *reconciliation cycle* (a single diff → plan → execute pass) is episodic — it maps to a case with two Worker(Workflow) phases. The *reconciliation loop* that spawns those cycles is continuous — it is an ongoing process, not a case. The case model handles fishbone execution of a single transition plan. The desired-state loop handles continuous monitoring that decides when to spawn the next case.

The DAG's partial-order dependencies are expressed inside the generated Serverless Workflow definitions (parallel `fork` branches), not as case stages. The case is a lifecycle container; the workflow is the DAG execution engine.

**Future direction (#25, parent#233):** Desired-state could serve as an alternative case planning model — expressing goals rather than explicit stage sequences, with the planner deriving execution order from dependencies. The two models coexist: explicit stages for cases where linear sequence IS the intent; desired-state planning for cases where dependency-driven partial ordering is the natural structure. At the platform level, "Goal" (invariant vs achievement) may become a first-class concept unifying cases, desired-state, and tasks (parent#233).

---

## 13. PLATFORM.md Updates Required at Ship Time

- Add `casehub-desiredstate` to Build/Dependency Order (after `casehub-work`, before `casehub-engine`)
- Add cross-repo dependency rows: api/ → `casehub-platform-api`; engine-adapter/ → `casehub-engine-api`, `casehub-engine-common`, `casehub-engine-flow`, `casehub-work-api`
- Update Repository Map one-liner to note engine-adapter/ is orchestration-tier
- Add Capability Ownership rows for `DesiredStateGraph`, `TransitionPlanner`, `ReconciliationLoop`, `FaultPolicyEngine`

## 14. Platform Coherence

- **Tier:** Foundation (api/, runtime/, testing/). Engine-adapter/ is orchestration tier.
- **Module naming:** follows `maven-submodule-folder-naming` protocol — `api`, `runtime`, `testing`, `engine-adapter`
- **SPI pattern:** follows `module-tier-structure` — pure-Java SPIs in api/, CDI implementations in runtime/
- **CDI priority:** follows `persistence-backend-cdi-priority` — `@DefaultBean` in runtime/, `@ApplicationScoped` in engine-adapter/
- **Reactive parity:** `NodeProvisioner` + `ReactiveNodeProvisioner` as peer SPIs; runtime CDI lookup (not build-time gating — these are SPIs, not service beans)
- **No workarounds:** follows `no-workarounds-fix-the-design` — `casehub-engine-flow` dependency removed from runtime/ rather than working around the `CaseInstance` coupling
- **casehub-ops:** real domain implementations (deployment, infra, compliance, iot) live in casehubio/casehub-ops. This repo ships only the generic runtime and the Nefarious Dungeons example.

---

## 15. References

- Research doc: `docs/superpowers/research/2026-06-07-desired-state-management-research.md`
- PLATFORM.md: `casehub-parent/docs/PLATFORM.md` — capability ownership, boundary rules
- Protocols: `casehub/garden/docs/protocols/` — module-tier-structure, persistence-backend-cdi-priority, reactive-service-build-gating, no-workarounds-fix-the-design
- Clojure loom: adjacency-map-over-persistent-maps pattern (inspired dual adjacency map design)
- Algebraic graphs (Alga): overlay/connect composition (inspired builder API)
