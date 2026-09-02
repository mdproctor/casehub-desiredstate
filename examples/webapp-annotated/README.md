# E-Commerce Tutorials -- Java Annotations

Annotation-driven companion to `webapp-yaml/`. Implements the same three
e-commerce tutorials using Java annotations instead of YAML, demonstrating
the annotation surface for desired-state graph declarations.

## Tutorials

### Tutorial 1: Store Basics

**File:** `Tutorial1StoreBasics.java`

Five-node order flow declared with `@DesiredState`, `@Node`, and `@DependsOn`.
Each `@Node` method returns a typed `NodeSpec` record.

```
product-catalog -> shopping-cart -> payment -> order-confirmation -> shipping
```

Equivalent YAML: `webapp-yaml/.../tutorial-1-store-basics.yaml`

### Tutorial 2: Smart Defaults

**File:** `Tutorial2SmartDefaults.java`

Builds on Tutorial 1 with four annotation features:

| Feature | YAML equivalent | Annotation mechanism |
|---------|----------------|---------------------|
| Invariants | `invariants:` block | `@GraphInvariant` on static void method with `@Match`/`@DirectDep` pattern parameters |
| Rules | `rules:` block | `@GraphRule` on static method returning `List<GraphMutation>` with `@Match`/`@NotExists` |
| Fault policies | `faultPolicy:` block | `@FaultPolicyDef` + `@Tier` on the interface, review factory as default methods |
| Conditional nodes | `when:` field | No annotation equivalent -- GoalCompiler controls inclusion |

The `@GraphInvariant` "paymentRequiresFraudCheck" enforces that every payment
node has a fraud-check dependent. The `@GraphRule` "autoNotifyConfirmations"
adds a notification node for any order-confirmation without one.

`@FaultPolicyDef` defines two escalation tiers: 3 failures triggers an
automated fraud review, 5 failures triggers a human support ticket.

Equivalent YAML: `webapp-yaml/.../tutorial-2-smart-defaults.yaml`

### Tutorial 3: Scale and Compose

**File:** `Tutorial3ScaleAndCompose.java`

forEach and modules are YAML-only language features. This tutorial demonstrates
the Java equivalent: a programmatic `GoalCompiler<Void>` that builds the graph
in code.

- **forEach equivalent:** An explicit loop stamps 3 shipping nodes across warehouses
- **Module equivalent:** A helper method creates notification pairs (email + SMS)

The verbosity difference is the point -- the YAML surface exists so operators
don't need to write this Java code.

Equivalent YAML: `webapp-yaml/.../tutorial-3-scale-and-compose.yaml`

## Surface Comparison

| Concept | YAML | Java Annotations |
|---------|------|-----------------|
| Node declaration | `nodes: { cart: { type: shopping-cart } }` | `@Node("cart") default ShoppingCartSpec cart() { ... }` |
| Dependencies | `dependsOn: [catalog]` | `@DependsOn("catalog")` |
| Variables | `${var.currency}` | Constructor arguments (type-safe) |
| Conditional nodes | `when: "${var.enabled}"` | GoalCompiler decides which nodes to include |
| Invariants | `invariants:` block with pattern vocabulary | `@GraphInvariant` on static void method |
| Rules | `rules:` block with action templates | `@GraphRule` on static method returning `List<GraphMutation>` |
| Fault policies | `faultPolicy:` block with tier templates | `@FaultPolicyDef` + `@Tier` + review factory methods |
| forEach | `forEach: warehouses` | Explicit loop in GoalCompiler |
| Modules | `imports: [{ module: X, as: Y }]` | Helper method in GoalCompiler |

## Running

```bash
# All annotation tutorials
mvn test -pl examples/webapp-annotated

# A specific tutorial test
mvn test -pl examples/webapp-annotated -Dtest=Tutorial3ScaleAndComposeTest
```
