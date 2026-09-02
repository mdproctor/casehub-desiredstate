# Data Pipeline -- YAML

YAML-driven medallion pipeline with the richest feature set of any pipeline surface: variables, forEach iteration, conditional nodes, modules, invariants, rules, fault policies, lifecycle phases, and human gating. Side-by-side companion to `examples/pipeline-annotated/`.

**Declaration surface:** YAML files at `META-INF/desiredstate/`

## What It Demonstrates

| Feature | How |
|---------|-----|
| Full YAML vocabulary | Two YAML files exercise every YAML primitive |
| Variables | `${var.batch_size}`, `${var.source_uri}` -- environment-specific config |
| forEach iteration | `regional` iterator stamps `regional-source` and `regional-ingest` across `["us-east", "eu-west"]` |
| Conditional nodes | `debug-validator` included only when `${var.debug_mode}` is truthy |
| Optional dependencies | `{ node: csv-ingest, optional: true }` -- silently removed when the target is excluded |
| Module imports | `monitoring` module imported as `pipe-monitor` with parameters |
| Invariants | `every-sink-has-upstream` (structural) + `minimum-data-sources` (cardinality with `minCount`) |
| Rules | `ensure-monitoring` auto-wires a monitor node for every sink |
| Fault policies | Three-tier escalation: retry -> AI review (3) -> human review (5) with `${fault.nodeId}` templates |
| Human gating | `humanGating: PROVISION_ONLY` on Gold-tier nodes |
| Lifecycle phases | `lifecycle-pipeline.yaml` splits the graph into infrastructure -> processing -> delivery phases |
| Cardinality constraints | `minCount: 1` on data-source match -- enforces minimum node count |

## Key Files

| File | Purpose |
|------|---------|
| `medallion-pipeline.yaml` | Single-graph pipeline: 10 declared nodes, 2 forEach stamps, 1 conditional, 1 module import, rules, invariants, fault policy |
| `lifecycle-pipeline.yaml` | Same pipeline as a 3-phase lifecycle: infrastructure (sources/schemas) -> processing (cleanse/validate) -> delivery (transform/sink) |
| `modules/monitoring.yaml` | Reusable monitoring module with `watched_node_id` and `alert_email` parameters |
| `PipelineYamlTest.java` | 21 tests -- compilation, node counts, forEach expansion, conditional inclusion/exclusion, module import, rule application, fault policy |
| `LifecyclePipelineTest.java` | 9 tests -- phase count, phase node assignment, phase ordering, cross-phase dependencies |

## Graph Shapes

**Single graph** (`medallion-pipeline.yaml`): 8 core nodes + 2 forEach stamps + 1 conditional + 2 module nodes + 1 rule-generated = up to 14 nodes.

**Lifecycle** (`lifecycle-pipeline.yaml`): Same pipeline split into three phases:
- Phase 1 (infrastructure): `csv-source`, `customer-schema` -- must all be PRESENT before Phase 2
- Phase 2 (processing): `csv-ingest`, `dedup-cleanser`, `quality-validator` -- must all be PRESENT before Phase 3
- Phase 3 (delivery): `aggregate-tx`, `warehouse-sink` -- runs indefinitely (`completionCondition: never`)

## Running the Tests

```bash
mvn test -pl examples/pipeline-yaml
mvn test -pl examples/pipeline-yaml -Dtest=PipelineYamlTest
mvn test -pl examples/pipeline-yaml -Dtest=LifecyclePipelineTest
```

## Insights

- **YAML is the richest surface.** forEach, modules, conditional nodes, lifecycle phases, and cardinality constraints are YAML-only features. The annotation and TypeScript surfaces cannot express them.
- **Lifecycle phases impose ordering without dependencies.** In the single-graph version, all nodes exist simultaneously and dependency ordering handles sequencing. In the lifecycle version, entire phases gate on completion conditions -- infrastructure must be fully provisioned before processing begins. This is a fundamentally different execution model using the same node definitions.
- **Optional dependencies bridge conditional and unconditional nodes.** `debug-validator` depends on `csv-ingest` with `optional: true`. When `debug_mode` is false, the debug-validator is excluded AND the dependency is silently removed. Without `optional`, the dependency would fail validation (dangling reference to an excluded node).
- **Cardinality constraints are invariants on count.** `minCount: 1` on data-source match is a compile-time check that at least one data source exists. This prevents a pipeline from compiling with zero inputs -- a structural bug that would otherwise only surface at runtime.
