# CBR Integration for Desired-State Evolution

> **Issue:** #23
> **Status:** Design approved
> **Date:** 2026-07-10

## Problem

When desired-state reconciliation keeps failing — fault policies exhausted, reconciliation not
converging — the system has no mechanism to learn from past successful configurations. It retries
the same approach or escalates to humans. Case-Based Reasoning (CBR) closes this gap by retrieving
past successful configurations for similar situations and adapting them to the current context.

## CBR Cycle Mapping

| CBR Step | Desired-State Owner | What It Does |
|----------|-------------------|--------------|
| Retrieve | `ConfigurationRetriever` SPI (api/) | Find past similar configurations by fault/situation context |
| Reuse | `ConfigurationAdapter` SPI (api/) + `CbrFaultPolicy`/`CbrSituationRecompiler` (runtime/) | Adapt retrieved configuration and apply as mutations or graph replacement |
| Revise | Outside scope (§Deferred: CBR Revise Step) | Evaluate reconciliation outcome, feed back to case store |
| Retain | Outside scope (casehub-ledger + CaseMemoryStore) | Store configurations and reconciliation outcomes |

Retain and Revise are outside this repo's scope. Retrieve and Reuse are the scope of this design.

## Two Integration Tiers

### Tactical — CbrFaultPolicy

Per-node, per-fault. Runs within `FaultPolicyEngine.evaluate()` alongside other fault policies.
When a fault fires, retrieves past configurations where similar faults were resolved, adapts them
to the current graph, and diffs to produce `List<GraphMutation>`.

Use case: a node keeps failing to provision. Other policies return empty (exhausted). CbrFaultPolicy
retrieves a past configuration where a similar node type in a similar graph topology was successfully
provisioned with different spec parameters, and proposes an `UpdateNode` mutation.

### Strategic — CbrSituationRecompiler

Whole-graph, situation-driven. Triggered by RAS when aggregate patterns are detected (repeated
failures, persistent drift, zone degradation). Retrieves past whole-graph configurations that
resolved similar situations and returns a replacement `CompilationResult`.

Participates in `SituationRecompilerEngine` alongside domain-specific recompilers. Acts as a
fallback — domain recompilers with higher priority run first, and CbrSituationRecompiler handles
situations that no domain recompiler covers.

Use case: RAS detects 3 consecutive failures on nodes in a zone. `CbrSituationRecompiler` retrieves
a past configuration where a similar zone-level situation was resolved by restructuring the topology
(different node count, different dependency wiring) and proposes a full graph replacement.

## Domain Types (api/)

### RetrievalContext

Carries everything the retriever and adapter need to understand the current situation:

```java
public record RetrievalContext(
    DesiredStateGraph currentGraph,
    ActualState actualState,
    FaultEvent faultEvent,                    // non-null for FaultPolicy path
    ActiveSituation situation                 // non-null for SituationRecompiler path
) {}
```

Exactly one of `faultEvent` or `situation` is populated — distinguishes the tactical and strategic
tiers. Enforced via static factory methods:

```java
public static RetrievalContext forFault(DesiredStateGraph graph, ActualState actual,
    FaultEvent event)

public static RetrievalContext forSituation(DesiredStateGraph graph, ActualState actual,
    ActiveSituation situation)
```

The canonical constructor is package-private. Callers use the factory methods which guarantee
the invariant.

### RetrievedConfiguration

A past configuration that succeeded in a similar context:

```java
public record RetrievedConfiguration(
    DesiredStateGraph graph,          // the graph fragment that worked
    double confidence,                // retriever's similarity score (0.0–1.0)
    String sourceId,                  // opaque identifier for the past case
    Map<String, String> metadata      // domain-specific context
) {}
```

### AdaptedConfiguration

The result of adapting a retrieved configuration to the current context:

```java
public record AdaptedConfiguration(
    DesiredStateGraph graph,          // adapted graph fragment
    double confidence,                // adapter's confidence in the adaptation (0.0–1.0)
    String sourceId                   // carried through from RetrievedConfiguration
) {}
```

### CbrConfiguration

Threshold configuration controlling go/no-go gates:

```java
public record CbrConfiguration(
    double minimumRetrievalConfidence,   // below this, skip adaptation
    double minimumAdaptationConfidence,  // below this, don't apply
    int maxCandidates                    // how many to retrieve
) {}
```

Not a CDI bean. Built per-call from `PreferenceProvider` (see §CBR Chain). Default values are
defined by the `PreferenceKey` entries in §Preference Integration — those are the single source
of truth.

