# E-Commerce Tutorials -- Shared NodeSpec Types

Shared domain types used by the e-commerce tutorial family. This module has no
tests of its own -- it provides `NodeSpec` records and `NodeType` constants
consumed by three surface-specific tutorial modules:

| Module | Surface | What it demonstrates |
|--------|---------|---------------------|
| `webapp-yaml/` | YAML | Nodes, dependencies, variables, invariants, rules, conditions, forEach, modules |
| `webapp-annotated/` | Java annotations | `@DesiredState`, `@Node`, `@DependsOn`, `@GraphRule`, `@GraphInvariant`, `@FaultPolicyDef` |
| `webapp/` | _(this module)_ | Shared types only -- no tutorials |

## Types

| Type ID | Record | Fields |
|---------|--------|--------|
| `product-catalog` | `ProductCatalogSpec` | name, maxProducts, currency |
| `shopping-cart` | `ShoppingCartSpec` | sessionTimeoutMinutes, maxItems |
| `payment` | `PaymentSpec` | provider, currency, maxRetries |
| `fraud-check` | `FraudCheckSpec` | threshold, provider |
| `order-confirmation` | `OrderConfirmationSpec` | template, emailEnabled |
| `shipping` | `ShippingSpec` | carrier, warehouse, trackingEnabled |
| `notification` | `NotificationSpec` | channel, target |
| `gift-wrapping` | `GiftWrappingSpec` | style, surcharge |
| `loyalty` | `LoyaltySpec` | program, pointsPerDollar |
| `fraud-review` | `FraudReviewSpec` | targetNode, errorDetail |
| `support-ticket` | `SupportTicketSpec` | targetNode, errorDetail, priority |

All types carry `@NodeTypeId` annotations matching their type ID, making them
discoverable by the YAML and annotation build-time processors.

`StoreNodeTypes` provides `NodeType` constants for use in programmatic
`GoalCompiler` implementations.

## Running

```bash
# Build the shared types (no tests to run)
mvn install -pl examples/webapp
```
