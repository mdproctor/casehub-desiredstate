# WorkItem Human Node Handler — Design Spec

**Issue:** casehubio/casehub-desiredstate#43
**Date:** 2026-06-26
**Prerequisite:** casehubio/work#275 — extract `WorkItemCreator` SPI into casehub-work-api

## Problem

`SimpleTransitionExecutor.executeProvision()` hard-codes a skip for `requiresHuman=true` nodes:

```java
if (node.requiresHuman()) {
    return new StepOutcome.Skipped("requires human");
}
```

Human nodes are permanently stuck — skipped every reconciliation cycle, no WorkItem created, no human notified, no tracking. The reconciliation loop keeps planning them, the executor keeps skipping them, indefinitely.

`CaseTransitionExecutor` handles human nodes via `HumanTaskTarget` bindings in case definitions (spec: `2026-06-17-workitem-integration-design.md`), but the STE (the lightweight executor for deployments without the full case engine) has no equivalent mechanism.

## Constraints

- **desiredstate is foundation-tier.** The runtime module must not gain heavyweight dependencies (JPA, Hibernate, Flyway).
- **desiredstate-api is SPI-first.** Every external capability is an interface: `NodeProvisioner`, `TransitionExecutor`, `GoalCompiler`, `ActualStateAdapter`, `FaultPolicy`, `EventSource`.
- **Adapter modules depend on API modules only.** `engine-adapter/` depends on `casehub-engine-api` (where `CaseHubRuntime` is an interface), `casehub-worker-api`, `casehub-engine-common`, `casehub-engine-flow`, `casehub-work-api`. Zero runtime dependencies. The work-adapter must follow this pattern.
- **Deprovision is always automated** for human nodes — neither STE nor CTE gates deprovision on `requiresHuman`. This matches real-world semantics (unregistering a device vs physically installing it).
- **StepOutcome.Skipped is the established convention** for human nodes — the CTE returns `Skipped("routed to WorkItem")`. The `faultFeedback()` loop only pattern-matches on `Failed`.

## Cross-Repo Prerequisite: casehubio/work#275

casehub-work has no creation SPI in its API module. `WorkItemService`, `WorkItemCreateRequest`, `WorkItemPriority`, and `WorkItem` all live in the runtime. No external module can create WorkItems without pulling in the full persistence stack.

work#275 extracts into `casehub-work-api`:

1. **`WorkItemCreator` interface** — analogous to `CaseHubRuntime` in `casehub-engine-api`:
   ```java
   public interface WorkItemCreator {
       WorkItemRef create(WorkItemCreateRequest request);
       Optional<WorkItemRef> findActiveByCallerRef(String callerRef);
   }
   ```
2. **`WorkItemCreateRequest`** — pure builder class, already depends only on API-module types (`Outcome`, `LabelPersistence`)
3. **`WorkItemPriority`** — simple enum, no dependencies
4. **`WorkItemLabelRequest`** — record, depends on `LabelPersistence` (already in api)
5. **`WorkItemRef`** — typed response: `record WorkItemRef(UUID id) {}`

`WorkItemService` in casehub-work runtime implements `WorkItemCreator`. The `findActiveByCallerRef` method filters by `WorkItemStatus.isActive()` (PENDING, ASSIGNED, IN_PROGRESS, SUSPENDED, DELEGATED) using an indexed database query.

## Design

### Architecture: SPI + Work-Adapter Module

Follow the `engine-adapter/` pattern: define an SPI in the API, provide a no-op default in the runtime, implement the work-backed version in a separate adapter module that depends on API modules only.

### 1. HumanNodeHandler SPI — `casehub-desiredstate-api`

```java
package io.casehub.desiredstate.api;

public interface HumanNodeHandler {
    StepOutcome onProvision(DesiredNode node, ProvisionContext context);
}
```

Provision-only. `ProvisionContext` already carries `tenancyId` and the full `DesiredStateGraph`.

No `onDeprovision` — current platform assumption is that deprovision is always automated. Can be extended later with a default method if human-gated deprovision is needed.

**Executor scope:** This SPI exists for executors that lack built-in human-task support. The CTE does not use it — it has its own mechanism (`HumanTaskTarget` bindings via engine HITL). All four combinations work correctly:

| Executor | work-adapter | Behavior |
|----------|-------------|----------|
| STE | absent | `NoOpHumanNodeHandler` — nodes skipped (current behavior) |
| STE | present | `WorkItemHumanNodeHandler` — WorkItems created |
| CTE | absent | CTE's own `HumanTaskTarget` bindings — engine creates WorkItems |
| CTE | present | CTE displaces STE; `WorkItemHumanNodeHandler` bean exists but is never invoked |

### 2. NoOpHumanNodeHandler — `casehub-desiredstate` runtime

```java
package io.casehub.desiredstate.runtime;

@DefaultBean
@ApplicationScoped
public class NoOpHumanNodeHandler implements HumanNodeHandler {
    @Override
    public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
        return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
    }
}
```

`@DefaultBean` — displaced by any non-default implementation on the classpath.

### 3. SimpleTransitionExecutor Changes — `casehub-desiredstate` runtime

New constructor parameter:

```java
public SimpleTransitionExecutor(NodeProvisioner provisioner, HumanNodeHandler humanNodeHandler) {
    this.provisioner = provisioner;
    this.humanNodeHandler = humanNodeHandler;
}
```

In `executeProvision()`, the `ProvisionContext` construction moves above the `requiresHuman` check so it's available for both paths. OTel span attributes are preserved unchanged:

```java
private StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
    Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("provision")
            .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
            .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
            .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
        ProvisionContext context = new ProvisionContext(tenancyId, graph);
        if (node.requiresHuman()) {
            return humanNodeHandler.onProvision(node, context);
        }
        ProvisionResult result = provisioner.provision(node, context);
        return switch (result) {
            case ProvisionResult.Success ignored -> new StepOutcome.Succeeded();
            case ProvisionResult.Failed f -> {
                span.setStatus(StatusCode.ERROR, f.reason());
                yield new StepOutcome.Failed(f.reason());
            }
            case ProvisionResult.PendingApproval pa ->
                new StepOutcome.Skipped("pending approval: " + pa.planReference());
        };
    } finally {
        span.end();
    }
}
```

`ProvisionContext` is cheap — a record with two fields, no computation. Moving it above the branch has no performance impact.

### 4. Work-Adapter Module — `work-adapter/`

New module `work-adapter/` with artifact `casehub-desiredstate-work`.

**Dependencies (API modules only — matching engine-adapter pattern):**
- `casehub-desiredstate-api` — for `HumanNodeHandler`, `DesiredNode`, `ProvisionContext`, `StepOutcome`
- `casehub-work-api` — for `WorkItemCreator`, `WorkItemCreateRequest`, `WorkItemPriority`, `WorkItemRef`
- `quarkus-arc` — for CDI annotations

**WorkItemHumanNodeHandler:**

```java
package io.casehub.desiredstate.work;

@ApplicationScoped
public class WorkItemHumanNodeHandler implements HumanNodeHandler {

    private final WorkItemCreator workItemCreator;

    @Inject
    public WorkItemHumanNodeHandler(WorkItemCreator workItemCreator) {
        this.workItemCreator = workItemCreator;
    }

    @Override
    public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
        String callerRef = "desiredstate:" + context.tenancyId() + ":" + node.id().value();

        Optional<WorkItemRef> active = workItemCreator.findActiveByCallerRef(callerRef);
        if (active.isPresent()) {
            return new StepOutcome.Skipped(
                "pending human action: WorkItem " + active.get().id());
        }

        WorkItemRef created = workItemCreator.create(WorkItemCreateRequest.builder()
            .title("Provision: " + node.id().value())
            .description("Human provisioning required for node "
                + node.id().value() + " (type: " + node.type().value() + ")")
            .category("desiredstate-provision")
            .callerRef(callerRef)
            .priority(WorkItemPriority.MEDIUM)
            .createdBy("desiredstate")
            .build());

        return new StepOutcome.Skipped(
            "pending human action: WorkItem " + created.id());
    }
}
```

**Design decisions:**

1. **Depends on `WorkItemCreator` interface (SPI), not `WorkItemService` (concrete).** Consistent with engine-adapter depending on `CaseHubRuntime` (interface), not the engine runtime. The `WorkItemCreator` implementation arrives at deployment time via CDI.

