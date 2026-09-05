---
layout: post
title: "Cutting the cord between engines and domain types"
date: 2026-09-05
entry_type: note
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [graph-engine, generics, jpa, serialization, architecture]
series: issue-138-runtime-polish
---

The desired-state runtime has a graph engine problem. `GraphRuleEngine` and `GraphInvariantEngine` are generic in purpose — fixed-point evaluation, pattern matching, cycle detection — but they're littered with imports from `api/`: `DesiredNode`, `DesiredStateGraph`, `NodeId`, `NodeType`, `Dependency`. Five domain types wired directly into code that doesn't care about any of them.

This matters because these engines are destined for extraction to `graph-core` (platform#267), a foundation-tier module shared across the platform. Every domain import is a dependency that follows them out. The extraction should be a mechanical package move, not a design project.

The approach: `GraphView<N>` and `MutableGraphView<N>` — generic view interfaces that expose node lookup, type extraction, and dependency traversal via string IDs. A `GraphReader<G, N>` / `GraphWriter<G, N>` adapter pair bridges each concrete graph type. `DesiredStateGraphAdapter` implements both for the current `DesiredStateGraph` / `DesiredNode` pair. At extraction time, the adapter stays behind; the views and engines move.

The hard part wasn't the interfaces — it was the imperative rules. `@GraphRule` methods take `DesiredStateGraph` as a parameter via reflection. Changing that parameter type would break every user's rule methods. The solution: function closures. The recorder wraps each `Method.invoke()` call in a `Function<MutableGraphView<N>, List<GraphMutation<N>>>` that closes over the original method, the instance, and the knowledge of how to extract a `DesiredStateGraph` from the view. The engine never sees the domain type; the closure handles the translation.

After generification, all five engines have zero desiredstate-specific imports. `GraphMutation<N>` was parameterised in place across ~80 references — `AddDependency`/`RemoveDependency` became `AddEdge<N>`/`RemoveEdge<N>` with string IDs instead of `NodeId`. `ConflictingMutationException` moved to string IDs. A new `GraphCycleException` handles cycles in the generic layer while the domain-specific `CyclicDependencyException` stays in `api/`.

With the engines clean, I turned to the second gap: `ReconciliationStateStore`. The in-memory default loses its graph snapshots on restart, which means orphaned nodes fall back to `TransitionPlanner`'s private `UnknownSpec` — a type provisioners can't pattern-match on. The fix they resort to is inferring entity type from `NodeId` naming conventions, which couples the provisioner to the goal compiler's ID scheme.

`JpaReconciliationStateStore` follows the `JpaFaultCountStore` pattern: `@ApplicationScoped`, Flyway migration, classpath-activated. The interesting design question was serialising the `DesiredStateGraph` — specifically the polymorphic `NodeSpec` marker interface. Each domain has its own implementations (`BronzeSpec`, `ScoutSpec`, etc.), and `api/` must stay free of Jackson annotations.

I stored the fully-qualified class name alongside each node's serialised spec. On deserialisation, `Class.forName()` resolves the concrete type and Jackson reconstructs the instance. This is fragile under class renames, but for a pre-release codebase that's fix-forward territory. The alternative — a `NodeSpecTypeMap` SPI in `api/` wired through build extensions — would have required changes across three modules for an S-sized issue.

One subtlety surfaced during testing: JPA's L1 cache. Loading an entity via `em.find()`, then removing it in a separate `@Transactional` method, then loading again returns the stale cached entity from the original persistence context. The fault count store's `getCount()` doesn't hit this because its tests never pre-load before removing. Adding `@Transactional` to `load()` fixed it — each call gets a fresh persistence context.

The graph-core extraction path is now clear. The engines, views, and adapter interfaces sit in a `graph` subpackage inside `annotations/runtime/`. When platform#267 lands, that subpackage moves to its own module — no API changes, no consumer impact, just a package move with a new Maven coordinate.
