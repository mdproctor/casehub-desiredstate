# Per-Node Execution Toolbox — Design Spec

**Issue:** #28
**Date:** 2026-06-17
**Status:** Design

## Summary

Make the `PipelineProvisioner`'s processing stage execution logic pluggable via an
`ExecutionBackend` strategy SPI. Each processing stage (INGESTION through SINK)
delegates to an `ExecutionBackend` implementation that encapsulates the execution
technology. Lambda and Stream are built-in; Camel and Drools are future optional
backends.

Non-processing node types (DATA_SOURCE, SCHEMA, AI_REVIEW, HUMAN_REVIEW) are handled
directly by the provisioner — they are metadata registration and fault handling, not
execution. They will never need pluggable backends.

This is a strategy-pattern refactoring within `examples/pipeline/`, not a new module
or framework.

## Motivation

The current `PipelineProvisioner` dispatches on `NodeType` via a 180-line if-chain. Each
branch hardcodes domain-specific provisioning logic. Issue #28 asks for pluggable execution
backends (Camel, Drools, Stream) and notes: "The `NodeProvisioner` SPI already supports
this — different provisioner implementations would delegate to the appropriate backend."

The fix is a per-stage execution strategy that the provisioner delegates to for processing
stages, while continuing to handle simple metadata and review operations directly.

### Strategic context

The brainstorming session for this issue surfaced a larger vision: Worker data coordination
patterns (DataExchange, DataChannel) as engine-tier primitives alongside the existing
Blackboard. That vision is tracked in casehubio/engine#532, dependent on casehubio/engine#528
(WorkerFunction pluggability). The Exchange/DataChannel concepts are Worker coordination
primitives — they belong in the engine ecosystem, not in casehub-desiredstate.

This spec focuses on what #28 asks for: per-node execution backends within the pipeline
example.

## Design

### Why processing stages only

The provisioner handles 10 node types. Only 6 are processing stages where execution
technology matters:

| Node type | What provisioning does | Needs pluggable backends? |
|-----------|----------------------|--------------------------|
| `DATA_SOURCE` | Registers metadata in PipelineWorld | No — metadata registration |
| `SCHEMA` | Registers schema definitions | No — metadata registration |
| `INGESTION` | Validates source, sets RUNNING, wires downstream | **Yes** |
| `CLEANSER` | Validates upstream, sets RUNNING, wires downstream | **Yes** |
| `ENRICHER` | Validates lookup source, sets RUNNING, wires downstream | **Yes** |
| `VALIDATOR` | Validates schema ref, sets RUNNING, wires downstream | **Yes** |
| `TRANSFORMER` | Sets RUNNING, wires downstream | **Yes** |
| `SINK` | Sets RUNNING | **Yes** |
| `AI_REVIEW` | Invokes AgentProvider, records outcome | No — fault handling |
| `HUMAN_REVIEW` | Registers review entry | No — fault handling |

Wrapping DATA_SOURCE or SCHEMA in an `ExecutionBackend` would dilute the concept — these
are not "executing" anything. Keeping the provisioner responsible for them directly is
less abstraction for code that doesn't need abstraction.

### ExecutionBackend SPI

```java
package io.casehub.desiredstate.example.pipeline;

public interface ExecutionBackend {
    boolean handles(DesiredNode node);
    ProvisionResult provision(DesiredNode node, ProvisionContext context);
    DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context);
}
```

**`handles(DesiredNode)`** — inspects the node's type and/or spec to determine whether
this backend can execute it. Takes the full `DesiredNode` (not just `NodeType`) so backends
can dispatch on spec type for finer-grained selection — e.g., a future
`DroolsCleanserSpec` vs `StreamCleanserSpec` could route to different backends.

**`provision` / `deprovision`** — same signatures as `NodeProvisioner`, same return types.
The backend IS the execution strategy for this processing stage.

### Ambiguity resolution — fail fast at dispatch

When the provisioner dispatches to backends, if more than one backend matches a node,
it throws `AmbiguousBackendException` rather than silently picking the first:

```java
List<ExecutionBackend> matching = backends.stream()
    .filter(b -> b.handles(node))
    .toList();
if (matching.size() > 1) {
    throw new AmbiguousBackendException(node, matching);
}
if (matching.isEmpty()) {
    return new ProvisionResult.Failed(
        "No execution backend for: " + node.id().value());
}
return matching.get(0).provision(node, context);
```

This forces explicit resolution when backends compete for the same node type. No implicit
ordering, no CDI priority magic. If you add a CamelCleanserBackend, it must not overlap
with DefaultExecutionBackend's cleanser handling — either the default stops handling
cleansers, or the specs disambiguate via `handles()`.

### PipelineProvisioner refactoring

The provisioner becomes a hybrid: direct handling for metadata/review, delegation for
processing stages.

```java
public class PipelineProvisioner implements NodeProvisioner {

    private final PipelineWorld world;
    private final AgentProvider agentProvider;
    private final List<ExecutionBackend> backends;

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        // Metadata registration — handled directly
        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            return provisionDataSource(node);
        }
        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            return provisionSchema(node);
        }

        // Fault handling — handled directly
        if (type.equals(PipelineNodeTypes.AI_REVIEW)) {
            return provisionAiReview(node, context);
        }
        if (type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            return provisionHumanReview(node);
        }

        // Processing stages — delegate to execution backend
        return dispatchToBackend(node, context);
    }

    private ProvisionResult dispatchToBackend(DesiredNode node, ProvisionContext context) {
        List<ExecutionBackend> matching = backends.stream()
            .filter(b -> b.handles(node))
            .toList();
        if (matching.size() > 1) {
            throw new AmbiguousBackendException(node, matching);
        }
        if (matching.isEmpty()) {
            return new ProvisionResult.Failed(
                "No execution backend for: " + node.id().value()
                    + " (type: " + node.type().value() + ")");
        }
        return matching.get(0).provision(node, context);
    }
}
```