2. **`findActiveByCallerRef` — not `findByCallerRef`.** Only returns WorkItems where `WorkItemStatus.isActive()` is true (PENDING, ASSIGNED, IN_PROGRESS, SUSPENDED, DELEGATED). This prevents the re-provision deadlock:
   - Node X → WorkItem 123 created
   - Human completes WorkItem 123 (status → COMPLETED, terminal)
   - Node X removed → deprovisioned
   - Node X re-added → `findActiveByCallerRef` returns `Optional.empty()` (123 is COMPLETED)
   - New WorkItem 456 created → correct behavior

3. **callerRef format `"desiredstate:{tenancyId}:{nodeId}"`** — deterministic, unique per tenant+node. Uses a non-UUID prefix (`desiredstate:`) intentionally. `WorkItemCallerRef.parseCaseId()` returns `null` for these WorkItems, which is correct — they are not case-associated. The casehub-work-api Javadoc states: "Externally created WorkItems may use any format."

4. **`Skipped` for both paths** — the node isn't provisioned in either case. The message distinguishes "created" from "already active" for tracing/debugging.

5. **Convention-based metadata** — title from nodeId, description from nodeId + nodeType, category fixed to `"desiredstate-provision"`. Matches the CTE pattern (`"Review: " + nodeId` per the 2026-06-17 spec). No NodeSpec in payload — `NodeSpec` is a marker interface with no serialization contract.

### 5. Module Structure Update

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `work-adapter/` | `casehub-desiredstate-work` | `io.casehub.desiredstate.work` | WorkItem-backed HumanNodeHandler — creates WorkItems for requiresHuman nodes via WorkItemCreator SPI. |

### 6. Testing

**`runtime/` — SimpleTransitionExecutorTest:**
- Update `skipsHumanNodes()` to verify delegation to HumanNodeHandler
- New: human node delegates to handler, handler's StepOutcome is propagated
- New: ProvisionContext constructed before requiresHuman check (tenancyId + graph available to handler)
- New: NoOpHumanNodeHandler returns Skipped with expected message

**`work-adapter/` — WorkItemHumanNodeHandlerTest:**
- First call: WorkItem created via WorkItemCreator, Skipped returned with WorkItem id
- Subsequent call (same tenancyId + nodeId): `findActiveByCallerRef` finds active WorkItem, no duplicate created
- Re-provision after completion: `findActiveByCallerRef` returns empty (terminal WorkItem ignored), new WorkItem created
- callerRef format verified: `"desiredstate:{tenancyId}:{nodeId}"`
- WorkItemCreateRequest fields verified: title, description, category, priority, callerRef, createdBy
- Different tenancyId + same nodeId → separate callerRefs → separate WorkItems
- Same tenancyId + different nodeId → separate callerRefs → separate WorkItems

All tests mock `WorkItemCreator` — no database, no runtime dependency.

## Rejected Alternatives

**Direct casehub-work runtime dependency in work-adapter:** Violates the adapter-module tier convention. engine-adapter depends on `CaseHubRuntime` (interface in casehub-engine-api), not the engine runtime. The work-adapter must follow the same pattern via `WorkItemCreator` (interface in casehub-work-api).

**Direct casehub-work dependency in desiredstate-runtime:** Even worse — makes every desiredstate consumer transitively depend on JPA/Hibernate/Flyway.

**CDI event for human nodes:** Loses StepOutcome return. Fire-and-forget breaks idempotency tracking.

**Push through NodeProvisioner:** Domain provisioners don't know about WorkItems. Pushes work-management concerns into every domain.

**New StepOutcome variant (HumanPending):** Not needed — `Skipped` is the established convention (CTE uses it). `faultFeedback` only matches on `Failed`. Adding a variant forces exhaustive-switch updates for no behavioral benefit.

**Enrich NodeSpec with WorkItem metadata:** NodeSpec describes what a node IS, not how to handle it. Coupling graph semantics to work-management concerns.

**`findByCallerRef` without status filtering:** Idempotency bug — completed WorkItems from previous provision cycles permanently block re-provisioning. Must filter to active-only via `findActiveByCallerRef`.
