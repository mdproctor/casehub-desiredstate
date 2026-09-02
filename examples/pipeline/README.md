# Data Pipeline -- Programmatic GoalCompiler

Medallion-architecture data pipeline (Bronze/Silver/Gold) demonstrating the full SPI surface: GoalCompiler, NodeProvisioner, ActualStateAdapter, FaultPolicy, ExecutionBackend. This is the base module -- all other pipeline examples (`pipeline-annotated`, `pipeline-yaml`, `pipeline-ts`) share its NodeSpec types.

**Declaration surface:** Programmatic Java (`PipelineGoalCompiler` implements `GoalCompiler<PipelineBlueprint>`)

## What It Demonstrates

| Feature | How |
|---------|-----|
| Domain-typed GoalCompiler | `PipelineGoalCompiler` translates a `PipelineBlueprint` (domain DSL) into a desired-state graph. Dependencies are inferred from the canonical pipeline topology -- the user never declares them. |
| Medallion layer constraint | `MedallionLayerConstraint.validate()` enforces Bronze-before-Silver-before-Gold ordering at compile time. |
| Pluggable execution backends | `ExecutionBackend` SPI lets each processing stage choose its execution strategy. `DefaultExecutionBackend` handles synchronous in-memory execution. |
| Three-tier fault escalation | `ThresholdFaultPolicy` with tiers: retry -> AI review (3 failures) -> human review (5 failures). `SchemaDriftFaultPolicy` and `QuarantineFaultPolicy` handle domain-specific fault paths. |
| PendingApproval gates | Gold-tier nodes (`transformer`, `sink`) require approval before provisioning. `PipelineProvisioner` returns `PendingApproval` for nodes with `approvalRequired`. |
| AI-assisted fault diagnosis | `PipelineProvisioner.provisionAiReview()` calls `AgentProvider` to diagnose failures, resolving automatically when possible. |
| Full reconciliation loop | `PipelineTest` runs multi-cycle reconciliation with drift detection, fault escalation, and graph mutation. |
| CaseTransitionExecutor integration | `PipelineCaseTransitionTest` demonstrates engine-backed orchestration with Worker(Workflow) phases. |

## Key Files

| File | Purpose |
|------|---------|
| `PipelineBlueprint.java` | Domain DSL -- records describing sources, schemas, stages |
| `PipelineGoalCompiler.java` | Translates blueprint into graph with inferred dependencies |
| `PipelineProvisioner.java` | Dispatches to `ExecutionBackend` per node type |
| `PipelineActualStateAdapter.java` | Reads actual state from `PipelineWorld`, including orphan detection |
| `PipelineWorld.java` | In-memory world state (stages, sources, schemas, reviews) |
| `ExecutionBackend.java` | SPI for pluggable stage execution |
| `DefaultExecutionBackend.java` | Synchronous in-memory execution |
| `QuarantineFaultPolicy.java` | Escalates quarantined validators to human review |
| `SchemaDriftFaultPolicy.java` | Escalates schema version mismatches to human review |
| `MedallionLayerConstraint.java` | Compile-time validation: no Gold-before-Silver dependencies |
| `PipelineTest.java` | 29 tests -- reconciliation, fault escalation, drift, approval |
| `ExecutionBackendTest.java` | 9 tests -- backend dispatch, ambiguity detection |
| `PipelineCaseTransitionTest.java` | Engine-adapter integration (Worker/Workflow phases) |

## NodeSpec Types (shared by all pipeline examples)

| Type ID | Record | Layer | Purpose |
|---------|--------|-------|---------|
| `data-source` | `DataSourceSpec` | Bronze | Raw data source (name, format, URI) |
| `schema` | `SchemaSpec` | Bronze | Schema definition (fields, version) |
| `ingestion` | `IngestionSpec` | Bronze | Batch ingestion from source |
| `cleanser` | `CleanserSpec` | Silver | Deduplication and null handling |
| `enricher` | `EnricherSpec` | Silver | Lookup join enrichment |
| `validator` | `ValidatorSpec` | Silver | Quality threshold + anomaly detection |
| `transformer` | `TransformerSpec` | Gold | Aggregation and reshape |
| `sink` | `SinkSpec` | Gold | Output destination |
| `ai-review` | `AiReviewSpec` | Fault | AI-assisted fault diagnosis |
| `human-review` | `HumanReviewSpec` | Fault | Human escalation |
| `monitor` | `MonitorSpec` | Rule | Auto-wired monitoring (via @GraphRule) |

## Pipeline Graph Shape

```
datasource <- ingestion <- cleanser <- enricher <- validator <- transformer <- sink
schema ------------------> cleanser
schema -----------------------------------------> validator
```

Dependencies flow left-to-right (Bronze -> Silver -> Gold). The GoalCompiler infers all edges from the stage ordering -- the blueprint author only declares stages, not dependencies.

## Surface Comparison (Pipeline Family)

| Concept | Programmatic | Annotations | YAML | TypeScript |
|---------|-------------|-------------|------|------------|
| Module | `pipeline/` | `pipeline-annotated/` | `pipeline-yaml/` | `pipeline-ts/` |
| Graph declaration | `GoalCompiler<PipelineBlueprint>` | `@DesiredState` + `@Node` + `@DependsOn` | `nodes:` + `dependsOn:` | `.ds.json` envelope |
| Dependencies | Inferred by compiler | `@DependsOn` annotation | `dependsOn:` field | `dependencies:` array |
| Fault policy | `ThresholdFaultPolicy` builder | `@FaultPolicyDef` + `@Tier` | `faultPolicy:` block | N/A (Java-side) |
| Graph rules | N/A (compiler handles) | `@GraphRule` on static method | `rules:` block | `@GraphRule` (cross-surface) |
| Graph invariants | `MedallionLayerConstraint` | `@GraphInvariant` on static method | `invariants:` block | N/A (Java-side) |
| Variables | Constructor args | Constructor args | `${var.name}` | Compile-time constants |
| forEach | Explicit loop in compiler | N/A | `forEach: regional` | N/A |
| Modules | N/A | N/A | `imports:` block | N/A |
| Lifecycle phases | N/A | N/A | `lifecycle: phases:` | N/A |
| Conditional nodes | Logic in compiler | N/A | `when:` field | N/A |
| Human gating | `HumanGating` enum | `@Node(humanGating=...)` | `humanGating:` field | `humanGating:` field |

## Running the Tests

```bash
# All pipeline tests (programmatic surface)
mvn test -pl examples/pipeline

# Specific test class
mvn test -pl examples/pipeline -Dtest=PipelineTest
mvn test -pl examples/pipeline -Dtest=ExecutionBackendTest
mvn test -pl examples/pipeline -Dtest=PipelineCaseTransitionTest
```

## Insights

- **The GoalCompiler is the escape hatch.** When YAML primitives aren't enough (custom dependency inference, programmatic conditionals, domain-specific validation), a GoalCompiler gives full control over graph construction. The trade-off is verbosity -- compare `PipelineGoalCompiler` (136 lines) with the equivalent YAML (165 lines) or annotations (126 lines).
- **Fault policies compose.** `ThresholdFaultPolicy` handles generic escalation, while `QuarantineFaultPolicy` and `SchemaDriftFaultPolicy` handle domain-specific paths. The `FaultPolicyEngine` evaluates all policies -- they don't compete.
- **ExecutionBackend demonstrates the provisioner-within-a-provisioner pattern.** `PipelineProvisioner` is the `NodeProvisioner` (runtime SPI), but it delegates actual work to `ExecutionBackend` implementations. This lets the same pipeline run on different execution engines without changing the desired-state graph.
