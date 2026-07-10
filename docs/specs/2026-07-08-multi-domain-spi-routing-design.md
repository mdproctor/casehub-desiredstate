# Multi-Domain SPI Routing Design

**Issues:** #51 (ActualStateAdapter routing), #52 (remaining CDI-ambiguous SPIs)
**Date:** 2026-07-08
**Status:** Draft

## Problem

In multi-domain deployments, multiple implementations of `ActualStateAdapter` and `EventSource`
appear on the classpath. `ReconciliationLoop` injects a single instance of each, causing CDI
ambiguity. `NodeProvisionerRouter` solved this for `NodeProvisioner` — the same pattern needs
extending to the remaining ambiguous SPIs.

## Scope Analysis

Four SPIs were identified as potentially ambiguous (#52). Analysis shows only two need work:

| SPI | Problem | Solution |
|-----|---------|----------|
| `ActualStateAdapter` | CDI ambiguity — ReconciliationLoop injects one | Router by NodeType (like NodeProvisionerRouter) |
| `EventSource` | CDI ambiguity — ReconciliationLoop injects one | Separate `MergedEventSource` interface + composition via Multi.merge() |
| `FaultPolicy` | None — FaultPolicyEngine already takes List\<FaultPolicy\> | No changes needed |
| `GoalCompiler<G>` | None — runtime never injects it; domain code selects its own | No changes needed |

## Design

### 1. ActualStateAdapter Routing

#### 1.1 Interface Change — ActualStateAdapter (api/)

Add `handledTypes()` to the existing interface. No default — every adapter must declare its types.

```java
public interface ActualStateAdapter {
    ActualState readActual(DesiredStateGraph desired, String tenancyId);
    Set<NodeType> handledTypes();
}
```

Resync intervals are NOT added here — they belong on `NodeProvisioner` and flow through
`NodeProvisionerRouter`. ActualStateAdapter does not own scheduling.

#### 1.2 Interface Change — DesiredStateGraph (api/)

Add `filterByTypes()` as a default method. This consolidates the graph-filtering logic used
by both `ReconciliationLoop.reconcileTypes()` and `DefaultActualStateAdapterRouter`.

```java
default DesiredStateGraph filterByTypes(Set<NodeType> types) {
    DesiredStateGraph result = this;
    for (Map.Entry<NodeId, DesiredNode> entry : nodes().entrySet()) {
        if (!types.contains(entry.getValue().type())) {
            result = result.withoutNode(entry.getKey());
        }
    }
    return result;
}
```

Uses the subtractive approach — `withoutNode()` handles dependency cleanup automatically.
`ReconciliationLoop.filterGraph()` is replaced by calls to this method.

#### 1.3 New Interface — ActualStateAdapterRouter (api/)

```java
public interface ActualStateAdapterRouter {
    ActualState readActual(DesiredStateGraph desired, String tenancyId);
    Set<NodeType> allHandledTypes();
}
```

Two methods: `readActual()` for the hot-path adapter dispatch, and `allHandledTypes()` for
startup validation and diagnostics. Symmetric with `NodeProvisionerRouter.allHandledTypes()`.

The router splits the graph by adapter, calls each with its subset, and merges results.
Consumers see one unified `ActualState`.

#### 1.4 DefaultActualStateAdapterRouter (runtime/)

Constructor takes `Collection<ActualStateAdapter>`. At construction time:
- Builds routing table `Map<NodeType, ActualStateAdapter>`
- Validates no overlapping types (same error pattern as DefaultNodeProvisionerRouter)

`readActual()` implementation:
1. Partition graph nodes by adapter using the routing table
2. For each adapter with matching nodes, call
   `adapter.readActual(desired.filterByTypes(adapterTypes), tenancyId)`
3. Merge all returned `ActualState` maps into one
4. Nodes not covered by any adapter get `NodeStatus.UNKNOWN`

`allHandledTypes()` returns the routing table's key set.

#### 1.5 CdiActualStateAdapterRouter (runtime/)

`@ApplicationScoped`, extends `DefaultActualStateAdapterRouter`. Collects adapters via
`Instance<ActualStateAdapter>`.

```java
@ApplicationScoped
public class CdiActualStateAdapterRouter extends DefaultActualStateAdapterRouter {
    @Inject
    public CdiActualStateAdapterRouter(Instance<ActualStateAdapter> adapters) {
        super(StreamSupport.stream(adapters.spliterator(), false).toList());
    }
}
```

No explicit no-args constructor needed — Quarkus ArC generates one for proxy support
automatically. Consistent with `CdiNodeProvisionerRouter`, which also omits it.

#### 1.6 ReconciliationLoop Changes

**Field and injection changes:**
- Replace `ActualStateAdapter` field with `ActualStateAdapterRouter`
- Replace `EventSource` field with `MergedEventSource` (see §2)
- All `actualStateAdapter.readActual()` calls become `actualStateAdapterRouter.readActual()`
- `ReconciliationLoop.filterGraph()` is replaced by `desired.filterByTypes(types)` (see §1.2)

**Startup cross-validation (CDI constructor only):**
Validate that every node type handled by provisioners has corresponding adapter coverage:

```java
Set<NodeType> provisionerTypes = router.allHandledTypes();
Set<NodeType> adapterTypes = actualStateAdapterRouter.allHandledTypes();
if (!adapterTypes.containsAll(provisionerTypes)) {
    Set<NodeType> uncovered = new LinkedHashSet<>(provisionerTypes);
    uncovered.removeAll(adapterTypes);
    throw new IllegalArgumentException(
        "NodeTypes handled by provisioners but not by any ActualStateAdapter: " + uncovered);
}
```

Without this, a provisioner handling type C with no adapter coverage produces an infinite
provision cycle: the adapter never reports PRESENT, so reconciliation keeps trying.

**Interval-grouped scheduling interaction:**
When `reconcileTypes(types)` fires for an interval group, it calls `desired.filterByTypes(types)`
to produce a type-filtered subgraph, then passes that subgraph to
`actualStateAdapterRouter.readActual()`. The router partitions the already-filtered graph
further by adapter. This two-level filtering is correct — the outer filter selects which types
to reconcile this cycle, the inner partition selects which adapter handles each type. Both
levels use `DesiredStateGraph.filterByTypes()`.

For event-driven `reconcile()`: the full graph goes to the router, which partitions by adapter.
No outer type filter — all types participate.

**Test constructor migration:**
Test constructors that take `ActualStateAdapter` are updated to take `ActualStateAdapterRouter`.
Since `ActualStateAdapterRouter` has two methods (`readActual` and `allHandledTypes`), it is
not a functional interface — lambdas cannot be used directly. Tests wrap their adapter:

```java
var adapterRouter = new DefaultActualStateAdapterRouter(List.of(testAdapter));
loop = new ReconciliationLoop(planner, executor, adapterRouter, faultEngine, ...);
```

`TestActualStateAdapter` inner classes gain `handledTypes()` returning a configurable set.
Test constructors that previously set `router = null` (bypassing interval-grouped scheduling)
continue to work — the null-router path in `ReconciliationLoop` is unchanged; only the
adapter field type changes.

### 2. EventSource Composition

#### 2.1 New Interface — MergedEventSource (api/)

```java
public interface MergedEventSource {
    Multi<StateEvent> stream();
}
```

Separate type from `EventSource` — `ReconciliationLoop` depends on this interface, not on
`EventSource` directly. Domain `EventSource` beans are never confused with the merged source
because they are different types. No CDI ambiguity, no `instanceof` workaround needed.

This follows the same separation as `ActualStateAdapterRouter` (consumer interface) vs
`ActualStateAdapter` (domain SPI): consumers depend on the composed abstraction, not on the
raw SPI.

#### 2.2 DefaultMergedEventSource (runtime/)

Constructor takes `Collection<EventSource>`. Merges all source streams with per-stream
error isolation.

```java
public class DefaultMergedEventSource implements MergedEventSource {
    private static final Logger LOG = Logger.getLogger(DefaultMergedEventSource.class.getName());

    private final List<EventSource> sources;

    public DefaultMergedEventSource(Collection<EventSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public Multi<StateEvent> stream() {
        if (sources.isEmpty()) return Multi.createFrom().empty();
        if (sources.size() == 1) return sources.getFirst().stream();
        return Multi.createBy().merging().streams(
            sources.stream()
                .map(s -> s.stream()
                    .onFailure().retry()
                        .withBackOff(Duration.ofSeconds(1))
                        .atMost(3)
                    .onFailure().recoverWithMulti(failure -> {
                        LOG.warning("EventSource stream failed after retries: "
                            + failure.getMessage());
                        return Multi.createFrom().empty();
                    }))
                .toList()
        );
    }
}
```

**Error isolation (multi-source only):** Each source stream is wrapped with retry-on-failure
before merging. If one domain's `EventSource.stream()` fails, only that stream retries —
other domains' event streams continue uninterrupted. After 3 retries with exponential backoff,
the failed stream is logged and replaced with an empty stream. The merged stream stays alive.
Periodic resync covers the gap for the affected domain.

**Single-source path:** The `sources.size() == 1` optimisation intentionally returns the raw
stream without retry/recover wrapping. In a single-domain deployment, a failed event source
is a clear operational signal — the subscription's error handler in `TenantLoop.start()` logs
it at WARNING level and the subscription terminates. Silent recovery would mask the failure.
Periodic resync continues regardless.

#### 2.3 CdiMergedEventSource (runtime/)

`@ApplicationScoped`, extends `DefaultMergedEventSource`. Collects event sources via
`Instance<EventSource>`.

```java
@ApplicationScoped
public class CdiMergedEventSource extends DefaultMergedEventSource {
    @Inject
    public CdiMergedEventSource(Instance<EventSource> sources) {
        super(StreamSupport.stream(sources.spliterator(), false).toList());
    }
}
```

No self-filtering needed — `MergedEventSource` does not extend `EventSource`, so
`Instance<EventSource>` never discovers it. Clean CDI separation.

#### 2.4 ReconciliationLoop Change

Replace `EventSource` field and injection with `MergedEventSource` (interface injection).
`MergedEventSource` is a functional interface (single method `stream()`), so test
constructors accept lambdas: `testEventSource::stream`.

### 3. Impact on Existing Code

#### 3.1 Example Modules

Each domain's `ActualStateAdapter` gains `handledTypes()` returning the same set as its
corresponding `NodeProvisioner`:

| Module | Adapter | Types (from provisioner) | Has EventSource? |
|--------|---------|--------------------------|-------------------|
| dungeon | `DungeonActualStateAdapter` | Same as `GoblinProvisioner.handledTypes()` | Yes — `DungeonEventSource` |
| pipeline | `PipelineActualStateAdapter` | Same as `PipelineProvisioner.handledTypes()` | Yes — `PipelineEventSource` |
| spatial | `BattlefieldActualStateAdapter` | Same as `BattlefieldProvisioner.handledTypes()` | No — periodic resync only |
| expansion | `ExpansionActualStateAdapter` | Same as `ExpansionProvisioner.handledTypes()` | No — periodic resync only |

Spatial and expansion domains have no `EventSource` implementation. They rely entirely on
interval-grouped periodic resync. `CdiMergedEventSource` discovers only dungeon and pipeline
event sources; this is correct and expected.

#### 3.2 testing/ Module

`MockActualStateAdapter` gains `handledTypes()` as a configurable field (like
`MockNodeProvisioner.setHandledTypes()`).

`CannedEventSource` — no changes needed. It implements `EventSource`, which is discovered
by `CdiMergedEventSource` automatically.

#### 3.3 Runtime Tests

ReconciliationLoop tests use inline `TestActualStateAdapter` classes. These gain
`handledTypes()` returning a configurable set. Test constructors are updated to take
`ActualStateAdapterRouter` — tests wrap their adapter in `DefaultActualStateAdapterRouter`.

For `EventSource`, test constructors take `MergedEventSource` (a functional interface).
Existing `TestEventSource` passes via method reference: `testEventSource::stream`.

#### 3.4 engine-adapter / work-adapter

No references to `ActualStateAdapter` or `EventSource` — no changes needed.

### 4. Testing Strategy

| Component | Test Approach |
|-----------|--------------|
| `DesiredStateGraph.filterByTypes()` | Unit test: filter nodes by type, verify dependency cleanup, empty result for no matches |
| `DefaultActualStateAdapterRouter` | Unit test: multi-adapter dispatch, overlapping type rejection, unknown type handling, `allHandledTypes()` |
| `CdiActualStateAdapterRouter` | Verify CDI wiring succeeds |
| `DefaultMergedEventSource` | Unit test: empty sources, single source passthrough, multi-source merge, per-stream error isolation and retry |
| `CdiMergedEventSource` | Verify CDI wiring succeeds, no self-inclusion |
| ReconciliationLoop startup validation | Unit test: provisioner types ⊄ adapter types → fail fast |
| ReconciliationLoop integration | Existing tests updated to use router/merged source — verify no behavioral regression |

### 5. Migration Notes

- **Breaking change:** `ActualStateAdapter.handledTypes()` is a new abstract method. All
  implementations must be updated. Pre-release project — no backward compatibility concern.
- **Breaking change:** `ReconciliationLoop` constructor signatures change — `ActualStateAdapter`
  → `ActualStateAdapterRouter`, `EventSource` → `MergedEventSource`. Test code that constructs
  ReconciliationLoop directly needs updating.
- **New API types:** `ActualStateAdapterRouter`, `MergedEventSource`, and
  `DesiredStateGraph.filterByTypes()` are additions to the api/ module.
- **Single-domain deployments** — work identically. Router with one adapter, merged source with
  one event source. Zero overhead path (single-source optimisation in DefaultMergedEventSource).
