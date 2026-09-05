# Decisions — #129 GraphView/reader pattern

## D1: Imperative/generic unification strategy

**Choice:** Function-closure approach — imperative domain coupling moves from the engine to the recorder via function closures. Single engine, single fixed-point loop, fully generic.

**Alternatives:**
- Composition (split engine) — generic engine handles parameterized/declarative, recorder orchestrates imperative separately. Trade-off: two evaluation paths, split fixed-point loop.
- Pre-evaluated contributions — engine accepts pre-evaluated imperative results. Trade-off: mixed abstraction, engine has domain-specific injection point.
- Change user contract — imperative methods take `GraphView<N>` instead of `DesiredStateGraph`. Trade-off: breaks existing user code, requires annotation contract change.

**Rationale:** The engine invokes imperative rules via `Method.invoke()` — already type-erased. The domain coupling (knowing what graph object to pass) is a resolution concern, not an evaluation concern. The recorder already resolves methods and creates `ResolvedRule` instances — wrapping the reflection call in a function closure moves the domain knowledge to where it belongs. `ImperativeRule<N>` carries `Function<MutableGraphView<N>, List<GraphMutation<N>>>` instead of raw `Method` + `instance`. The function closes over both plus the knowledge of how to extract `DesiredStateGraph` from the view. Result: engine has zero desiredstate imports, no split, no leaky abstraction on `GraphView`, no broken user code.

**Trade-offs:** `ResolvedRule` and `ResolvedInvariant` become parameterized (`<N>`). `ImperativeRule` changes from `(Method, Object)` to `Function` — same information, different packaging. Recorder has a downcast (`(DesiredStateGraphView) view`) inside the closure, safe because recorder co-produces view and rules.

**Depends on:** D2 (closure returns `List<GraphMutation<N>>`), D3 (closure uses `MutableGraphView<N>`)

**Sources:** `GraphRuleEngine.java:60-77` (current imperative dispatch), `DesiredStateGraphRecorder.java:184-216` (current rule resolution), migration context §5 (GraphView design rationale)

**Exploration:** deep-analysis

**Status:** captured

## D2: GraphMutation parameterization strategy

**Choice:** Parameterize `api.GraphMutation<N>` in place. No parallel generic type.

**Alternatives:**
- Create parallel `GraphMutation<N>` in annotations/runtime alongside existing `api.GraphMutation` with boundary converters. Trade-off: two 1:1 sealed hierarchies, converter maintenance, technical debt.

