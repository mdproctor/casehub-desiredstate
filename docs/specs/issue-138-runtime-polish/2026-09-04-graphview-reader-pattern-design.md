# GraphView/Reader Pattern — Design Spec

**Date:** 2026-09-04
**Issue:** #129 (GraphView reader pattern in annotations/runtime), child of #138 (runtime polish epic)
**Branch:** issue-138-runtime-polish
**Status:** Design — ready for implementation planning

## Purpose

Decouple the graph pattern-matching engines (`GraphRuleEngine`, `GraphInvariantEngine`,
`PatternEvaluator`, `PatternMatchingSupport`) from desiredstate-specific types (`DesiredNode`,
`DesiredStateGraph`, `NodeId`, `NodeType`, `Dependency`). After this refactor, the engines
have zero imports of domain-specific types from `io.casehub.desiredstate.api.*`. Remaining
api imports (`GraphMutation`, `ConflictingMutationException`) are generic types that move
to graph-core at extraction (step 2, platform#267), making the engines mechanically
extractable to a shared `graph-core` module.

This is step 1 of the migration path documented in the yaml-core migration context §5:
refactor in place, then extract.

## Architecture

### View/Reader/Writer stack (D3)

Four new interfaces, all in `annotations/runtime` (future graph-core package):

```
GraphReader<G, N>              GraphWriter<G, N>
       │                              │
       └──────── GraphView<N> ────────┘
                      │
              MutableGraphView<N>
```

**`GraphReader<G, N>`** — extracts generic information from a domain graph `G` with nodes
of type `N`:

```java
interface GraphReader<G, N> {
    Map<String, N> nodes(G graph);
    N node(G graph, String id);
    String nodeId(N node);
    String nodeType(N node);
    Set<String> dependenciesOf(G graph, String nodeId);
    Set<String> dependentsOf(G graph, String nodeId);
}
```

**`GraphWriter<G, N>`** — applies generic mutations back to a domain graph `G`:

```java
interface GraphWriter<G, N> {
    G applyMutation(G graph, GraphMutation<N> mutation) throws GraphCycleException;
}
```

The writer contract: if a mutation would create a cycle, throw `GraphCycleException`.
Domain implementations catch their own cycle exceptions and wrap them.

**`GraphView<N>`** — read-only view wrapping graph + reader at the boundary (D5):

```java
interface GraphView<N> {
    Map<String, N> nodes();
    N node(String id);
    String nodeId(N node);
    String nodeType(N node);
    Set<String> dependenciesOf(String nodeId);
    Set<String> dependentsOf(String nodeId);
}
```

**`MutableGraphView<N>`** — extends `GraphView<N>` with mutation:

```java
interface MutableGraphView<N> extends GraphView<N> {
    MutableGraphView<N> withMutation(GraphMutation<N> mutation);
}
```

Each mutation returns a new view wrapping the updated graph. Immutability preserved.

### DesiredStateGraphAdapter (D3)

Implements `GraphReader<DesiredStateGraph, DesiredNode>` and
`GraphWriter<DesiredStateGraph, DesiredNode>`. Lives in `annotations/runtime` — the
adapter stays in desiredstate, only the interfaces move to graph-core at extraction.

```java
class DesiredStateGraphAdapter
        implements GraphReader<DesiredStateGraph, DesiredNode>,
                   GraphWriter<DesiredStateGraph, DesiredNode> {

    @Override
    public String nodeId(DesiredNode node) { return node.id().value(); }

    @Override
    public String nodeType(DesiredNode node) { return node.type().value(); }

    @Override
    public Map<String, DesiredNode> nodes(DesiredStateGraph graph) {
        // Convert Map<NodeId, DesiredNode> to Map<String, DesiredNode>
    }

    @Override
    public Set<String> dependenciesOf(DesiredStateGraph graph, String nodeId) {
        return graph.dependenciesOf(NodeId.of(nodeId)).stream()
                    .map(NodeId::value).collect(toSet());
    }

    @Override
    public DesiredStateGraph applyMutation(DesiredStateGraph graph,
                                            GraphMutation<DesiredNode> mutation) {
        try {
            return graph.withMutation(mutation);
        } catch (CyclicDependencyException e) {
            throw new GraphCycleException(
                e.getCycle().stream().map(NodeId::value).toList());
        }
    }
}
```

**`DesiredStateGraphView`** — concrete `MutableGraphView<DesiredNode>` wrapping the adapter +
graph. Lives in `annotations/runtime` alongside the adapter:

```java
class DesiredStateGraphView implements MutableGraphView<DesiredNode> {
    private final DesiredStateGraph graph;
    private final DesiredStateGraphAdapter adapter;

    DesiredStateGraphView(DesiredStateGraph graph, DesiredStateGraphAdapter adapter) {
        this.graph = graph;
        this.adapter = adapter;
    }

    DesiredStateGraph graph() { return graph; }

    @Override public Map<String, DesiredNode> nodes() { return adapter.nodes(graph); }
    @Override public DesiredNode node(String id) { return adapter.node(graph, id); }
    @Override public String nodeId(DesiredNode node) { return adapter.nodeId(node); }
    @Override public String nodeType(DesiredNode node) { return adapter.nodeType(node); }
    @Override public Set<String> dependenciesOf(String nodeId) {
        return adapter.dependenciesOf(graph, nodeId);
    }
    @Override public Set<String> dependentsOf(String nodeId) {
        return adapter.dependentsOf(graph, nodeId);
    }

    @Override
    public MutableGraphView<DesiredNode> withMutation(GraphMutation<DesiredNode> mutation) {
        return new DesiredStateGraphView(adapter.applyMutation(graph, mutation), adapter);
    }
}
```

**Recorder interaction** — `DesiredStateGraphRecorder.applyGraphRulesToResult` creates the
view, passes it to the engine, and extracts the domain graph from the returned view:

```java
DesiredStateGraphAdapter adapter = new DesiredStateGraphAdapter();
DesiredStateGraphView view = new DesiredStateGraphView(sg.graph(), adapter);
MutableGraphView<DesiredNode> evaluated = engine.evaluate(view, rules);
DesiredStateGraph result = ((DesiredStateGraphView) evaluated).graph();
```

The downcast is safe — `withMutation()` on `DesiredStateGraphView` returns a new
`DesiredStateGraphView`, so the runtime type is preserved through the engine's entire
fixed-point loop. Same safety argument as for imperative rule closures: the recorder
co-produces the view and the rules.

### GraphMutation<N> parameterization (D2)

`api.GraphMutation` is parameterized in place — no parallel type:

```java
public sealed interface GraphMutation<N> {
    default String targetNodeId() { ... }

    record AddNode<N>(N node) implements GraphMutation<N> {}
    record RemoveNode<N>(String id) implements GraphMutation<N> {}
    record UpdateNode<N>(String id, N adaptedNode) implements GraphMutation<N> {}
    record AddEdge<N>(String from, String to) implements GraphMutation<N> {}
    record RemoveEdge<N>(String from, String to) implements GraphMutation<N> {}
}
```

Changes from current:
- `AddNode(DesiredNode)` → `AddNode<N>(N node)`
- `RemoveNode(NodeId)` → `RemoveNode<N>(String id)`
- `UpdateNode(NodeId, DesiredNode)` → `UpdateNode<N>(String id, N adaptedNode)`
- `AddDependency(Dependency)` → `AddEdge<N>(String from, String to)`
- `RemoveDependency(Dependency)` → `RemoveEdge<N>(String from, String to)`

`Dependency` stays in `api/` as a domain type. The adapter converts between
`AddEdge(from, to)` and `Dependency(NodeId.of(from), NodeId.of(to))`.

`targetNodeId()` returns `String` (was `NodeId`). For edge mutations it returns `null`
(unchanged).

All existing consumers become `GraphMutation<DesiredNode>`. IntelliJ change-signature
handles the propagation.

### Function-closure unification (D1)

Imperative rules close over the domain dispatch at wiring time. The engine is fully
generic — single fixed-point loop, zero split.

**`ResolvedRule<N>`** — parameterized sealed interface:

```java
sealed interface ResolvedRule<N> {
    String name();

    record ImperativeRule<N>(String name,
        Function<MutableGraphView<N>, List<GraphMutation<N>>> evaluator)
        implements ResolvedRule<N> {}

    record ParameterizedRule<N>(String name, Method method, Object instance,
        List<PatternParameterDescriptor> patterns)
        implements ResolvedRule<N> {
        String[] bindingNames() { return PatternMatchingSupport.getParameterNames(method); }
    }

    record DeclarativeRule<N>(String name, List<PatternParameterDescriptor> patterns,
        String[] bindingNames,
        Function<Map<String, N>, List<GraphMutation<N>>> actionEvaluator)
        implements ResolvedRule<N> {}
}
```

**`ResolvedInvariant<N>`** — same pattern:

```java
sealed interface ResolvedInvariant<N> {
    String name();

    record ImperativeInvariant<N>(String name,
        Consumer<GraphView<N>> validator) implements ResolvedInvariant<N> {}

    record ParameterizedReflectiveInvariant<N>(String name, Method method, Object instance,
        List<PatternParameterDescriptor> patterns) implements ResolvedInvariant<N> {
        String[] bindingNames() { return PatternMatchingSupport.getParameterNames(method); }
    }

    record DeclarativeInvariant<N>(String name, List<PatternParameterDescriptor> patterns,
        String[] bindingNames, String messageTemplate) implements ResolvedInvariant<N> {}
}
```

**Recorder creates closures** — `DesiredStateGraphRecorder` wraps imperative dispatch:

```java
// Rule closure
Function<MutableGraphView<DesiredNode>, List<GraphMutation<DesiredNode>>> evaluator = view -> {
    DesiredStateGraph graph = ((DesiredStateGraphView) view).graph();
    return (List<GraphMutation<DesiredNode>>) method.invoke(instance, graph);
};

// Invariant closure
Consumer<GraphView<DesiredNode>> validator = view -> {
    DesiredStateGraph graph = ((DesiredStateGraphView) view).graph();
    method.invoke(instance, graph);
};
```

The downcast is safe — the recorder co-produces the view and the rules.

### Engine generification

**`GraphRuleEngine`** — public API changes:

```java
// Before
public DesiredStateGraph evaluate(DesiredStateGraph graph, List<ResolvedRule> rules)

// After
public <N> MutableGraphView<N> evaluate(MutableGraphView<N> view, List<ResolvedRule<N>> rules)
```

Internal methods change `DesiredStateGraph` → `MutableGraphView<N>`, `DesiredNode` → `N`,
`NodeId` → `String`. The fixed-point loop, deduplication, and mutation application are
structurally identical — only the types change. Two methods require API pattern changes
beyond type substitution:

**`detectEdgeConflicts`** — currently keys on `Dependency` (domain record). After
generification, `AddEdge<N>` carries `(String from, String to)` — two scalars, not a
key-ready record. Uses `Map.entry(from, to)` as composite key (structural equals since
Java 9):
```java
Map<Map.Entry<String,String>, GraphMutation<N>> addEdges = new HashMap<>();
case GraphMutation.AddEdge<N> add -> addEdges.put(Map.entry(add.from(), add.to()), m);
```

**`filterNoOps`** — edge no-op checks currently use `graph.dependencies()` (global edge
set, not on `GraphView<N>`). After generification, uses per-node lookup via
`view.dependenciesOf()`:
```java
case GraphMutation.AddEdge<N> add -> !view.dependenciesOf(add.from()).contains(add.to());
case GraphMutation.RemoveEdge<N> rem -> view.dependenciesOf(rem.from()).contains(rem.to());
```

Semantically equivalent (specific edge existence check), different API call pattern.
The `GraphView<N>` API is sufficient — no need to add a global `edges()` method (D5
deliberately minimal).

Imperative dispatch:
```java
case ImperativeRule<N> imp -> imp.evaluator().apply(view);
```

Parameterized dispatch:
```java
List<Map<String, N>> allBindings = PatternEvaluator.evaluate(view, patterns, paramNames);
```

**`GraphInvariantEngine`** — same pattern:

```java
// Before
public void validate(DesiredStateGraph graph, List<ResolvedInvariant> invariants)

// After
public <N> void validate(GraphView<N> view, List<ResolvedInvariant<N>> invariants)
```

Read-only — takes `GraphView<N>`, not `MutableGraphView<N>`.

Internal methods that access node properties (`resolveMatchTemplate`, `buildExpectedAnchors`,
`countMatchingNodes`, `checkExpansionCardinality`) thread `GraphView<N>` from `validate()`
through to the call site. For example, `resolveMatchTemplate` changes from
`node.id().value()` / `node.type().value()` to `view.nodeId(node)` / `view.nodeType(node)` —
same pattern as `PatternEvaluator`'s type matching.

**`PatternEvaluator`** — static methods become generic:

```java
// Before
public static List<Map<String, DesiredNode>> evaluate(
    DesiredStateGraph graph, List<PatternParameterDescriptor> patterns, String[] bindingNames)

// After
public static <N> List<Map<String, N>> evaluate(
    GraphView<N> view, List<PatternParameterDescriptor> patterns, String[] bindingNames)
```

Type matching changes from `NodeType.of(s).equals(n.type())` to
`view.nodeType(n).equals(s)` — simpler, direct string comparison.

**`PatternMatchingSupport`** — static methods become generic:

```java
// Before
public static DesiredNode resolveReference(PatternParameterDescriptor p, int paramIndex,
    String[] paramNames, Map<String, DesiredNode> bindings)

// After
public static <N> N resolveReference(PatternParameterDescriptor p, int paramIndex,
    String[] paramNames, Map<String, N> bindings)
```

Graph traversal methods (`findDirectNeighbors`, `findReachable`, `existsGlobal`,
`existsRelational`) change from `(DesiredStateGraph, DesiredNode, ...)` to
`(GraphView<N>, N, ...)`.

`crossProduct` becomes `crossProduct(List<List<N>>)` — already structurally generic,
just needs the type parameter.

### Exception generification (D4)

Every exception type the engines touch uses string IDs. No special cases.

| Type | Change |
|---|---|
| `ConflictingMutationException` | `(String, GraphMutation<?>, GraphMutation<?>)`. Stays in `api/` during step 1 (both `GraphRuleEngine` in annotations/runtime/ and `FaultPolicyEngine` in runtime/ import from `api/`). Moves to graph-core at extraction (step 2, platform#267). `FaultPolicyEngine` stays in desiredstate — imports from graph-core after extraction. |
| `GraphCycleException` | **New.** `(List<String>)` in engine package. Writer adapter wraps `CyclicDependencyException`. |
| `CyclicDependencyException` | Stays in `api/` unchanged. Domain graph throws it; writer adapter catches and wraps. |
| `GraphRuleCycleException` | `cyclePath: List<String>` (was `List<NodeId>`). |
| `GraphRuleNonConvergenceException` | Takes `List<ResolvedRule<?>>` (parameterized). |
| `GraphViolationException` | `(String message, String... affectedNodeIds)` (was `NodeId...`). |
| `GraphViolation` | `affectedNodes: List<String>` (was `List<NodeId>`). |
| `GraphInvariantViolationsException` | No change — generic after `GraphViolation` update. |

## What does NOT change

- `@GraphRule`, `@GraphInvariant` annotations — unchanged
- Quarkus deployment processors — unchanged (they produce descriptors, not engine types)
- `DesiredNode`, `NodeSpec` — domain types untouched
- `Dependency`, `NodeId`, `NodeType` — domain value types untouched
- YAML and annotation user-facing APIs — unchanged
- Runtime modules (`ReconciliationLoop`, `TransitionPlanner`) — use
  `GraphMutation<DesiredNode>` (mechanical type parameter addition), no algorithm changes

## Consumer impact

### Annotation users

**`@GraphRule` imperative methods** — no change. Method signature stays
`List<GraphMutation> myRule(DesiredStateGraph graph)` (raw `GraphMutation`, as users
write today). The raw type continues to work because type parameters are erased at
runtime; reflection invocation is transparent.

**`@GraphInvariant` methods** that throw `GraphViolationException` — change `NodeId` args
to `String`: `throw new GraphViolationException("msg", node.id().value())` instead of
`throw new GraphViolationException("msg", node.id())`.

### Domain graph types (api/ and runtime/)

**`DesiredStateGraph`** — interface method signature change:
- `withMutation(GraphMutation)` → `withMutation(GraphMutation<DesiredNode>)` (raw → parameterized)
- All other methods unchanged

**`ImmutableDesiredStateGraph.withMutation()`** — switch cases change:
- `case GraphMutation.AddDependency m -> withDependency(m.dependency())` →
  `case GraphMutation.AddEdge m -> withDependency(new Dependency(NodeId.of(m.from()), NodeId.of(m.to())))`
- `case GraphMutation.RemoveDependency m -> withoutDependency(m.dependency())` →
  `case GraphMutation.RemoveEdge m -> withoutDependency(new Dependency(NodeId.of(m.from()), NodeId.of(m.to())))`
- `case GraphMutation.RemoveNode m -> withoutNode(m.id())` →
  `case GraphMutation.RemoveNode m -> withoutNode(NodeId.of(m.id()))` (`m.id()`: `NodeId` → `String`)
- `case GraphMutation.UpdateNode m -> { nodes.containsKey(m.id()); m.id().value() }` →
  `{ nodes.containsKey(NodeId.of(m.id())); m.id() }` (`m.id()`: `NodeId` → `String`, `.value()` removed)
- `AddNode` — type parameter addition only (`m.node()` stays `DesiredNode`)

**`GraphMutations`** (api/) — signature and construction changes:
- Return type: `List<GraphMutation>` → `List<GraphMutation<DesiredNode>>`
- `new GraphMutation.AddDependency(new Dependency(node.id(), dependsOn))` →
  `new GraphMutation.AddEdge<>(node.id().value(), dependsOn.value())`
- Callers: `FaultPolicy.addReviewNode()`, `YamlFaultPolicyBuilder`, 4 example modules, tests

### Runtime consumers (runtime/)

**`GraphDiff`** — constructs all mutation variants:
- `new GraphMutation.RemoveNode(id)` → `new GraphMutation.RemoveNode<>(id.value())` (NodeId → String)
- `new GraphMutation.AddDependency(dep)` → `new GraphMutation.AddEdge<>(dep.from().value(), dep.to().value())`
- `new GraphMutation.RemoveDependency(dep)` → `new GraphMutation.RemoveEdge<>(dep.from().value(), dep.to().value())`
- `targetNodeId()` return type: `NodeId` → `String` (unchanged delegation)

**`FaultPolicyEngine`** — non-trivial but mechanical changes, no algorithm changes:
- `Map<NodeId, List<GraphMutation>> byNode` → `Map<String, List<GraphMutation<DesiredNode>>>`
  (key type change ripples through conflict detection loop)
- `ConflictingMutationException(nodeId, first, second)` — `nodeId`: `NodeId` → `String`
- `FaultPolicy.onFault()` return type: `List<GraphMutation>` → `List<GraphMutation<DesiredNode>>`
- `getTargetNodeId()` return type: `NodeId` → `String`

### YAML surface consumers (yaml/runtime/)

**`YamlRuleConverter.evaluateActions()`** — constructs all mutation variants. Actually
simplifies: the YAML surface already works with strings internally and currently wraps
them in `NodeId`/`Dependency` at the boundary:
- `new GraphMutation.RemoveNode(NodeId.of(id))` → `new GraphMutation.RemoveNode<>(id)` (simpler)
- `new GraphMutation.AddDependency(new Dependency(NodeId.of(from), NodeId.of(to)))` →
  `new GraphMutation.AddEdge<>(from, to)` (simpler)
- Same pattern for `UpdateNode`, `RemoveDependency`

**`YamlFaultPolicyBuilder`** — uses `GraphMutations.addNodeDependingOn()`, inherits its changes

### SPI implementors

**`FaultPolicy`** (api/) — return type change on public SPI interface:
- `List<GraphMutation> onFault(...)` → `List<GraphMutation<DesiredNode>> onFault(...)`
- Implementors: `ThresholdFaultPolicy` (api/), `FaultPolicy.addReviewNode()` static helper,
  `QuarantineFaultPolicy`, `SchemaDriftFaultPolicy` (examples/)
- Same construction-site changes as runtime consumers at each mutation construction point

## Verification

After refactor, verify:
1. `GraphRuleEngine`, `GraphInvariantEngine`, `PatternEvaluator`, `PatternMatchingSupport`
   have zero imports of domain-specific types from `io.casehub.desiredstate.api.*`
   (`DesiredNode`, `DesiredStateGraph`, `NodeId`, `NodeType`, `Dependency`). Remaining api
   imports (`GraphMutation`, `ConflictingMutationException`) are generic types that move to
   graph-core at extraction (step 2, platform#267)
2. All existing tests pass after mechanical migration (variant renames, `String` for
   `NodeId`/`Dependency`, type parameter addition) — same test logic, updated construction
3. `GraphView`, `MutableGraphView`, `GraphReader`, `GraphWriter` interfaces are in a
   subpackage that can be moved to platform mechanically

## References

- Migration context §5 — `/Users/mdproctor/claude/casehub/desiredstate/docs/specs/issue-128-migrate-yaml-core/2026-09-02-yaml-core-migration-context.md`
- Issue #129 — scope items, acceptance criteria, change matrix
- Platform#267 — graph-core extraction (step 2, depends on this refactor)
- `ForEachAdapter<E>` — yaml-core precedent for reader/adapter pattern
- D1–D5 decisions — `specs/issue-138-runtime-polish/decisions.md`
