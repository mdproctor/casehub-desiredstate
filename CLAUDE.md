# CaseHub Desired State

## Project Type

type: java

## Repository Role

Foundation-tier generic desired-state management runtime. Domain-agnostic — knows about graphs, nodes,
edges, planners, reconciliation loops, and fault policy primitives. Knows nothing about Kubernetes pods,
IoT devices, CaseHub agents, or infrastructure resources. Domains plug in via SPIs.

**Tier:** Foundation (alongside casehub-platform, casehub-ledger, casehub-work, casehub-qhorus in the build order)

**Design philosophy:** Generic first, domains layered on top. The runtime is written once. Each new domain
contributes only domain-specific knowledge via four SPIs: GoalCompiler, ActualStateAdapter, NodeProvisioner,
FaultPolicy. Workflow execution delegates to casehub-engine-flow. Human nodes generate casehub-work WorkItems.

**Research doc:** `docs/superpowers/research/2026-06-07-desired-state-management-research.md` (in casehub-parent)

## Build Commands

```bash
mvn --batch-mode install
mvn --batch-mode deploy -DskipTests   # CI only — requires GITHUB_TOKEN
```

## Module Structure

| Module | Artifact | Root package | Purpose |
|--------|----------|-------------|---------|
| `api/` | `casehub-desiredstate-api` | `io.casehub.desiredstate.api` | Core SPIs + domain types. Pure Java, Mutiny provided, CDI annotations provided. |
| `runtime/` | `casehub-desiredstate` | `io.casehub.desiredstate.runtime` | TransitionPlanner, ReconciliationLoop, FaultPolicyEngine. Quarkus extension. |
| `testing/` | `casehub-desiredstate-testing` | `io.casehub.desiredstate.testing` | Mock SPIs and test fixtures. **Test scope only.** |

## Core SPIs (api/)

| SPI | Signature | Domain responsibility |
|-----|-----------|----------------------|
| `GoalCompiler<G>` | `compile(G goals, Constraints, DomainData) → DesiredStateGraph` | Translate goal declaration into node graph |
| `ActualStateAdapter` | `readActual(DesiredStateGraph) → ActualState` | Read current reality from domain sources |
| `NodeProvisioner` | `provision(DesiredNode, ProvisionContext) → ProvisionResult` | Create/update a single node |
| `NodeProvisioner` | `deprovision(ActualNode, DeprovisionContext) → DeprovisionResult` | Remove a single node |
| `FaultPolicy` | `onFault(FaultEvent, DesiredStateGraph) → GraphMutation` | Mutate graph in response to fault |
| `EventSource` | `stream() → Multi<StateEvent>` | Stream actual-state events into reconciliation loop |

## Core Runtime Types (api/)

| Type | Purpose |
|------|---------|
| `DesiredStateGraph` | Directed acyclic graph — `List<DesiredNode>`, `List<Dependency>` |
| `DesiredNode` | `id`, `type`, `spec` (opaque domain payload), `requiresHuman` |
| `TransitionPlan` | Pruning-first ordered steps — `removals: List<OrderedStep>`, `additions: List<OrderedStep>` |
| `ReconciliationResult` | `resolved`, `drifted`, `faulted` node lists + `mutations` |
| `NodeSpec` | Opaque domain-specific node specification |
| `FaultEvent` | Fault event with node reference and fault type |
| `GraphMutation` | Add/remove/update node in the desired graph |

## Ordering Rule — Pruning Before Growing

1. Diff desired graph vs actual state
2. Plan removal workflows (leaves before roots — dependency-aware)
3. Plan addition workflows (roots before leaves — dependency-aware)
4. Execute sequentially via casehub-engine-flow

This ensures no dangling dependencies and no half-removed states.

## Human Nodes

`DesiredNode.requiresHuman = true` → provisioning step generates a `WorkItem` (casehub-work) instead
of calling `NodeProvisioner`. ReconciliationLoop awaits WorkItem completion event via `CaseSignalSink`
before marking node provisioned and proceeding to dependent nodes.

## Cross-Repo Conventions

Protocols live in `casehub/garden`. Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/superpowers/specs/` |
| adr | `docs/adr/` |
| handover | workspace `HANDOFF.md` |
| write-blog | workspace `blog/` |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/casehub-desiredstate

## Workspace

**Project repo:** `/Users/mdproctor/claude/casehub/desiredstate`
**Workspace:** `/Users/mdproctor/claude/public/casehub-desiredstate`
**Workspace type:** public