## SPIs (api/)

### SituationRecompiler — SPI Evolution

The existing `SituationRecompiler` SPI is evolved to accept `ActualState`. CBR retrieval needs
both desired and actual state to find similar past configurations — any intelligent recompiler
that reasons about runtime state benefits from the same access.

```java
public interface SituationRecompiler {

    Optional<CompilationResult> recompile(
        DesiredStateGraph current,
        ActualState actual,
        ActiveSituation situation,
        DesiredStateGraphFactory factory);

    default int priority() { return 0; }
}
```

**Parameter ordering:** Groups by purpose — state inputs (`current`, `actual`), trigger
(`situation`), utility (`factory`). Since this is a breaking change that touches all
implementations anyway, the ordering is deliberate.

**ActualState parameter:** Added to the method signature directly (breaking change). All existing
implementations update mechanically — `ExpansionSituationRecompiler` and any domain recompilers
accept the parameter even if unused. This platform has no external consumers; the breakage forces
every implementor to be aware that ActualState is available.

**`priority()` default method:** Supports the new `SituationRecompilerEngine` chain-of-responsibility
pattern (see §Runtime). Lower values run first. Default is `0`. `CbrSituationRecompiler` returns
`Integer.MAX_VALUE` to act as a fallback. Domain recompilers use the default or override explicitly.

`DesiredStateReplanDispatch` (engine-adapter) is updated to read `ActualState` via
`ActualStateAdapterRouter` using `ActiveSituation.tenancyId()`, and passes it to the engine.

### ConfigurationRetriever

Retrieve step — finds similar past configurations:

```java
public interface ConfigurationRetriever {
    List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults);
}
```

Returns ranked results (highest confidence first). Domain implementations wire `CaseRetriever`
(casehub-neocortex rag-api) under the hood, translating `RetrievalContext` into a similarity
query (see §Implementation Sketch: ConfigurationRetriever). Empty list = no similar cases found.

**Latency contract:** `CbrFaultPolicy` runs synchronously within `FaultPolicyEngine.evaluate()`,
which is in the fault-evaluation phase of the reconciliation loop. Existing fault policies are
local, fast operations. `ConfigurationRetriever.retrieve()` may perform network calls (embedding
similarity search). Implementations must return within a bounded time appropriate to the deployment.
If the underlying retrieval system exceeds the implementation's internal timeout, the retriever
must return an empty list rather than blocking indefinitely. The SPI does not prescribe a specific
timeout — that depends on the case store's latency characteristics — but the retriever must not
make the reconciliation cycle unbounded.

### ConfigurationAdapter

Reuse step — adapts a retrieved configuration to the current context:

```java
public interface ConfigurationAdapter {
    Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context);
}
```

Returns `Optional.empty()` if the retrieved configuration can't be meaningfully adapted. The adapter
may modify nodes, adjust specs, rewire dependencies. Confidence on the output reflects the adapter's
assessment of fit, independent of the retriever's similarity score.

## Runtime Implementations (runtime/)

### CBR Chain

Both `CbrFaultPolicy` and `CbrSituationRecompiler` execute the same chain:

1. Resolve `CbrConfiguration` from `PreferenceProvider` (per-call, not frozen at startup)
2. Build `RetrievalContext` from available inputs
3. `retriever.retrieve(context, config.maxCandidates())`
4. Filter: drop candidates below `config.minimumRetrievalConfidence()`
5. For each surviving candidate: `adapter.adapt(candidate, context)`
6. Filter: drop adapted results below `config.minimumAdaptationConfidence()`
7. Take highest-confidence result
8. Apply (diff-to-mutations for FaultPolicy, wrap in CompilationResult for SituationRecompiler)

Step 1 follows the established preference resolution pattern: `PreferenceProvider` is injected at
construction, `preferenceProvider.resolve(SettingsScope.root())` is called at each chain invocation.
This allows runtime preference changes (e.g., an operator lowering the confidence threshold while
investigating a persistent failure) to take effect without restart — consistent with how
`DefaultNodeProvisionerRouter.resyncIntervalFor()` resolves `RESYNC_INTERVAL`.

### CbrFaultPolicy

```java
@ApplicationScoped
public class CbrFaultPolicy implements FaultPolicy {
    private final ConfigurationRetriever retriever;
    private final ConfigurationAdapter adapter;
    private final PreferenceProvider preferenceProvider;
}
```

