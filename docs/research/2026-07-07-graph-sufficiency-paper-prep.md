# Graph Model Sufficiency for Desired-State Reconciliation in Non-Infrastructure Domains

**Status:** Research prep — literature review, empirical findings, gap analysis
**Date:** 2026-07-07
**Context:** CaseHub desiredstate runtime, spatial/vector POC (#57), issues #60/#61

## Thesis

A typed-node directed acyclic graph (DAG) with opaque domain specs is representationally
sufficient for desired-state reconciliation in spatial and continuous domains — not only
infrastructure/container orchestration. The gap identified by empirical stress-testing is
not in state *representation* but in aggregate *reasoning* about state, which is an
orthogonal concern solved by a situational awareness layer.

## Empirical Evidence (CaseHub Spatial POC)

### Setup

10x10 terrain grid with fog of war, three scenarios of increasing difficulty, each
pushing on a different layer of the graph model's capability:

| Layer | What it tests | Result |
|-------|---------------|--------|
| 1. Region-as-node, strength-as-spec | Can spatial state be modeled as graph nodes? | **Sufficient** |
| 2. Zone group nodes for ratio distribution | Can cross-node invariants (ratios) be maintained? | **Sufficient** — GoalCompiler recompilation + provisioner ordering |
| 3. Dynamic rebalancing via fault policy | Can fault policies redistribute without graph-internal knowledge? | **Sufficient after fix** — FaultPolicy SPI gained ActualState parameter |
| 4. Strategic pivot (aggregate evaluation) | Can the system detect "this approach is failing" and switch plans? | **Not a representation problem** — solved by RAS situational awareness |

### Key Findings

1. **Graph model handles spatial topology.** Region-as-node, strength-as-spec,
   zone group nodes with ratio-based allocation. `DistributionGoalCompiler` produces
   valid graphs. Force distribution, frontier expansion, zone splits — all expressible
   as standard graph operations (overlay, withNode, withMutation).

2. **Empty dependency sets degrade gracefully.** Spatial domains produce graphs with
   minimal or no edges. TransitionPlanner's Kahn sort produces a valid (arbitrary)
   ordering for independent nodes. No special-casing needed — a graph with no edges
   is a degenerate but valid DAG.

3. **Opaque NodeSpec is the key abstraction.** `NodeSpec` is a marker interface —
   domains implement with typed records (`UnitSpec(strength)`, `ZoneSpec(totalForce,
   allocation)`). The graph model imposes no constraints on spec content. Continuous
   values, ratios, spatial coordinates — all expressible as spec fields.

4. **The representation/reasoning distinction.** The graph CAN represent spatial state.
   What it cannot do is *reason* about aggregate patterns across nodes (correlated
   failures, approach-level health, strategic alternatives). This is an evaluation
   concern, not a storage concern. Solved by RAS (Reliability, Availability,
   Serviceability) integration: CloudEvents → Ganglia pattern detection → case-triggered
   response.

5. **ReconciliationLoop coupling is shallow.** Despite 33 references to
   `DesiredStateGraph`, the loop uses only `nodes()` (iterate for drift detection),
   `withMutation()` (apply fault feedback), and CAS on `AtomicReference`. All
   topology-aware operations (dependencies, ordering, roots/leaves) are isolated
   in `TransitionPlanner`. The loop itself is representation-agnostic.

### Scenario Details

**Scenario 1 — Defense Posture:** Static defensive allocation across terrain zones.
Region cells as CELL nodes, force units as UNIT nodes, zones as ZONE group nodes.
Dependencies: zone → unit (ordering), unit → cell (placement). GoalCompiler
produces the graph from a `DefenseBlueprint`. Reconciliation detects destroyed
units via ActualStateAdapter, replans, reprovisions. Works without strain.

**Scenario 2 — Attack Waypoints:** Sequential advance along a path with defensive
allocation at each waypoint. Dependency chains express advance ordering. Path
rerouting (enemy blocks a waypoint) handled by GoalCompiler recompilation —
old waypoints orphaned and deprovisioned, new path provisioned. Teardown-rebuild
is verbose but correct.

**Scenario 3 — Force Distribution:** Ratio-based allocation across a frontier.
Zone nodes carry `ZoneSpec(totalForce, Map<CellRef, Double> allocation)`. Unit
specs derive strength from zone ratios. Three sub-scenarios:

- *Initial allocation + expansion*: GoalCompiler recompilation handles frontier growth
- *Priority shift*: Ratio changes expressed as UpdateNode mutations
- *Zone split*: Structural change via teardown-rebuild (old zone removed, two new zones added)
- *Fault policy redistribution*: ZoneRebalanceFaultPolicy uses ActualState to identify
  ABSENT units and redistribute allocation among survivors
- *Repeated losses (layer 4)*: Each fault cycle handled independently — no pattern
  detection, no escalation. **This is the finding that motivated RAS integration.**
- *Strategic pivot*: Graph transition from north-approach to south-approach works
  mechanically. The *decision* to pivot is the gap — solved by NodeFaultGanglion +
  ChainMode.Count → CaseTrigger → replan case.

### Architecture of the Solution

```
Graph model (representation)     RAS (reasoning)
─────────────────────────────    ──────────────────────────────
DesiredStateGraph                Ganglia (pattern detection)
  nodes(), withMutation()          NodeFaultGanglion
  TransitionPlanner (Kahn sort)    ChainMode.Count / .Streak / .Rate
  ReconciliationLoop               SituationDefinition (correlation window)
  FaultPolicy (tactical, per-node) CaseTrigger (strategic, cross-node)
                                   SituationRecompiler (replan)

ReconciliationLoop → CloudEvents → RAS Engine → Ganglia → Case → Response
```

## Literature Review

### Kubernetes Controller Pattern — Desired-State Reconciliation

The pattern originates in Kubernetes: declare desired state, observe actual state,
reconcile the delta. Well-established in practice but studied academically primarily
in the infrastructure domain.

- **Context Kubernetes (2026)** — Generalizes the pattern: "desired-state declaration
  and continuous reconciliation are not specific to containers. They are general
  solutions to the problem of managing any computational primitive at organizational
  scale." Does not prove representational sufficiency for non-infrastructure domains.
  [arXiv:2604.12599](https://arxiv.org/html/2604.12599v1)

- **Dirigent (2024)** — Lightweight serverless orchestration. Identifies the fundamental
  bottleneck in Knative as "multiple K8s-based controllers reconciling desired and
  actual cluster state" via synchronous read-modify-write to etcd. Performance analysis
  of the reconciliation critical path.
  [arXiv:2404.16393](https://arxiv.org/html/2404.16393)

### DAG-Based Reconciliation

- **x-cellent: Reconciling Object State Using DAGs** — Practical guide to modeling
  reconciliation tasks as DAG nodes with dependency edges. "If the desired state is
  'simple'... one can easily imagine how to reconcile that. But what if you have to
  reconcile desired state with an external API that consists of a bunch of objects
  that have to be created in a specific order?" Proposes companion data structures
  and contracts for reconcile-tasks. No formal sufficiency proof.
  [x-cellent blog](https://www.x-cellent.com/posts/reconciling-object-state-using-dags)

### State Modeling and Root Cause Analysis

- **SynergyRCA (2025)** — Graph-based state modeling for Kubernetes root cause analysis.
  Uses "StateGraph" to model resource entity dependencies and "MetaGraph" for
  cross-resource queries. Three-step state comparison: verify existence, ensure
  correctness, identify discrepancies. Relevant as a graph-based approach to state
  reasoning, but focused on diagnosis rather than representation sufficiency.
  [arXiv:2506.02490](https://arxiv.org/abs/2506.02490)

- **Distributed Tracing for Cascading Changes (2024)** — Traces object-to-object
  propagation in the Kubernetes control plane. Controllers form implicit dependency
  graphs; changes cascade through these graphs. Measures convergence time from initial
  change to all related objects reaching desired state.
  [arXiv:2411.01336](https://arxiv.org/html/2411.01336)

- **Monitoring Cascading Changes (2023)** — Earlier work on the same theme.
  "Changes to one object propagate to other objects in a chain." Establishes that
  controller dependencies create implicit DAGs.
  [arXiv:2307.12567](https://arxiv.org/pdf/2307.12567)

### Infrastructure-as-Code Reconciliation

- **NSync (2025)** — Automated IaC reconciliation via AI agents. Key insight:
  "infrastructure changes eventually all occur via cloud API invocations." Detects
  drift from API traces and reconciles back into IaC programs. Assumes infrastructure
  primitives throughout — no consideration of non-infrastructure state.
  [arXiv:2510.20211](https://arxiv.org/abs/2510.20211)

### HTN Planning and Verification

Relevant because the CaseHub GoalCompiler follows HTN-like decomposition (goals →
task networks → node graphs). HTN plan verification is well-studied but addresses
plan correctness, not state representation sufficiency.

- **Hoeller (2022)** — Compiling HTN plan verification problems into HTN planning
  problems. Translates verification into solvable planning instances.
  [PDF](https://staff.fnwi.uva.nl/g.behnke/papers/Hoeller2022Verify.pdf)

- **Behnke (2015)** — On the complexity of HTN plan verification and its implications
  for plan recognition. Proves NP-completeness of partially-ordered HTN verification.
  [PDF](https://staff.fnwi.uva.nl/g.behnke/papers/Behnke2015Verification.pdf)

- **AAAI 2026 — HTN Plan Verification by Qualitative Temporal Reasoning** — Exploits
  temporal structure in hierarchical decomposition. Transforms verification of
  partially-ordered task networks into temporal constraint satisfaction.
  [AAAI](https://ojs.aaai.org/index.php/AAAI/article/view/40958)

- **The Toad System (2024)** — Totally ordered HTN planning. Task decomposition
  graphs (TDGs) reflect reachable parts of the task hierarchy. Rebuilding during
  planning improves heuristic accuracy.
  [JAIR](https://www.jair.org/index.php/jair/article/download/14945/27049/39663)

### Spatio-Temporal Graph Models (Adjacent Domain)

These papers address spatial state in graphs, but from a machine learning perspective
rather than desired-state reconciliation. Included for completeness.

- **ST-GNN Survey (2023)** — Comprehensive survey of spatio-temporal graph neural
  networks. Mature research direction with foundational formalism. Challenges in
  scalability, adaptive learning, interpretability.
  [arXiv:2301.10569](https://arxiv.org/pdf/2301.10569)

- **Samen — Concept Shift in Spatio-Temporal Graphs (IJCAI 2025)** — Addresses
  concept shift where "similar historical observations lead to different future
  evolutions." Hierarchical state learning with prefix-suffix collaborative mechanism.
  [IJCAI](https://www.ijcai.org/proceedings/2025/0392.pdf)

- **Graph Mamba Survey (2024)** — State-space models for graph learning. Handles both
  spatial (node/edge relationships) and temporal (changes over time) in a unified
  framework. Dynamic graphs model evolving structures.
  [arXiv:2412.18322](https://arxiv.org/html/2412.18322v1)

### Formal Verification and Convergence

- **Model-Free Model Reconciliation (2019)** — Reconciliation in the context of
  explainable AI planning. Agent updates its model to align human and agent
  understanding. Different sense of "reconciliation" (model alignment, not state
  convergence) but the formal framework for reasoning about when two views of state
  are "sufficiently close" is relevant.
  [arXiv:1903.07198](https://arxiv.org/abs/1903.07198)

## Gap Analysis

### What the literature covers

| Topic | Coverage |
|-------|----------|
| Kubernetes reconciliation pattern | Well-documented, widely practiced |
| DAG-based task ordering for reconciliation | Practical guides exist, no formal treatment |
| HTN plan verification (correctness) | Formally studied (NP-completeness, SAT-based) |
| Spatio-temporal graph models (ML) | Extensive survey literature |
| Infrastructure drift detection | Active research (NSync, SynergyRCA) |

### What the literature does NOT cover

1. **Representational sufficiency of typed-node DAGs for non-infrastructure domains.**
   Every paper assumes infrastructure primitives (pods, services, VMs). No formal
   treatment of whether the same model handles spatial, continuous, or ratio-based state.

2. **Graceful degradation of DAG ordering with empty edge sets.** The spatial POC
   showed that a DAG with no edges is a valid degenerate case producing arbitrary but
   correct ordering. No paper addresses this property or its implications.

3. **The representation/reasoning separation.** The distinction between "can the graph
   model STORE this state?" (yes) and "can the graph model REASON about aggregate
   patterns across this state?" (no — need a situational awareness layer) does not
   appear in the literature as a formal separation of concerns.

4. **Opaque spec generality.** The marker-interface pattern (NodeSpec) that allows
   arbitrary domain types as node payloads without constraining the graph model is
   a practical design pattern with no formal treatment of its representational power.

5. **Empirical stress-testing methodology for state representation models.** The
   layered evaluation approach (topology → distribution → fault response → strategic
   reasoning) as a systematic way to probe representational limits is novel.

## Potential Paper Structure

### Title Candidates

- "Beyond Infrastructure: Graph Model Sufficiency for Desired-State Reconciliation
  in Spatial and Continuous Domains"
- "Typed-Node DAGs as Universal State Representations for Declarative Reconciliation"
- "Separating Representation from Reasoning in Desired-State Management: An Empirical
  Study"

### Proposed Sections

1. **Introduction** — Kubernetes popularized desired-state reconciliation but the
   pattern is assumed to require infrastructure-shaped problems. We show it generalizes.

2. **Background** — Desired-state reconciliation pattern. DAG-based transition planning
   (Kahn sort). Opaque node specs. Existing literature review.

3. **The Sufficiency Hypothesis** — A typed-node DAG with opaque specs and optional
   dependency edges is representationally sufficient for any domain where state can be
   decomposed into discrete identifiable units.

4. **Empirical Methodology** — Four-layer stress test:
   - L1: Can domain state be modeled as typed nodes?
   - L2: Can cross-node invariants be maintained through the provisioner pattern?
   - L3: Can fault policies operate with only per-node information + actual state?
   - L4: Can the system reason about aggregate patterns across nodes?

5. **Spatial Domain Case Study** — Three scenarios of increasing difficulty. Concrete
   code, test results, failure mode documentation.

6. **The Representation/Reasoning Boundary** — Layers 1-3 are representation concerns
   (sufficient). Layer 4 is a reasoning concern (requires situational awareness, not
   a new representation). Formal characterization of this boundary.

7. **Architecture** — How the two concerns compose: graph model for representation,
   RAS (event correlation + pattern detection) for aggregate reasoning. Neither
   replaces the other.

8. **Limitations** — Problems that genuinely aren't desired-state problems (pure
   numerical optimization, continuous field equations). Where the decomposition into
   discrete identifiable units fails.

9. **Related Work** — Kubernetes reconciliation, HTN planning, spatio-temporal graphs,
   IaC drift detection.

10. **Conclusion** — The graph model is more general than infrastructure. The gap
    is in reasoning, not representation. Separating these concerns enables the same
    runtime to serve both infrastructure and spatial/continuous domains.

## CaseHub Artifacts (Evidence Base)

| Artifact | Path |
|----------|------|
| Spatial POC design spec | Issue #57 closing comment |
| Defense posture test | `examples/spatial/.../defense/DefensePostureTest.java` |
| Attack waypoints test | `examples/spatial/.../attack/AttackWaypointsTest.java` |
| Force distribution test | `examples/spatial/.../distribution/ForceDistributionTest.java` |
| RAS situation detection test | `examples/spatial/.../distribution/SituationDetectionTest.java` |
| DesiredStateGraph interface | `api/.../api/DesiredStateGraph.java` (22 lines) |
| ReconciliationLoop | `runtime/.../runtime/ReconciliationLoop.java` (899 lines, 33 graph refs) |
| TransitionPlanner (Kahn sort) | `runtime/.../runtime/TransitionPlanner.java` |
| FaultPolicy SPI (with ActualState) | `api/.../api/FaultPolicy.java` |
| NodeFaultGanglion | `ras-adapter/.../ras/NodeFaultGanglion.java` |
| Expansion example (HTN + lifecycle) | `examples/expansion/` |
| Garden: withoutNode edge destruction | `GE-20260616-780f2e` |
| Garden: orphan UnknownSpec | `GE-20260703-b2073a` |
| Garden: CAS race in reconciliation | `GE-20260616-3d2605` |
| Issue: aggregate subgraph reasoning | #61 (epic, RAS integration) |
| Issue: runtime factoring assessment | #60 (this investigation) |
| Research doc | `docs/research/2026-06-07-desired-state-management-research.md` |
| Design spec | `docs/specs/2026-06-12-generic-runtime-design.md` |