**Rationale:** Maintaining two parallel mutation types that are structurally identical is textbook tech debt. With all modules in a single repo and IntelliJ refactoring across the workspace, the ripple from parameterizing in place is mechanical — `GraphMutation` → `GraphMutation<DesiredNode>` at all usage sites, `NodeId` → `String` and `Dependency` → `(String, String)` in the generic variants. At extraction time (platform#267), the now-generic type moves to graph-core with zero cleanup.

**Trade-offs:** Ripple across ~80+ references in annotations/runtime/, runtime/, engine-adapter/, work-adapter/, examples/, testing/. The primary complexity center is annotations/runtime/ — `GraphRuleEngine` (17+ references across switch cases, conflict detection, mutation application), `ResolvedRule.DeclarativeRule` (function type signature), `DesiredStateGraphRecorder` (rule/invariant resolution). Other modules are mechanical substitution. Edge type: `AddDependency<N>` and `RemoveDependency<N>` carry `(String from, String to)` directly — the `Dependency` record stays in `api/` as a domain type using `NodeId`. No new wrapper record needed.

**Sources:** `api/GraphMutation.java` (current sealed interface), issue #129 item 7, user direction ("this sounds like technical debt")

**Exploration:** quick

**Status:** revised — R1-05 (added annotations/runtime as primary complexity center), R1-06 (explicit edge type specification)

## D3: GraphView interface design — reader/writer/view stack

**Choice:** Full reader/writer/view stack. `GraphView<N>` wraps graph + reader. `MutableGraphView<N>` wraps graph + reader + writer. `GraphReader<G, N>` and `GraphWriter<G, N>` are adapter interfaces. `DesiredStateGraphAdapter` implements both for `DesiredStateGraph`/`DesiredNode`.

**Alternatives:**
- View IS the adapter (skip reader/writer) — `DesiredStateGraphView` directly implements view methods. Trade-off: can't reuse the view construction pattern for other graph types without refactoring; fine for one graph type but graph-core targets multiple.
- Defer reader/writer to extraction — build only `GraphView<N>` and `MutableGraphView<N>` now, add `GraphReader<G, N>` and `GraphWriter<G, N>` at graph-core extraction (platform#267). Trade-off: View must contain all adaptation logic inline; extraction becomes a design step rather than a mechanical package move; rework when reader/writer is eventually introduced.

**Rationale:** The migration strategy (§5 of yaml-core migration context) is deliberately front-loaded: issue #129 introduces all interfaces and generifies engines in place; platform#267 extracts to graph-core as a mechanical package move. Deferring reader/writer to extraction makes that step a design step — contradicting the strategy. The reader/writer pattern follows the `ForEachAdapter<E>` precedent in yaml-core: each graph type plugs in by providing its own adapter with zero coupling from graph-core to any domain type. The migration context identifies two consumer patterns (separated generic graph vs. unified domain node) — the reader abstracts over both. Building the adapter surface now means graph-core arrives ready for use.

**Trade-offs:** Four interfaces instead of two. One consumer today (DesiredStateGraphAdapter), more at extraction time. The extra interfaces are small and stable.

**Depends on:** D1 (function-closure approach uses MutableGraphView from this decision)

**Sources:** Migration context §5 (reader/adapter pattern, two consumer patterns), issue #129 items 1-3, `ForEachAdapter<E>` precedent in yaml-core, platform#267 (graph-core extraction — concrete second consumer context)

**Exploration:** quick

**Status:** revised — R1-07 (rationale: removed overstated eidos claim, cited platform#267), R1-08 (added deferred alternative, rejected)

## D4: Exception type generification — consistent string IDs, no special cases

**Choice:** Every exception type the engines touch gets generified to string IDs. No domain types in engine exceptions. Writer adapter translates domain exceptions at the boundary.

Specific changes:

| Type | Action |
|---|---|
| `ConflictingMutationException(NodeId, GraphMutation, GraphMutation)` | → `(String, GraphMutation<?>, GraphMutation<?>)`. Move from `api/` to graph-core — thrown by `GraphRuleEngine` (annotations/runtime/) and `FaultPolicyEngine` (runtime/), both of which move to graph-core at extraction. |
| `CyclicDependencyException(List<NodeId>)` | Stays in `api/` for `ImmutableDesiredStateGraph`. New `GraphCycleException(List<String>)` in engine package. Writer adapter catches domain exception, wraps it. |
| `GraphRuleCycleException(List<String>, List<NodeId>)` | → `(List<String>, List<String>)` — cyclePath becomes `List<String>`. |
| `GraphRuleNonConvergenceException(List<ResolvedRule>)` | → `List<ResolvedRule<?>>` or extract names before construction. |
| `GraphViolationException(String, NodeId...)` | → `(String, String...)` — affected node IDs as strings. |
| `GraphViolation(String, String, String, List<NodeId>)` | → `List<String>` for affected nodes. |
| `GraphInvariantViolationsException(List<GraphViolation>)` | No change — generic after `GraphViolation` update. |

**Alternatives:**
- Leave `CyclicDependencyException` as-is, engine catches by superclass or direct api import. Trade-off: inconsistent — one exception stays domain-typed while all others are generified. Avoids work but creates an irregularity.

**Rationale:** The principle is uniform: everything the engine uses has string IDs and generic mutation types. `CyclicDependencyException` crosses the adapter boundary (thrown by domain graph, caught by engine) — the writer adapter translates, which is exactly what adapters do. `ConflictingMutationException` is currently in `api/` and thrown by two engine classes across two modules (`GraphRuleEngine` in annotations/runtime/ and `FaultPolicyEngine` in runtime/). Both engines move to graph-core at extraction — the exception moves with them.

**Trade-offs:** One new exception class (`GraphCycleException`). `GraphViolationException` is a user-contract change: domain implementors writing `@GraphInvariant` methods that throw this exception will need to change `NodeId` args to `String` (migration: `nodeA.id()` → `nodeA.id().value()`). This is mechanical but affects consumer code, not just internal runtime.

**Depends on:** D2 (GraphMutation parameterization), D3 (writer adapter translates CyclicDependencyException)

**Sources:** `CyclicDependencyException.java` (api/), `ConflictingMutationException.java` (api/), `GraphRuleCycleException.java`, `GraphViolationException.java`, `GraphViolation.java` (all annotations/runtime/)

**Exploration:** deep-analysis

**Status:** revised — R1-02 (fixed ConflictingMutationException location), R1-10 (explicit user-contract change for GraphViolationException)

## D5: GraphView API surface for pattern matching and engine operations

**Choice:** `GraphView<N>` exposes node lookup, type/ID extraction, and dependency traversal — the minimum surface required by PatternEvaluator, PatternMatchingSupport, GraphRuleEngine, and GraphInvariantEngine.

Core methods:
- `Map<String, N> nodes()` — all nodes keyed by string ID
- `N node(String id)` — lookup by ID
- `String nodeId(N node)` — extract string ID from node
- `String nodeType(N node)` — extract type string from node
- `Set<String> dependenciesOf(String nodeId)` — outgoing edge targets
- `Set<String> dependentsOf(String nodeId)` — incoming edge sources

`MutableGraphView<N>` extends `GraphView<N>` with:
- `MutableGraphView<N> withMutation(GraphMutation<N> mutation)` — apply mutation, return new view

**Alternatives:**
- Minimal view (only what D1 closures need) — expose only `withMutation` and let pattern matching stay domain-specific. Trade-off: pattern matching and invariant engines can't generify, defeating the purpose of graph-core extraction.
- Rich view (add pattern query methods) — expose `nodesByType(String)`, `hasNode(String)`, `edgeCount()`. Trade-off: larger interface surface, speculative convenience methods without a second consumer to validate them.

**Rationale:** Derived from what PatternEvaluator and PatternMatchingSupport actually call on `DesiredStateGraph` today. `PatternEvaluator.evaluate()` iterates nodes by type via `graph.nodes().values()` with `n.type().equals(NodeType.of(s))`. `PatternMatchingSupport.findDirectNeighbors()`/`findReachable()` traverse via `graph.dependenciesOf()`/`graph.dependentsOf()`. `existsGlobal()`/`existsRelational()` check node existence by type. These operations map directly to the six core methods. After generification: `NodeType.of(s).equals(n.type())` becomes `view.nodeType(n).equals(s)` — simpler. `graph.nodes().get(id)` becomes `view.node(idString)` — same structure.

**Depends on:** D3 (GraphView is part of the reader/writer/view stack)

**Sources:** `PatternEvaluator.java` (6 DesiredStateGraph/DesiredNode interactions), `PatternMatchingSupport.java` (8 methods using graph.nodes(), dependenciesOf, dependentsOf), `GraphRuleEngine.java` (withMutation, filterNoOps), `GraphInvariantEngine.java` (nodes iteration, type comparison)

**Exploration:** quick (surfaced by review)

**Status:** captured

## D6: NodeSpec type resolution strategy for JPA serialization

**Choice:** Store the fully-qualified class name (FQCN) alongside each node's serialized spec in the JSON envelope. On deserialization, `Class.forName(fqcn)` resolves the concrete class; Jackson `treeToValue()` reconstructs the instance.

**Alternatives:**
- NodeTypeId registry in api/ — define a `NodeSpecTypeMap` SPI, wire via yaml/deployment and annotations/deployment build extensions. Trade-off: cleaner semantically (uses `@NodeTypeId` as discriminator) but requires cross-module changes across 3+ modules for an S-sized issue.
- NodeSpecFactoryProvider — implement the existing (but unwired) `NodeSpecFactoryProvider` SPI. Trade-off: factory takes `Map<String, Object>` — designed for YAML input, not Jackson round-tripping; would need adaptation.

**Rationale:** Self-contained in `persistence-jpa/` with zero cross-module impact. FQCN is fragile under class renames, but this is pre-release — fix-forward is fine. The FQCN is already the natural identifier Jackson uses for polymorphic types. No new SPIs, no deployment module, no recorder.

**Trade-offs:** Class renames break deserialization of stored graphs. Mitigated by pre-release status and the planner's graceful fallback to `UnknownSpec` when `previousDesired` is null.

**Sources:** `NodeSpecRegistry.java` (yaml/runtime — existing type→class mapping), `NodeSpecFactoryProvider.java` (api/ — unwired SPI), `JpaFaultCountStore.java` (established pattern)

**Exploration:** quick

**Status:** captured

## D7: Table schema — single row per tenant

**Choice:** One row per `tenancy_id` with the entire `DesiredStateGraph` serialized as a `TEXT` column. Primary key is `tenancy_id`. Additional `updated_at TIMESTAMP` column for diagnostics.

**Alternatives:**
- Per-node rows (tenancy_id + node_id composite key, each node serialized individually) — Trade-off: more granular, allows partial reads/updates, but the SPI contract is whole-graph store/load/remove. Partial operations would be unused complexity. More rows, more JOINs, more entities.

**Rationale:** The SPI operates on whole graphs per tenant — `store()` replaces the entire graph, `load()` returns the entire graph, `remove()` deletes it. A single-row schema maps 1:1 to this contract. The `TransitionPlanner` always needs the full previous graph for orphan detection — there's no partial-load path.

**Trade-offs:** Large graphs produce large TEXT values. For typical desired-state graphs (tens to hundreds of nodes), this is well within database TEXT limits. Not a concern at current scale.

**Sources:** `ReconciliationStateStore.java` (api/ — SPI contract), `ReconciliationLoop.java:750-752` (usage in plan()), `FaultCountEntity.java` (precedent pattern)

**Exploration:** quick

**Status:** captured

## D8: Serialization format — Jackson JSON envelope with FQCN discriminator

**Choice:** Jackson `ObjectMapper` serializes the graph into a JSON envelope: `{nodes: [...], dependencies: [...]}`. Each node carries `{id, specClass, spec, humanGating, hooks}`. `specClass` is the FQCN from D6. `LifecycleStep` subtypes (sealed: `Verify`, `Notify`, `Wait`) use the same FQCN approach in the hooks field. On `ClassNotFoundException` during deserialization, log a warning and return `Optional.empty()` — the planner already handles null gracefully via `UnknownSpec` fallback.

**Alternatives:**
- Java serialization (JDK `ObjectOutputStream`) — Trade-off: handles polymorphism automatically but fragile under class evolution, not inspectable, generally frowned upon.
- Custom binary format — Trade-off: compact but unreadable, unmaintainable, no ecosystem tooling.

**Rationale:** JSON is inspectable (debuggable via SQL queries), portable, and Jackson is already on the Quarkus classpath. The envelope structure mirrors the `DesiredStateGraph` contract (nodes + dependencies). Reconstruction uses `DesiredStateGraphFactory.of(nodes, deps)` — injected via CDI. `api/` stays free of Jackson annotations (no `@JsonTypeInfo` on `NodeSpec` or `LifecycleStep`).

**Trade-offs:** Jackson `jackson-databind` added as a compile dependency to `persistence-jpa/`. The serializer is internal (package-private `GraphSerializer`) — no public API surface.

**Depends on:** D6 (FQCN determines the discriminator field in the envelope)

**Sources:** `DesiredStateGraphFactory.java` (api/ — reconstruction entry point), `DefaultDesiredStateGraphFactory.java` (runtime/ — CDI bean), `DesiredNode.java`, `HookDescriptor.java`, `LifecycleStep.java` (serialization surface)

**Exploration:** quick

**Status:** captured
