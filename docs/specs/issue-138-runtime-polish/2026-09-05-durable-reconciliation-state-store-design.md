# Design: Durable ReconciliationStateStore — JPA Implementation

**Issue:** casehubio/casehub-desiredstate#132
**Branch:** issue-138-runtime-polish
**Date:** 2026-09-05
**Scale:** S / Complexity: Med

## Problem

`ReconciliationStateStore` persists the last-reconciled desired graph per tenant, enabling
`TransitionPlanner` to resolve orphan node specs during deprovision. The default
`InMemoryReconciliationStateStore` loses state on JVM restart — orphan nodes fall back to
`TransitionPlanner.UnknownSpec` (private), which provisioners cannot pattern-match on.
The provisioner must then infer entity type from `NodeId` naming conventions
(GE-20260703-b2073a), which is fragile.

## Scope

JPA-backed `ReconciliationStateStore` in `persistence-jpa/`, following the established
`JpaFaultCountStore` pattern. Classpath-activated — displaces `DefaultReconciliationStateStore`
when `persistence-jpa` is on the classpath.

**Out of scope:** Partial graph persistence, graph versioning, query-time access to
individual nodes, migration from in-memory to JPA (no existing data to migrate).

## Design

### Table Schema

Single row per tenant. The SPI operates on whole graphs — `store()` replaces the entire graph,
`load()` returns the entire graph, `remove()` deletes it. A single-row schema maps 1:1 to
this contract.

```sql
CREATE TABLE ds_reconciliation_state (
    tenancy_id   VARCHAR(255) PRIMARY KEY,
    graph_json   TEXT NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);
```

Flyway migration: `V2__create_reconciliation_state.sql` at
`db/desiredstate/migration/`. Continues the version sequence from
`V1__create_fault_count.sql`.

### JPA Entity

`ReconciliationStateEntity` — `@Entity @Table(name = "ds_reconciliation_state")`.
Single `@Id tenancyId` (no composite key needed). Fields: `tenancyId` (VARCHAR PK),
`graphJson` (TEXT), `updatedAt` (TIMESTAMP).

### Store Implementation

`JpaReconciliationStateStore` — `@ApplicationScoped`, implements `ReconciliationStateStore`.
Injects `EntityManager` and `DesiredStateGraphFactory`. Displaces
`DefaultReconciliationStateStore` (`@DefaultBean`) via standard CDI priority.

| Method | Behaviour |
|--------|-----------|
| `store(tenancyId, graph)` | Serialize graph → JSON via `GraphSerializer`. Upsert: `em.find()` → update or `em.persist()`. Set `updatedAt = now`. |
| `load(tenancyId)` | `em.find()` → if found, deserialize JSON → `DesiredStateGraph` via `GraphSerializer`. Return `Optional.of(graph)`. On deserialization error, log warning, return `Optional.empty()`. |
| `remove(tenancyId)` | `em.find()` → `em.remove()` if present. |

All mutating methods are `@Transactional`.

### Serialization (D6, D8)

Package-private `GraphSerializer` handles `DesiredStateGraph` ↔ JSON conversion using
Jackson `ObjectMapper`.

**JSON envelope:**

```json
{
  "nodes": [
    {
      "id": "bronze-1",
      "specClass": "io.casehub.example.pipeline.BronzeSpec",
      "spec": { "field1": "value1" },
      "humanGating": "NONE",
      "hooks": null
    }
  ],
  "dependencies": [
    { "from": "silver-1", "to": "bronze-1" }
  ]
}
```

**Serialization path:**
1. Iterate `graph.nodes()` — for each `DesiredNode`: extract `id.value()`,
   `spec.getClass().getName()`, serialize spec via `mapper.valueToTree(spec)`,
   `humanGating.name()`, serialize hooks (if non-null, using FQCN for `LifecycleStep` subtypes).
2. Iterate `graph.dependencies()` — for each `Dependency`: `from.value()`, `to.value()`.
3. Write envelope as JSON string.