The metadata and review handling is extracted into private methods (4 methods, each 5-10
lines). The 6 processing stage branches move to `DefaultExecutionBackend`.

**Deprovision** mirrors provision: DATA_SOURCE and SCHEMA are handled directly (remove
from PipelineWorld); AI_REVIEW and HUMAN_REVIEW are handled directly (remove review
entry); processing stages are dispatched to the execution backend with the same
`AmbiguousBackendException` guard. The default backend's deprovision for all 6
processing stages is identical — `world.removeStage(node.id())` with downstream
cascade.

### DefaultExecutionBackend

The existing processing stage logic is extracted into a single backend:

```java
public class DefaultExecutionBackend implements ExecutionBackend {

    private static final Set<NodeType> PROCESSING_TYPES = Set.of(
        PipelineNodeTypes.INGESTION, PipelineNodeTypes.CLEANSER,
        PipelineNodeTypes.ENRICHER, PipelineNodeTypes.VALIDATOR,
        PipelineNodeTypes.TRANSFORMER, PipelineNodeTypes.SINK
    );

    private final PipelineWorld world;

    @Override
    public boolean handles(DesiredNode node) {
        return PROCESSING_TYPES.contains(node.type());
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        // Per-type precondition validation + set RUNNING + wire downstream
        // (existing if-chain logic, extracted here)
    }
}
```

### Known evolution: shared concerns separation

The `DefaultExecutionBackend` currently bundles three concerns per stage type. Only one
varies by execution technology:

| Concern | Example | Varies by technology? |
|---------|---------|----------------------|
| **Precondition validation** | Check upstream ingestion exists, validate schema ref | No — domain-invariant |
| **State management** | Set stage state to RUNNING in PipelineWorld | No — always needed |
| **Downstream wiring** | Call `registerDownstream()` to wire connections | No — always needed |
| **Execution** | Currently a no-op (simulation); would be "start Camel route" or "fire Drools session" | **Yes** |

When alternative backends arrive for specific stages (e.g., CamelCleanserBackend), they
need the same precondition validation, state management, and downstream wiring — only
the execution step differs. Without separation, every alternative backend duplicates
all three shared concerns.

This is a known future evolution point. The right separation (template method, composition,
or shared validators) will emerge when the first alternative backend is built. For the
current scope — one backend handling all 6 processing stages — bundling is correct.

What to watch for: when a second backend is added for any processing stage, factor out
precondition validation, state management, and downstream wiring before implementing
the backend. Don't duplicate any of the three.

### Future optional backends

Camel and Drools backends are separate issues. They would be additional
`ExecutionBackend` implementations:

| Future backend | Technology | Node types |
|---------------|-----------|-----------|
| `CamelExecutionBackend` | Apache Camel routes | Subset of processing stages |
| `DroolsExecutionBackend` | Drools rule sessions | Subset of processing stages |

When these arrive, `handles()` disambiguates: the alternative backend handles nodes
whose spec carries Camel/Drools configuration; `DefaultExecutionBackend` handles
everything else. No overlap, no ambiguity.

This is when the precondition validation separation (see above) becomes necessary.

### What does NOT change

- **No new module.** The SPI and implementations live in `examples/pipeline/`.
- **No Exchange, DataChannel, or ExchangeProcessor.** These are engine-tier Worker
  coordination primitives tracked in casehubio/engine#532.
- **No PipelineOrchestrator.** The existing `SimpleTransitionExecutor` and
  `CaseTransitionExecutor` already orchestrate node provisioning.
- **No quarkus-flow dependency.** The pipeline example continues to use the existing
  transition executor SPIs.
- **NodeProvisioner SPI unchanged.** `ExecutionBackend` is a pipeline-internal
  delegation strategy, not a core API type.

## Testing strategy

TDD throughout.

**Test assertions and behaviour are unchanged** — the refactoring is mechanical
(extract branches to backend + private methods, wire them together). The 23 existing
tests in `PipelineTest` verify the same outcomes. **Test setup code updates** to
construct the `DefaultExecutionBackend` and pass it to the provisioner.

New tests added:

1. **Backend dispatch** — provisioner correctly delegates processing stages to the
   execution backend
2. **Direct handling** — provisioner handles DATA_SOURCE, SCHEMA, AI_REVIEW,
   HUMAN_REVIEW directly (not via backend)
3. **Missing backend** — provisioning fails cleanly when no backend handles a
   processing stage
4. **Ambiguous backend** — `AmbiguousBackendException` thrown when multiple backends
   match the same node
5. **Backend isolation** — `DefaultExecutionBackend` tested independently with minimal
   PipelineWorld setup

## Cross-repo issues filed

| Issue | Repo | What it tracks |
|-------|------|---------------|
| engine#528 | casehubio/engine | WorkerFunction pluggability — extract Flow to optional module |
| engine#532 | casehubio/engine | Worker data coordination patterns — DataExchange, DataChannel alongside Blackboard |