- Resolves `CbrConfiguration` per-call from `PreferenceProvider`
- Constructs `RetrievalContext.forFault(current, actual, event)` — `situation` null
- After step 7, diffs the adapted graph against the current graph to produce `List<GraphMutation>`
- Returns empty list if no candidate survives the confidence gates
- Participates in `FaultPolicyEngine` like any other policy — no ordering guarantees needed

### CbrSituationRecompiler

```java
@ApplicationScoped
public class CbrSituationRecompiler implements SituationRecompiler {
    private final ConfigurationRetriever retriever;
    private final ConfigurationAdapter adapter;
    private final PreferenceProvider preferenceProvider;

    @Override
    public int priority() { return Integer.MAX_VALUE; }
}
```

- Resolves `CbrConfiguration` per-call from `PreferenceProvider`
- Constructs `RetrievalContext.forSituation(current, actual, situation)` — `faultEvent` null
- `ActualState` is received as a parameter from `SituationRecompilerEngine` (no need to inject
  `ActualStateAdapterRouter` — the engine's caller provides it)
- After step 7, wraps adapted graph in `CompilationResult.single()`
- Returns `Optional.empty()` if no candidate survives
- `priority()` returns `Integer.MAX_VALUE` — runs last, acting as a fallback when domain
  recompilers return `Optional.empty()`
- When `NoOpConfigurationRetriever` is active (no domain retriever), functionally identical to
  the former `NoOpSituationRecompiler`

### SituationRecompilerEngine

Aggregates all `SituationRecompiler` beans via chain-of-responsibility — tries each in priority
order (ascending) until one returns a non-empty `CompilationResult`.

```java
@ApplicationScoped
public class SituationRecompilerEngine {
    private final List<SituationRecompiler> recompilers;

    SituationRecompilerEngine(@All List<SituationRecompiler> recompilers) {
        this.recompilers = recompilers.stream()
            .sorted(Comparator.comparingInt(SituationRecompiler::priority))
            .toList();
    }

    public Optional<CompilationResult> recompile(
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.recompile(
                current, actual, situation, factory);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
}
```

This mirrors `FaultPolicyEngine` architecturally: both aggregate SPI beans. The difference is
the aggregation pattern — `FaultPolicyEngine` merges all policy mutations; `SituationRecompilerEngine`
uses first-non-empty-wins because `CompilationResult` values cannot be meaningfully merged.

`DesiredStateReplanDispatch` injects `SituationRecompilerEngine` instead of `SituationRecompiler`.

### NoOp Defaults

- `NoOpConfigurationRetriever` (`@DefaultBean`) — returns empty list
- `NoOpConfigurationAdapter` (`@DefaultBean`) — returns `Optional.empty()`

When no domain provides implementations, both CBR paths are inert.

### NoOpSituationRecompiler — Removed

Replaced by `SituationRecompilerEngine` + `CbrSituationRecompiler`. When `NoOpConfigurationRetriever`
is active, `CbrSituationRecompiler` returns `Optional.empty()` for every call — functionally
identical to the old no-op. When the recompiler list is empty (CBR module not on classpath), the
engine itself returns `Optional.empty()`.

## Graph Diffing

`CbrFaultPolicy` diffs the adapted graph fragment against the current graph to produce mutations.

Algorithm:

```
For each node in adapted graph:
  - Not in current → AddNode
  - In current, spec differs → UpdateNode
For each node in current graph within the adapted graph's node set (scope):
  - Not in adapted → RemoveNode
For each dependency in adapted graph:
  - Not in current → AddDependency (target node must exist in current or adapted graph)
For each dependency in current graph between in-scope nodes:
  - Not in adapted → RemoveDependency
```

Scope is defined by the adapted graph's node set. The diff only proposes mutations for nodes
the adapted configuration covers — it never touches nodes outside the fragment.

**Cross-boundary dependency rules:**
- **AddDependency:** The adapted graph may propose dependencies where one endpoint is in-scope
  and the other is out-of-scope (e.g., wiring an adapted node to an existing infrastructure
  node). This is valid provided the target node exists in the current graph. Dependencies where
  neither endpoint exists in the current-or-adapted graph are rejected.
- **RemoveDependency:** Only dependencies where BOTH endpoints are in-scope are candidates for
  removal. Cross-boundary dependencies are never removed by the diff — the adapted fragment has
  no authority over them.

`NodeSpec` equality uses record structural equality (domains implement `NodeSpec` as records).
This is a precondition for correct diff behaviour — `UpdateNode` detection depends on
`NodeSpec.equals()`. The `testing/` module includes a contract test to verify this (see §Testing).

Extracted as `GraphDiff.computeMutations(DesiredStateGraph current, DesiredStateGraph adapted)`
— package-private in `runtime/`, testable independently.

## Observability

The CBR chain emits structured log events at each decision point:

| Event | Level | Content |
|-------|-------|---------|
| `cbr.retrieve` | INFO | Candidate count, top confidence score, source IDs |
| `cbr.retrieve.filtered` | DEBUG | Candidates dropped below retrieval threshold, their scores |
| `cbr.adapt` | INFO | Adapted candidate count, top confidence score |
| `cbr.adapt.filtered` | DEBUG | Adapted candidates dropped below adaptation threshold |
| `cbr.selected` | INFO | Selected candidate sourceId, confidence, path (fault/situation) |
| `cbr.no-candidate` | INFO | No candidate survived — chain returned empty |

Both `CbrFaultPolicy` and `CbrSituationRecompiler` use the same log event structure. Events
include the `tenancyId` (from `ActiveSituation.tenancyId()` or derived from graph context) for
correlation.

CloudEvent emission for CBR decisions (type `io.casehub.desiredstate.cbr.decision`) is deferred
to implementation — the log events above are the minimum viable observability.

## Preference Integration

`DesiredStatePreferenceKeys` extended with typed `PreferenceKey<T>` entries:

```java
public static final PreferenceKey<DoublePreference> CBR_MIN_RETRIEVAL_CONFIDENCE =
    new PreferenceKey<>("desiredstate", "cbr.min-retrieval-confidence",
        new DoublePreference(0.5), DoublePreference::parse);

public static final PreferenceKey<DoublePreference> CBR_MIN_ADAPTATION_CONFIDENCE =
    new PreferenceKey<>("desiredstate", "cbr.min-adaptation-confidence",
        new DoublePreference(0.6), DoublePreference::parse);

public static final PreferenceKey<IntPreference> CBR_MAX_CANDIDATES =
    new PreferenceKey<>("desiredstate", "cbr.max-candidates",
        new IntPreference(3), IntPreference::parse);
```

`DoublePreference` and `IntPreference` implement `SingleValuePreference` from platform-api.
They are promoted from `io.casehub.api.spi.routing` (casehub-engine-api) to
`io.casehub.platform.api.preferences` (casehub-platform-api), alongside `DurationPreference`.
All `SingleValuePreference` implementations belong in the same package.

## Implementation Sketch: ConfigurationRetriever

A typical `ConfigurationRetriever` implementation wraps `CaseRetriever` (casehub-neocortex rag-api):

1. **Feature extraction:** The retriever extracts query features from `RetrievalContext`:
   - Fault path: `FaultEvent.type()`, `FaultEvent.node()` node type, affected node's `NodeSpec`
     class, graph neighbourhood (adjacent nodes and dependency structure)
   - Situation path: `ActiveSituation.correlationKey()`, situation evidence keys, graph-level
     metrics (node count by type, dependency density)

2. **Similarity query:** Extracted features are serialised as a text description or structured
   query and passed to `CaseRetriever.retrieve()`, which performs embedding-based similarity
   search against the case store.

3. **Confidence mapping:** `CaseRetriever` returns scored results; the retriever maps these
   directly to `RetrievedConfiguration.confidence` (cosine similarity on embeddings, 0.0–1.0).

4. **Case store schema:** Each case entry stores a `DesiredStateGraph` (or graph fragment),
   the situation/fault context under which it was applied, and the reconciliation outcome
   (success/failure). The graph is stored as a serialised adjacency structure with node specs.

This sketch is illustrative — the full `CaseRetriever` integration specification belongs in the
casehub-neocortex repo. The SPI boundary (`ConfigurationRetriever`) is the contract; the
implementation behind it is domain-specific.

## Deferred: CBR Revise Step

The standard CBR cycle includes Revise — evaluating the outcome of an applied configuration and
feeding that evaluation back to the case store so future retrievals improve. This is the learning
loop that makes CBR useful beyond static lookup.

**Expected flow:**
1. CBR proposes a configuration (via `CbrFaultPolicy` or `CbrSituationRecompiler`)
2. The reconciliation loop applies it and observes the outcome (success, partial failure, full failure)
3. The outcome, paired with the original `RetrievalContext` and applied configuration, is emitted
   as a reconciliation outcome event
4. A downstream consumer (casehub-ledger or CaseMemoryStore) ingests the event and updates the
   case store — reinforcing successful configurations and down-weighting failures

Without Revise, CBR recommendations are static — quality depends entirely on the initial case
corpus. This is acceptable for the first integration (cases are manually curated), but the Revise
feedback loop is necessary for the system to improve autonomously.

**GitHub issue required:** Track the Revise step implementation as a separate issue in
casehub-desiredstate, linked to #23.

## Testing (testing/)

- `MockConfigurationRetriever` — programmable retriever for test scenarios
- `MockConfigurationAdapter` — programmable adapter for test scenarios

Runtime tests:

- `CbrFaultPolicyTest` — full chain: retrieve → adapt → diff → mutations
  - No candidates found → empty mutations
  - Candidates below retrieval confidence → filtered
  - Candidates below adaptation confidence → filtered
  - Successful adaptation → correct mutations generated
  - Conflicting mutations with other policies → ConflictingMutationException
  - Log events emitted at each stage (observability)
  - Per-call preference resolution: changed thresholds take effect on next call
- `CbrSituationRecompilerTest` — full chain: retrieve → adapt → CompilationResult
  - No candidates → Optional.empty()
  - Confidence gates → filtering
  - Successful → CompilationResult.single() returned
  - ActualState is passed through to RetrievalContext
  - Per-call preference resolution: changed thresholds take effect on next call
- `SituationRecompilerEngineTest` — chain-of-responsibility aggregation
  - Single recompiler returning non-empty → result returned
  - Multiple recompilers, first returns empty, second returns result → second used
  - Priority ordering respected — lower priority values run first
  - All return empty → Optional.empty()
  - Empty recompiler list → Optional.empty()
- `GraphDiffTest` — mutation generation
  - AddNode for new nodes
  - UpdateNode for changed specs
  - RemoveNode for removed nodes (in scope only)
  - Dependency mutations
  - Out-of-scope nodes untouched
  - Cross-boundary AddDependency to existing out-of-scope node → valid
  - Cross-boundary RemoveDependency → not generated (out of scope)
  - Empty diff → empty mutations
- `NodeSpecValueSemanticsTest` — contract test (in `testing/`)
  - Verifies that `NodeSpec` implementations satisfy value semantics: equal instances by
    field values produce `equals() == true` and identical `hashCode()`. Runs against all
    `NodeSpec` implementations on the classpath via service discovery or reflective scan.

## What Changes

| Location | Change |
|----------|--------|
| `api/` | Add `RetrievedConfiguration`, `AdaptedConfiguration`, `RetrievalContext`, `CbrConfiguration`, `ConfigurationRetriever`, `ConfigurationAdapter` |
| `api/` | Evolve `SituationRecompiler`: add `ActualState` parameter, reorder parameters, add `priority()` default method |
| `runtime/` | Add `CbrFaultPolicy`, `CbrSituationRecompiler`, `SituationRecompilerEngine`, `NoOpConfigurationRetriever`, `NoOpConfigurationAdapter`, `GraphDiff` |
| `runtime/` | Remove `NoOpSituationRecompiler` |
| `runtime/` | Extend `DesiredStatePreferenceKeys` with typed CBR `PreferenceKey<T>` entries |
| `engine-adapter/` | Update `DesiredStateReplanDispatch`: inject `SituationRecompilerEngine` + `ActualStateAdapterRouter`, pass `ActualState` to engine |
| `platform-api/` | Promote `DoublePreference`, `IntPreference` from casehub-engine-api to `io.casehub.platform.api.preferences` |
| `testing/` | Add `MockConfigurationRetriever`, `MockConfigurationAdapter`, `NodeSpecValueSemanticsTest` |

## What Does NOT Change

- `ReconciliationLoop` — no modifications
- `FaultPolicyEngine` — no modifications
- `LifecycleManager` — no modifications
- `DesiredStateGraph` — no modifications
- Any existing FaultPolicy implementation
- Any existing SituationRecompiler implementation (signature changes mechanically; no behavioural change required)

## Dependencies

No new external dependencies. All types used (`DesiredStateGraph`, `ActualState`, `FaultEvent`,
`ActiveSituation`, `CompilationResult`) are already in `api/`. The `ActiveSituation` import from
`io.casehub.ras.api` is already a dependency of `api/` (used by `SituationRecompiler`).
`DoublePreference` and `IntPreference` are promoted to `io.casehub.platform.api.preferences`
in casehub-platform-api.