**Deserialization path:**
1. Parse JSON envelope.
2. For each node: `Class.forName(specClass)` → `mapper.treeToValue(specNode, specClass)` →
   construct `DesiredNode(NodeId.of(id), spec, HumanGating.valueOf(humanGating), hooks)`.
3. For each dependency: `new Dependency(NodeId.of(from), NodeId.of(to))`.
4. Reconstruct graph: `graphFactory.of(nodes, deps)`.

**Error handling:** On `ClassNotFoundException` or any Jackson deserialization error, log a
warning with the tenancy ID and return `Optional.empty()`. The planner already handles
`previousDesired == null` gracefully — orphan nodes use `UnknownSpec`. This is the correct
degradation path: a corrupted or stale stored graph should not prevent reconciliation.

### HookDescriptor and LifecycleStep Handling

`HookDescriptor` contains `List<LifecycleStep>` fields. `LifecycleStep` is a sealed interface
with three concrete records (`Verify`, `Notify`, `Wait`), all in `api/`. Since `api/` must
remain free of Jackson annotations, the serializer handles polymorphism via the same FQCN
approach: each `LifecycleStep` serialized as `{stepClass: "...", ...fields}`.

### Dependencies

`persistence-jpa/pom.xml` additions:
- `com.fasterxml.jackson.core:jackson-databind` (compile) — JSON serialization

`DesiredStateGraphFactory` injected via CDI — `DefaultDesiredStateGraphFactory` is already
`@DefaultBean @Singleton` in runtime/.

### CDI Priority Ladder

| Tier | Bean | Module |
|------|------|--------|
| 1a | `DefaultReconciliationStateStore` (`@DefaultBean`) | runtime/ |
| 2 | `JpaReconciliationStateStore` (`@ApplicationScoped`) | persistence-jpa/ |

Same pattern as `DefaultFaultCountStore` → `JpaFaultCountStore`.

### Test Strategy

`JpaReconciliationStateStoreTest` — `@QuarkusTest` with H2 `MODE=PostgreSQL` + Flyway
(same test config as `JpaFaultCountStoreTest`).

Test cases:
- `load_returnsEmpty_whenNothingStored`
- `store_thenLoad_roundTrips` — store a graph with multiple nodes (different NodeSpec types),
  dependencies, humanGating variants, and hooks; verify all fields survive round-trip
- `store_overwritesPreviousValue`
- `remove_clearsStoredGraph`
- `tenantIsolation_storesAreIndependent`
- `load_returnsEmpty_onDeserializationError` — store a row with malformed JSON, verify
  graceful degradation

Test fixtures use a simple `TestNodeSpec` record annotated with `@NodeTypeId("test")` in
the test package — follows the established pattern.

## References

- `ReconciliationStateStore.java` (api/) — SPI contract
- `InMemoryReconciliationStateStore.java` (api/) — in-memory default
- `DefaultReconciliationStateStore.java` (runtime/) — CDI fallback bean
- `ReconciliationLoop.java:747-761` — usage in `plan()` method
- `TransitionPlanner.java:36-78` — orphan spec resolution with `previousDesired`
- `JpaFaultCountStore.java` (persistence-jpa/) — established JPA store pattern
- `FaultCountEntity.java` (persistence-jpa/) — entity pattern
- `V1__create_fault_count.sql` — Flyway migration pattern
- `DesiredStateGraphFactory.java` (api/) — graph reconstruction
- `DefaultDesiredStateGraphFactory.java` (runtime/) — CDI bean for reconstruction
- `NodeTypeId.java` (api/) — type annotation (used by FQCN approach as context, not as discriminator)
- GE-20260703-b2073a — orphan deprovision with UnknownSpec (motivates why durable store matters)
- GE-20260609-ef7dbe — Flyway NOT NULL + DEFAULT H2 gotcha (informed test config choice)
- GE-20260606-e5f0ab — JSON format migration technique (informed graceful degradation approach)
