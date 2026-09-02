# Data Pipeline -- Java Annotations

Annotation-driven medallion pipeline demonstrating `@DesiredState`, `@Node`, `@DependsOn`, `@FaultPolicyDef`, `@GraphRule`, `@GraphInvariant`, and `@Tier`. Side-by-side companion to `examples/pipeline/` (programmatic) and `examples/pipeline-yaml/` (YAML).

**Declaration surface:** Java annotations on a single interface (`MedallionPipeline`)

## What It Demonstrates

| Feature | How |
|---------|-----|
| Declarative graph via interface | `@DesiredState` on the interface, `@Node` on default methods returning NodeSpec |
| Type-safe dependencies | `@DependsOn({"csv-ingest", "customer-schema"})` on node methods |
| Declarative fault policy | `@FaultPolicyDef` + `@Tier` at the interface level -- generates a `ThresholdFaultPolicy` at build time |
| Tier review factories | `@Tier(review = "createAiReview")` references a default method that produces the review spec |
| Graph rules (structural rewriting) | `@GraphRule` on a static method with `@Match` + `@NotExists` pattern vocabulary |
| Graph invariants (structural validation) | `@GraphInvariant` on a static method with `@Match` + `@DirectDep` -- ensures every sink has an upstream transformer |
| Human gating | `@Node(humanGating = HumanGating.PROVISION_ONLY)` on Gold-tier nodes |
| Reused NodeSpec types | All specs come from `examples/pipeline/` -- annotations are the declaration surface, not the domain types |

## Key Files

| File | Purpose |
|------|---------|
| `MedallionPipeline.java` | Single interface declaring the entire graph: 8 nodes, fault policy, 1 rule, 1 invariant |
| `MonitorSpec.java` | NodeSpec for the auto-wired monitoring node (created by graph rule) |
| `MedallionPipelineTest.java` | 9 tests -- compilation, node count, dependencies, fault policy, rule application, invariant enforcement |

## The Graph (One Interface)

The entire pipeline is declared on `MedallionPipeline`:

```
@DesiredState(namespace = "pipeline", name = "medallion")
@FaultPolicyDef(faultTypes = "PROVISION_FAILED", nodeTypes = {"transformer", "sink"},
    tiers = { @Tier(threshold=3, review="createAiReview"), @Tier(threshold=5, review="createHumanReview") })
public interface MedallionPipeline {
    @Node("csv-source") default DataSourceSpec csvSource() { ... }
    @Node("csv-ingest") @DependsOn("csv-source") default IngestionSpec csvIngestion() { ... }
    ...
    @GraphRule static List<GraphMutation> ensureMonitoring(@Match(type="sink") ...) { ... }
    @GraphInvariant static void everySinkHasUpstream(@Match(type="sink") ..., @DirectDep(type="transformer") ...) {}
}
```

8 declared nodes + 1 rule-generated (monitor) = 9 nodes after rule application.

## Running the Tests

```bash
mvn test -pl examples/pipeline-annotated
mvn test -pl examples/pipeline-annotated -Dtest=MedallionPipelineTest
```

## Insights

- **One file, full graph.** The annotation surface collapses what takes 136 lines of GoalCompiler code or 165 lines of YAML into a single 126-line interface. The trade-off: forEach, modules, and conditional nodes are not available in the annotation surface.
- **Review factories are default methods.** `@Tier(review = "createAiReview")` binds the fault escalation tier to a method on the same interface. The build-time processor validates the method exists and has the right signature `(FaultEvent, DesiredStateGraph) -> NodeSpec`.
- **Cross-surface reuse.** NodeSpec records (`DataSourceSpec`, `CleanserSpec`, etc.) live in `examples/pipeline/`. The annotation example only adds the declaration surface and a single new spec (`MonitorSpec` for the graph rule). All four pipeline examples share the same domain types.
