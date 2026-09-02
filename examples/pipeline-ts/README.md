# Data Pipeline -- TypeScript

TypeScript-declared medallion pipeline demonstrating the TS DSL surface with cross-surface `@GraphRule` monitoring. Side-by-side companion to `examples/pipeline-yaml/`.

**Declaration surface:** TypeScript compiled to `.ds.json` envelope + Java `@GraphRule` for cross-surface structural rewriting

## What It Demonstrates

| Feature | How |
|---------|-----|
| TypeScript graph declaration | `defineGraph()` + `node()` helper with discriminated union types in the TS SDK |
| JSON envelope format | Compiled `.ds.json` file with `kind`, `namespace`, `nodes[]`, `dependencies[]` |
| Cross-surface graph rules | `EnsureMonitoringRule.java` uses `@GraphRule(graph = {"pipeline:*"})` to apply monitoring rules to the TS-declared graph from Java |
| Human gating | `humanGating: "PROVISION_ONLY"` on the warehouse sink |
| Multi-source dependencies | Two regional data sources feed a single transformer |

## Key Files

| File | Purpose |
|------|---------|
| `medallion-pipeline.ds.json` | Compiled graph envelope: 4 nodes (2 sources, 1 transformer, 1 sink), 3 dependencies |
| `EnsureMonitoringRule.java` | `@GraphRule(graph = {"pipeline:*"})` -- cross-surface rule that auto-wires monitoring for sinks declared in any pipeline namespace |
| `PipelineTsTest.java` | 7 tests -- compilation, node count, dependencies, rule application, human gating |

## Cross-Surface Rules

The defining feature of the TS surface is that graph rules and invariants can be written in Java and applied to TS-declared graphs. `EnsureMonitoringRule` demonstrates this:

```java
@GraphRule(graph = {"pipeline:*"})
public class EnsureMonitoringRule {
    @GraphRule
    public static List<GraphMutation> ensureMonitoring(
            @Match(type = "sink") DesiredNode sink,
            @NotExists(type = "monitor", of = "sink", direction = Direction.DEPENDENTS) Void guard) {
        return GraphMutations.addNodeDependingOn(...);
    }
}
```

The `graph = {"pipeline:*"}` pattern means this rule applies to any graph in the `pipeline` namespace -- regardless of whether that graph was declared in Java, YAML, or TypeScript. The build extension discovers both the `.ds.json` graph and the `@GraphRule` class, then applies the rule at compile time.

## Graph Shape

```
source-us-east --\
                  +--> csv-ingest --> warehouse-sink --> [monitor-warehouse-sink] (rule-generated)
source-eu-west --/
```

4 declared nodes + 1 rule-generated = 5 nodes after compilation.

## Running the Tests

```bash
mvn test -pl examples/pipeline-ts
mvn test -pl examples/pipeline-ts -Dtest=PipelineTsTest
```

## Insights

- **TypeScript for structure, Java for policy.** The TS surface handles graph declaration (what exists and how it connects). Rules, invariants, and fault policies are Java-side concerns that apply across all declaration surfaces. This separation means operators can write graphs in TypeScript while platform teams enforce structural constraints in Java.
- **The `.ds.json` envelope is the compilation target.** The TypeScript SDK's `defineGraph()` produces a JSON envelope that the Quarkus build extension discovers at `META-INF/desiredstate/*.ds.json`. The envelope format is stable -- the SDK can evolve independently of the runtime.
- **Cross-surface is the point.** A rule written once in Java applies to graphs from YAML, TypeScript, and annotations. This is why the pipeline-ts example exists: to prove that structural policies compose across declaration boundaries.
